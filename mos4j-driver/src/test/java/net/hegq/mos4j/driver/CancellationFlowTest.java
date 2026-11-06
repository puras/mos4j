package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StreamEmitter;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消贯通：driver → planner → 模型 → 工具批次（T6.4 补齐 driver 侧）。
 *
 * <p>每个用例守一条「不写也会出事」的规则：
 * <ul>
 *   <li>已取消的轮次不应在日志里留下半个 turn（取消先于落盘）；</li>
 *   <li>取消后不得留下孤儿 turn（turn/start 必须配 turn/end）；</li>
 *   <li>取消后未开始的工具不得执行，但回执必须补上（配对合法）；</li>
 *   <li>令牌必须真的传到模型调用（否则「叫停」只是叫停了框架自己）。</li>
 * </ul>
 */
class CancellationFlowTest {

    /** 记录收到的令牌，并可在调用时触发取消。 */
    private static final class RecordingModel implements ChatModel {

        private final List<CancellationToken> tokens = new ArrayList<>();
        private final Runnable onCall;
        private final Response response;

        private RecordingModel(Response response, Runnable onCall) {
            this.response = response;
            this.onCall = onCall;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            tokens.add(cancellation);
            if (onCall != null) {
                onCall.run();
            }
            return response;
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

    private static Response toolCallResponse(String callId) {
        return new Response(
                new Message(Role.ASSISTANT, "调工具", null,
                        List.of(new ToolCall(callId, "read", "{}")), null, null, null),
                null, "tool_calls");
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
    void cancelledBeforeRunWritesNothing() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        RecordingModel model = new RecordingModel(
                new Response(Message.assistant("不该被叫到"), null, "stop"), null);
        Agent agent = Agent.of(Config.builder().model(model).build());
        Session session = new Session("s1", null);

        assertThrows(CancellationException.class, () -> agent.run(session, "干活", token));

        assertEquals(0, session.events().size(),
                "取消先于落盘：已取消的调用不该在日志里留下半个 turn");
        assertEquals(0, model.tokens.size(), "已取消就不该再调模型");
    }

    @Test
    void cancellationReachesTheModelCall() throws Exception {
        CancellationToken token = CancellationToken.create();
        RecordingModel model = new RecordingModel(
                new Response(Message.assistant("答"), null, "stop"), null);
        Agent agent = Agent.of(Config.builder().model(model).build());

        agent.run(new Session("s1", null), "干活", token);

        assertEquals(1, model.tokens.size());
        assertNotNull(model.tokens.get(0));
        assertFalse(model.tokens.get(0).isCancelled(),
                "令牌必须真的传到模型调用，否则「叫停」只叫停了框架自己");
    }

    @Test
    void cancelledDuringDecisionSkipsToolAndClosesTurn() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger executed = new AtomicInteger();
        // 模型被调用时宿主叫停：后面的工具批次不得再执行。
        RecordingModel model = new RecordingModel(toolCallResponse("call_1"), token::cancel);
        Agent agent = Agent.of(Config.builder().model(model).tools(registry(executed)).build());
        Session session = new Session("s1", null);

        assertThrows(CancellationException.class, () -> agent.run(session, "干活", token));

        assertEquals(0, executed.get(), "取消后未开始的工具不得执行");
        // 事件序列：turn/start → user → assistant → tool/call → tool/result → turn/end
        List<Event> events = session.events();
        assertEquals(EventKind.TURN_START, events.get(0).kind());
        assertEquals(EventKind.TURN_END, events.get(events.size() - 1).kind(),
                "取消也必须收尾，不能留孤儿 turn");
        assertEquals("interrupted", events.get(events.size() - 1).reason(),
                "取消复用已冻结的 interrupted 取值，不新增 reason 值");
        assertTrue(events.get(events.size() - 1).error().contains("已取消"),
                events.get(events.size() - 1).error());

        // assistant 的工具调用必须有配对的 tool/result（否则历史非法）
        long calls = events.stream().filter(e -> e.kind() == EventKind.TOOL_CALL).count();
        long results = events.stream().filter(e -> e.kind() == EventKind.TOOL_RESULT).count();
        assertEquals(calls, results, "工具调用与回执必须一一配对");
        Event result = events.stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        assertTrue(result.toolResult().isError(), "被取消的调用必须落成失败回执");
        assertTrue(result.toolResult().content().contains("已取消"), result.toolResult().content());
    }

    @Test
    void cancelledRunLeavesNoOrphanTurnInTheLog() {
        CancellationToken token = CancellationToken.create();
        RecordingModel model = new RecordingModel(
                new Response(Message.assistant("答"), null, "stop"), token::cancel);
        Agent agent = Agent.of(Config.builder().model(model).build());
        Session session = new Session("s1", null);

        assertThrows(CancellationException.class, () -> agent.run(session, "干活", token));

        long starts = session.events().stream().filter(e -> e.kind() == EventKind.TURN_START).count();
        long ends = session.events().stream().filter(e -> e.kind() == EventKind.TURN_END).count();
        assertEquals(starts, ends, "turn/start 与 turn/end 必须配平");
        assertEquals(1, ends);
    }

    @Test
    void noTokenMeansNoCancellation() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        RecordingModel model = new RecordingModel(
                new Response(Message.assistant("答"), null, "stop"), null);
        Agent agent = Agent.of(Config.builder().model(model).tools(registry(executed)).build());

        String answer = agent.run(new Session("s1", null), "干活");

        assertEquals("答", answer);
    }
}
