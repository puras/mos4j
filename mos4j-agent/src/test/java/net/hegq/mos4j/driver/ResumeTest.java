package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.session.Approval;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.JsonlPersistence;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续跑（Go 差异 #2）与审批先验（Go 差异 #3）的端到端行为。
 *
 * <p>对位 Go 的 {@code agent/driver/react/resume.go} 与 {@code prior.go}。
 *
 * <p><b>六条纪律</b>：
 * <ul>
 *   <li><b>显式续跑</b>：框架不自动拾起挂起的轮次；</li>
 *   <li><b>沿用原轮次号</b>：审批决定的复用键是 {@code (turn, callId)}；</li>
 *   <li><b>载荷原样重放</b>：id / 名字 / 参数都不改；</li>
 *   <li><b>走普通安全链</b>：守卫裁决、审批、审计照常；</li>
 *   <li><b>回填真实结果</b>：不是投影里那种合成失败回执；</li>
 *   <li><b>没有可续的就不收尾</b>：那一轮保持挂起。</li>
 * </ul>
 */
class ResumeTest {

    /** 只回答、不调工具的模型（续跑后正常收尾）。 */
    private static final class AnsweringModel implements ChatModel {

        private final AtomicInteger decisions = new AtomicInteger();

        @Override
        public Response chat(Request request) {
            return chat(request, net.hegq.mos4j.core.CancellationToken.none());
        }

