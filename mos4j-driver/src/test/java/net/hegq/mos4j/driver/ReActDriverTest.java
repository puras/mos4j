package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StreamChunk;
import net.hegq.mos4j.llm.StreamEmitter;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.llm.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 驱动的行为测试（T1.15 / T1.18）。
 *
 * <p>每个用例守一条「不写也会出事」的规则：工具定义漏传、截断调用被执行、
 * 工具失败中断整轮、决策器报错留下孤儿 turn。</p>
 */
class ReActDriverTest {

    /** 可编排的假模型：按脚本逐轮返回。 */
    private static final class ScriptedModel implements ChatModel {

        private final List<Response> script = new ArrayList<>();
        private final List<Request> requests = new ArrayList<>();
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel respond(Response response) {
            script.add(response);
            return this;
        }

        @Override
        public Response chat(Request request) {
            requests.add(request);
            int i = index.getAndIncrement();
            return i < script.size() ? script.get(i)
                    : new Response(Message.assistant("（无脚本可答）"), null, "stop");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.FUNCTION_CALLING);
        }
    }

    private static Response toolCallResponse(String callId, String tool, String args, String finish) {
        return new Response(
                new Message(Role.ASSISTANT, "调工具", null,
                        List.of(new ToolCall(callId, tool, args)), null, null, null),
                null, finish);
    }

    private static Response answerResponse(String text) {
        return new Response(Message.assistant(text), null, "stop");
    }

    private static ToolRegistry registry(AtomicInteger executed) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读取文件", null, args -> {
            executed.incrementAndGet();
            return "文件内容";
        }));
        return registry;
    }

    @Test
    void toolDefinitionsReachTheModel() throws Exception {
        ScriptedModel model = new ScriptedModel().respond(answerResponse("好"));
        Agent agent = Agent.of(Config.builder().model(model).tools(registry(new AtomicInteger())).build());

        agent.run(new Session("s1", null), "读一下");

        assertEquals(1, model.requests.size());
        List<net.hegq.mos4j.llm.model.Tool> tools = model.requests.get(0).tools();
        assertTrue(tools != null && !tools.isEmpty(),
                "工具定义必须进请求——漏了模型根本不知道有 read");
        assertEquals("read", tools.get(0).name());
        assertEquals("读取文件", tools.get(0).description());
        assertEquals("object", tools.get(0).parameters().get("type"));
    }

    @Test
    void truncatedToolCallIsNotExecuted() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        ScriptedModel model = new ScriptedModel()
                .respond(toolCallResponse("call_1", "read", "{\"path\":\"a", "length"))
                .respond(answerResponse("换个方式"));
        Agent agent = Agent.of(Config.builder().model(model).tools(registry(executed)).build());
        Session session = new Session("s1", null);

        agent.run(session, "读一下");

        assertEquals(0, executed.get(), "截断的工具调用参数不可信 → 拒绝执行");
        Event toolResult = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        assertTrue(toolResult.toolResult().isError(), toolResult.toolResult().content());
        assertTrue(toolResult.toolResult().content().contains("截断"), toolResult.toolResult().content());
    }

    @Test
    void toolFailureBecomesResultAndTurnContinues() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("boom", "会炸", null, args -> {
            throw new IllegalStateException("磁盘炸了");
        }));
        ScriptedModel model = new ScriptedModel()
                .respond(toolCallResponse("call_1", "boom", "{}", "tool_calls"))
                .respond(answerResponse("工具失败了，我改用别的方式"));
        Agent agent = Agent.of(Config.builder().model(model).tools(registry).build());
        Session session = new Session("s1", null);

        String answer = agent.run(session, "干活");

        assertEquals("工具失败了，我改用别的方式", answer);
        Event toolResult = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        assertTrue(toolResult.toolResult().isError(), "工具失败必须落成失败回执");
        assertTrue(toolResult.toolResult().content().contains("磁盘炸了"), toolResult.toolResult().content());
    }

    @Test
    void repeatedIdenticalCallGetsReminderFromThirdTime() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        ScriptedModel model = new ScriptedModel()
                .respond(toolCallResponse("call_1", "read", "{\"path\":\"a\"}", "tool_calls"))
                .respond(toolCallResponse("call_2", "read", "{\"path\":\"a\"}", "tool_calls"))
                .respond(toolCallResponse("call_3", "read", "{\"path\":\"a\"}", "tool_calls"))
                .respond(answerResponse("不再重试了"));
        Agent agent = Agent.of(Config.builder().model(model).tools(registry(executed)).build());
        Session session = new Session("s1", null);

        agent.run(session, "读一下");

        List<Event> results = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).toList();
        assertEquals(3, results.size());
        assertFalse(results.get(0).toolResult().content().contains("[提醒]"), "第 1 次不提醒");
        assertFalse(results.get(1).toolResult().content().contains("[提醒]"), "第 2 次不提醒");
        assertTrue(results.get(2).toolResult().content().contains("[提醒]"), "第 3 次起提醒");
        assertTrue(results.get(2).toolResult().content().contains("连续第 3 次"), results.get(2).toolResult().content());
    }

    @Test
    void maxDecisionRoundsStopsTurnWithError() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .respond(toolCallResponse("call_1", "read", "{}", "tool_calls"));
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registry(new AtomicInteger()))
                .maxDecisionRounds(1)
                .build());
        Session session = new Session("s1", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.run(session, "读一下"));

        assertTrue(error.getMessage().contains("决策次数"), error.getMessage());
        Event last = session.events().get(session.events().size() - 1);
        assertEquals(EventKind.TURN_END, last.kind());
        assertEquals("error", last.reason());
    }

    @Test
    void plannerFailureEndsTurnWithError() throws Exception {
        ChatModel broken = new ChatModel() {
            @Override
            public Response chat(Request request) {
                throw new IllegalStateException("服务端 500");
            }

            @Override
            public Response chat(Request request, CancellationToken cancellation) {
                return chat(request);
            }

            @Override
            public Response chatStream(Request request, StreamEmitter emitter) {
                return chat(request);
            }

            @Override
            public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
                return chat(request);
            }

            @Override
            public Set<Capability> capabilities() {
                return Set.of();
            }
        };
        Agent agent = Agent.of(Config.builder().model(broken).build());
        Session session = new Session("s1", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.run(session, "读一下"));

        assertTrue(error.getMessage().contains("服务端 500"), error.getMessage());
        Event last = session.events().get(session.events().size() - 1);
        assertEquals(EventKind.TURN_END, last.kind(), "决策器报错也必须收尾，不能留孤儿 turn");
        assertEquals("error", last.reason());
        assertTrue(last.error().contains("服务端 500"), last.error());
    }
}
