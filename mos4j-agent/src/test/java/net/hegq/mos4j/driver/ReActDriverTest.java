package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * 可编排的流式假模型：先推增量再给聚合结果（模拟真实流式链路）。
     *
     * <p>非流式接口直接断言「不该被调到」——流式开关打开后若还走非流式，
     * 那就是接线断层。
     */
    private static final class StreamingModel implements ChatModel {

        private final List<Response> script = new ArrayList<>();
        private final AtomicInteger index = new AtomicInteger();
        private final AtomicInteger streamCalls = new AtomicInteger();

        private StreamingModel respond(Response response) {
            script.add(response);
            return this;
        }

        @Override
        public Response chat(Request request) {
            throw new AssertionError("stream(true) 时应走 chatStream，不能落到非流式");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chatStream(request, emitter, CancellationToken.none());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            streamCalls.incrementAndGet();
            int i = index.getAndIncrement();
            if (emitter != null) {
                try {
                    emitter.emit(new StreamChunk("思", "推理", null, null));
                    emitter.emit(new StreamChunk("考", null, null, null));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return i < script.size() ? script.get(i)
                    : new Response(Message.assistant("（无脚本可答）"), null, "stop");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.FUNCTION_CALLING, Capability.STREAMING);
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
        List<net.hegq.mos4j.model.Tool> tools = model.requests.get(0).tools();
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
    void theConfiguredMaxTokensReachesEveryRequest() throws Exception {
        // 模型级配置（对齐 Go 的 Config["max_tokens"]）：配了就必须真的进请求，
        // 否则宿主以为自己限了输出，其实请求里根本没这个字段
        ScriptedModel model = new ScriptedModel().respond(answerResponse("好"));
        Agent agent = Agent.of(Config.builder().model(model).maxTokens(123).build());

        agent.run(new Session("s1", null), "干活");

        assertEquals(1, model.requests.size());
        assertEquals(123, model.requests.get(0).maxTokens());
    }

    @Test
    void withoutTheSettingMaxTokensStaysUnset() throws Exception {
        ScriptedModel model = new ScriptedModel().respond(answerResponse("好"));
        Agent agent = Agent.of(Config.builder().model(model).build());

        agent.run(new Session("s1", null), "干活");

        assertNull(model.requests.get(0).maxTokens(), "没配就留空（用协议自己的缺省）");
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

    // ------------------------------------------------------------------ 流式接线

    @Test
    void onChunkReceivesIncrementsWhenStreamIsOn() throws Exception {
        // 回归：onChunk 曾经在 RunContext 里存在但宿主装不上
        // （Agent.Builder / Mos4j.Builder 都没这个方法），流式开关打开后
        // 增量被推给一个空消费者——「有参数、没入口」比「没这个能力」更难排查
        StreamingModel model = new StreamingModel().respond(answerResponse("思考"));
        List<StreamChunk> seen = new ArrayList<>();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .stream(true)
                .onChunk(seen::add)
                .build());

        agent.run(new Session("s1", null), "想一想");

        assertEquals(1, model.streamCalls.get(), "stream(true) 必须走 chatStream");
        assertEquals(2, seen.size(), "两个增量块都应到达宿主: " + seen);
        assertEquals("思", seen.get(0).delta());
        assertEquals("推理", seen.get(0).reasoning(), "思维链增量要原样透出，不能被合并掉");
        assertEquals("考", seen.get(1).delta());
    }

    @Test
    void streamOffNeverCallsOnChunk() throws Exception {
        // Go 同口径（TestStreamOnChunk 的反面）：Stream=false 时即便模型会流式也不走流式路径
        ScriptedModel model = new ScriptedModel().respond(answerResponse("好"));
        AtomicInteger chunks = new AtomicInteger();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .stream(false)
                .onChunk(chunk -> chunks.incrementAndGet())
                .build());

        agent.run(new Session("s1", null), "干活");

        assertEquals(0, chunks.get(), "stream(false) 不应触发 onChunk");
    }

    @Test
    void withoutOnChunkStreamingStillWorks() throws Exception {
        // 不装回调不能影响结果：流式链路照走，增量丢弃
        StreamingModel model = new StreamingModel().respond(answerResponse("思考"));
        Agent agent = Agent.of(Config.builder().model(model).stream(true).build());

        String reply = agent.run(new Session("s1", null), "想一想");

        assertEquals(1, model.streamCalls.get());
        assertEquals("思考", reply, "没装回调时最终作答仍应正常返回");
    }
}
