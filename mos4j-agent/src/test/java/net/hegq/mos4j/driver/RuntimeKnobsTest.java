package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.policy.ParamPolicy;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期可调参数（并发上限 / 决策轮数上限 / 参数级规则）与取消原因。
 *
 * <p>每个用例守一条「不写也会出事」的规则：
 * <ul>
 *   <li><b>下一轮生效</b>：正在跑的那一轮已经取过旋钮值——半路改会让同一批工具的
 *       并行度前后不一致，审计对不上；</li>
 *   <li><b>真的取当前值</b>：旋钮必须在每轮开始时现读，不能只在装配期拍死；</li>
 *   <li><b>没装就没有槽</b>：没装参数级策略时热替换必须报错，不能「假装生效」；</li>
 *   <li><b>取消原因要落地</b>：原因必须出现在 turn/end 的 error 与抛出的异常里，
 *       否则审计里只剩笼统的「已取消」。</li>
 * </ul>
 */
class RuntimeKnobsTest {

    /** 可编排的模型：按预设脚本返回响应，并记录决策次数。 */
    private static final class ScriptedModel implements ChatModel {

        private final java.util.function.Supplier<Response> script;
        private final AtomicInteger decisions = new AtomicInteger();
        private final Runnable onCall;

        private ScriptedModel(java.util.function.Supplier<Response> script, Runnable onCall) {
            this.script = script;
            this.onCall = onCall;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            decisions.incrementAndGet();
            if (onCall != null) {
                onCall.run();
            }
            return script.get();
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

    private static Response toolCalls(List<ToolCall> calls) {
        return new Response(
                new Message(Role.ASSISTANT, "调工具", null, calls, null, null, null),
                null, "tool_calls");
    }

    private static Response toolCall(String callId, String args) {
        return toolCalls(List.of(new ToolCall(callId, "read", args)));
    }

    @Test
    void cancellationReasonReachesTurnEndAndTheThrownError() {
        CancellationToken token = CancellationToken.create();
        ScriptedModel model = new ScriptedModel(
                () -> new Response(Message.assistant("不该被采纳"), null, "stop"),
                () -> token.cancel(new IllegalStateException("用户点了停止")));
        Agent agent = Agent.of(Config.builder().model(model).build());
        Session session = new Session("s1", null);

        CancellationException thrown = assertThrows(CancellationException.class,
                () -> agent.run(session, "干活", token));

        assertTrue(thrown.getMessage().contains("用户点了停止"),
                "抛出的异常必须解释具体原因：" + thrown.getMessage());
        List<Event> events = session.events();
        Event end = events.get(events.size() - 1);
        assertEquals(EventKind.TURN_END, end.kind());
        assertEquals("interrupted", end.reason(), "取消沿用已冻结的 reason 取值");
        assertTrue(end.error() != null && end.error().contains("用户点了停止"),
                "turn/end 的 error 必须带原因：" + end.error());
    }

    @Test
    void maxDecisionRoundsIsReadAtTheStartOfEveryRound() {
        AtomicInteger decisions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(() -> {
            decisions.incrementAndGet();
            return toolCall("call_" + decisions.get(), "{}");
        }, null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读取", null, args -> "内容"));
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registry)
                .maxDecisionRounds(2)
                .build());
        Session session = new Session("s1", null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> agent.run(session, "干活", null));
        assertTrue(thrown.getMessage().contains(ReActDriver.ERR_MAX_DECISION_ROUNDS));
        assertEquals(2, decisions.get(), "装配期的轮数上限必须在第一轮就生效");

        // 运行期调大：下一轮必须读到新值（而不是继续用装配期的 2）。
        agent.setMaxDecisionRounds(5);
        assertEquals(5, agent.maxDecisionRounds());
        assertThrows(IllegalStateException.class, () -> agent.run(session, "再干", null));
        assertEquals(7, decisions.get(), "运行期调大后新一轮多跑 5 轮（2 + 5）");
    }

    @Test
    void concurrencyIsHotSwappable() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(() -> toolCalls(List.of(
                new ToolCall("c1", "read", "{}"),
                new ToolCall("c2", "read", "{}"),
                new ToolCall("c3", "read", "{}"),
                new ToolCall("c4", "read", "{}"))), null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new net.hegq.mos4j.tool.Tool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "读取";
            }

            @Override
            public Object execute(String argsJson) {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return "内容";
            }

            @Override
            public boolean concurrencySafe(String argsJson) {
                // 并发是显式 opt-in：不声明就独占（串行）。
                return true;
            }
        });
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registry)
                .concurrency(1)
                .maxDecisionRounds(1)
                .build());
        Session session = new Session("s1", null);

        // 模型永远返回工具调用 → 一轮后触顶（轮数上限 1），工具已执行，峰值可观测。
        assertThrows(IllegalStateException.class, () -> agent.run(session, "干活", null));
        assertEquals(1, peak.get(), "并发上限 1 时工具必须串行");

        peak.set(0);
        agent.setConcurrency(4);
        assertEquals(4, agent.concurrency());
        assertThrows(IllegalStateException.class, () -> agent.run(session, "再干", null));
        assertTrue(peak.get() > 1, "运行期调大并发后新一轮必须并行：" + peak.get());
    }

    @Test
    void paramRulesAreHotSwappable() throws Exception {
        AtomicInteger decisions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(() -> decisions.getAndIncrement() % 2 == 0
                ? toolCall("call_1", "{\"path\":\"/etc/passwd\"}")
                : new Response(Message.assistant("答"), null, "stop"), null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读取", null, args -> "内容"));
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registry)
                .paramPolicy(ParamPolicy.builder()
                        .rule("read", "path", ParamPolicy.Verdict.ALLOW)
                        .build())
                .build());
        Session session = new Session("s1", null);

        // 收紧：参数级规则换到 deny → 工具不得执行。
        agent.setRules(ParamPolicy.builder()
                .rule("read", "path", ParamPolicy.Verdict.DENY)
                .build());
        agent.run(session, "干活", null);
        assertTrue(lastToolResult(session).contains("被策略拒绝"),
                "deny 档必须挡住工具：" + lastToolResult(session));

        // 放开：换回 allow → 工具可执行。
        agent.setRules(ParamPolicy.builder()
                .rule("read", "path", ParamPolicy.Verdict.ALLOW)
                .build());
        agent.run(session, "再干", null);
        assertEquals("内容", lastToolResult(session),
                "allow 档必须放行工具：" + lastToolResult(session));
    }

    @Test
    void setRulesWithoutInstalledParamPolicyFailsClosed() {
        ScriptedModel model = new ScriptedModel(() -> new Response(Message.assistant("答"), null, "stop"), null);
        Agent agent = Agent.of(Config.builder().model(model).build());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> agent.setRules(ParamPolicy.builder()
                        .rule("read", "path", ParamPolicy.Verdict.DENY)
                        .build()));
        assertTrue(thrown.getMessage().contains("参数级策略"),
                "报错要说清缺什么：" + thrown.getMessage());
    }

    private static String lastToolResult(Session session) {
        for (int i = session.events().size() - 1; i >= 0; i--) {
            Event event = session.events().get(i);
            if (event.kind() == EventKind.TOOL_RESULT) {
                return event.toolResult().content();
            }
        }
        return "";
    }
}