        @Override
        public Response chat(Request request, net.hegq.mos4j.core.CancellationToken cancellation) {
            decisions.incrementAndGet();
            return new Response(Message.assistant("答"), null, "stop");
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter,
                                   net.hegq.mos4j.core.CancellationToken cancellation) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.FUNCTION_CALLING);
        }
    }

    /** 记录工具收到的参数（验证载荷原样重放）。 */
    private static final class RecordingTool implements net.hegq.mos4j.tool.Tool {

        private final AtomicReference<String> received = new AtomicReference<>();

        @Override
        public String name() {
            return "write";
        }

        @Override
        public String description() {
            return "写";
        }

        @Override
        public Object execute(String argsJson) {
            received.set(argsJson);
            return "written";
        }
    }

    /**
     * 一轮「崩在半路」的现场：开了轮、批了准、声明了调用，但没回填结果、没收尾。
     */
    private static Session crashedSession(String callId, String args) throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.USER).turn(1).text("干活").build());
        session.append(Event.builder(EventKind.ASSISTANT).turn(1).text("调工具")
                .toolCalls(List.of(new ToolCall(callId, "write", args))).build());
        session.append(Event.builder(EventKind.APPROVAL_ASKED).turn(1)
                .approval(new Approval("ap_old", "write", callId, "要写文件", null)).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(new Approval("ap_old", "write", callId, null, "approved")).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall(callId, "write", args)).build());
        return session;
    }

    private static Agent agent(RecordingTool tool, ApprovalChannel channel) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        Config.Builder builder = Config.builder()
                .model(new AnsweringModel())
                .tools(registry);
        if (channel != null) {
            builder.approvals(channel);
        }
        return Agent.of(builder.build());
    }

    private static List<Event> eventsOfKind(Session session, EventKind kind) {
        List<Event> found = new ArrayList<>();
        for (Event event : session.events()) {
            if (event.kind() == kind) {
                found.add(event);
            }
        }
        return found;
    }

    @Test
    void resumeKeepsTheOriginalTurnAndReplaysThePayloadVerbatim() throws Exception {
        RecordingTool tool = new RecordingTool();
        Session session = crashedSession("c1", "{\"path\":\"a.txt\",\"n\":1}");

        String answer = agent(tool, null).resume(session, ResumeOptions.latest());

        assertEquals("答", answer);
        assertEquals("{\"path\":\"a.txt\",\"n\":1}", tool.received.get(),
                "载荷原样重放：id / 名字 / 参数都不改");
        assertEquals(1, eventsOfKind(session, EventKind.TURN_START).size(),
                "续跑不新开一轮");
        assertEquals(1, eventsOfKind(session, EventKind.USER).size(),
                "续跑不补 user 消息（那一轮的输入已经在日志里）");

        Event result = eventsOfKind(session, EventKind.TOOL_RESULT).get(0);
        assertEquals("c1", result.toolResult().callId());
        assertEquals("written", result.toolResult().content(), "回填真实结果");
        assertFalse(result.toolResult().isError());
        assertEquals(1, result.turn(), "沿用原轮次号");

        Event end = eventsOfKind(session, EventKind.TURN_END).get(0);
        assertEquals(1, end.turn());
        assertEquals("completed", end.reason(), "续跑完成后正常收尾那一轮");
    }

    @Test
    void resumeWithoutHangingCallsKeepsTheTurnHanging() throws Exception {
        RecordingTool tool = new RecordingTool();
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{}")).build());
        session.append(Event.builder(EventKind.TOOL_RESULT).turn(1)
                .toolResult(new net.hegq.mos4j.session.ToolResult("c1", "done", false)).build());

        NothingToResumeException thrown = assertThrows(NothingToResumeException.class,
                () -> agent(tool, null).resume(session, ResumeOptions.latest()));

        assertTrue(thrown.getMessage().contains("悬空"), thrown.getMessage());
        assertEquals(1, session.interruptedTurn(), "那一轮保持挂起（没收尾）");
        assertTrue(eventsOfKind(session, EventKind.TURN_END).isEmpty(),
                "没有可续的不写 turn/end：收尾等于把「还欠一次调用」的事实抹掉");
    }

    @Test
    void resumeWithoutAnInterruptedTurnFails() throws Exception {
        Session session = new Session("s1", null);

        assertThrows(NothingToResumeException.class,
                () -> agent(new RecordingTool(), null).resume(session, ResumeOptions.latest()));
    }

    @Test
    void resumeOnlyRunsTheSelectedCallIds() throws Exception {
        RecordingTool tool = new RecordingTool();
        Session session = crashedSession("c1", "{\"a\":1}");
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c2", "write", "{\"b\":2}")).build());

        agent(tool, null).resume(session, ResumeOptions.latest().withCalls("c1"));

        assertEquals("{\"a\":1}", tool.received.get(), "只续指定的调用");
        assertEquals(1, eventsOfKind(session, EventKind.TOOL_RESULT).size());
        assertEquals("c1", eventsOfKind(session, EventKind.TOOL_RESULT).get(0).toolResult().callId());
        assertTrue(session.hangingCalls().stream().anyMatch(h -> h.callId().equals("c2")),
                "没被指定的那次调用仍然悬空");
    }

    @Test
    void resumeReusesThePriorApprovalInsteadOfAskingAgain() throws Exception {
        RecordingTool tool = new RecordingTool();
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel channel = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        GuardBus guards = new GuardBus().register(event -> GuardDecision.ask("要写文件"));
        Session session = crashedSession("c1", "{\"a\":1}");

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        Agent agent = Agent.of(Config.builder()
                .model(new AnsweringModel())
                .tools(registry)
                .guards(guards)
                .approvals(channel)
                .build());

        agent.resume(session, ResumeOptions.latest());

        assertEquals(0, asked.get(), "同一轮同一次调用已批过 → 不再问人");
        assertEquals("{\"a\":1}", tool.received.get(), "载荷原样重放");
        Event result = eventsOfKind(session, EventKind.TOOL_RESULT).get(0);
        assertFalse(result.toolResult().isError(), "被复用的批准放行执行");
        assertEquals("written", result.toolResult().content());

        List<Event> audit = eventsOfKind(session, EventKind.APPROVAL_ASKED);
        assertEquals(2, audit.size(), "复用也记一对 asked（新的审批身份独立）");
        assertTrue(audit.get(1).approval().reason().contains("ap_old"),
                "「这次没重新问人」的线索留在审计里：" + audit.get(1).approval().reason());
    }

    @Test
    void resumeWithoutAnApprovalChannelStillFailsClosed() throws Exception {
        RecordingTool tool = new RecordingTool();
        GuardBus guards = new GuardBus().register(event -> GuardDecision.ask("要写文件"));
        Session session = crashedSession("c1", "{\"a\":1}");

        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        Agent agent = Agent.of(Config.builder()
                .model(new AnsweringModel())
                .tools(registry)
                .guards(guards)
                .build());

        agent.resume(session, ResumeOptions.latest());

        Event result = eventsOfKind(session, EventKind.TOOL_RESULT).get(0);
        assertTrue(result.toolResult().isError(), "没有问人的能力 → fail-closed");
        assertTrue(result.toolResult().content().contains("未装配审批通道"),
                result.toolResult().content());
    }

    @Test
    void resumeAfterJsonlRestoreFindsTheHangingCalls() throws Exception {
        Path dir = Path.of("target", "test-tmp", "resume-" + System.nanoTime());
        Files.createDirectories(dir);
        JsonlPersistence persistence = new JsonlPersistence(dir);
        Session session = new Session("s1", persistence);
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.USER).turn(1).text("干活").build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{\"a\":1}")).build());

        // 重新打开：恢复出来的会话仍要认得出「那次调用还悬空」。
        Session restored = JsonlPersistence.restore("s1", persistence);
        assertEquals(1, restored.interruptedTurn(), "恢复后认得出被打断的轮次");
        assertEquals(1, restored.hangingCalls().size(), "恢复后认得出悬空调用");

        RecordingTool tool = new RecordingTool();
        agent(tool, null).resume(restored, ResumeOptions.latest());
        assertEquals("{\"a\":1}", tool.received.get(), "恢复后照常原样重放");
        assertEquals(1, eventsOfKind(restored, EventKind.TOOL_RESULT).get(0).turn());
    }

    @Test
    void aDriverThatDoesNotSupportResumeSaysSo() {
        LoopDriver driver = (rt, session, input) -> "ok";
        Session session = new Session("s1", null);

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> driver.resume(null, session, ResumeOptions.latest(), null));

        assertTrue(thrown.getMessage().contains("续跑"), thrown.getMessage());
    }

    @Test
    void functionToolRegistrationIsUnaffected() {
        // 载荷冻结要求参数逐字不变：FunctionTool 收到的就是日志里的原文。
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<String> received = new AtomicReference<>();
        registry.register(FunctionTool.of("write", "写", null, args -> {
            received.set(args);
            return "ok";
        }));
        registry.call("write", "{\"x\":1}");
        assertEquals("{\"x\":1}", received.get());
    }
}
