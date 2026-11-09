package net.hegq.mos4j.session;

import net.hegq.mos4j.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话派生读（Go 差异 #4）：「日志即 checkpoint」——恢复所需的事实**已经在日志里**，
 * 这里只补**读**这一半。
 *
 * <p>对位 Go 的 {@code session/derive.go}（{@code PendingApprovals} /
 * {@code ApprovalOutcome} / {@code ApprovalDecision} / {@code InterruptedTurn} /
 * {@code HangingCalls}）。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>纯读</b>：不写日志、不改状态、不推进序号——派生视图随时可重算；</li>
 *   <li><b>不新增事件类型</b>：派生键全部用事件里已有的字段（turn + callId + 审批 id）；</li>
 *   <li><b>配对不串</b>：审批按 <b>id</b> 配对，悬空调用按 <b>callId</b> 配对——两种配对各走各的。</li>
 * </ul>
 */
class SessionDerivationTest {

    private static Approval asked(String id, String tool, String callId) {
        return new Approval(id, tool, callId, "要写文件", null);
    }

    private static Approval decided(String id, String tool, String callId, String outcome) {
        return new Approval(id, tool, callId, null, outcome);
    }

    @Test
    void pendingApprovalsListsAskedButNotDecided() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.APPROVAL_ASKED).turn(1)
                .approval(asked("a1", "write", "c1")).build());
        session.append(Event.builder(EventKind.APPROVAL_ASKED).turn(1)
                .approval(asked("a2", "shell", "c2")).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(decided("a1", "write", "c1", "approved")).build());

        List<Approval> pending = session.pendingApprovals();
        assertEquals(1, pending.size(), "只剩没答的那个");
        assertEquals("a2", pending.get(0).id());
        assertEquals("shell", pending.get(0).toolName());
    }

    @Test
    void approvalOutcomeIsLookedUpById() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.APPROVAL_ASKED).turn(1)
                .approval(asked("a1", "write", "c1")).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(decided("a1", "write", "c1", "rejected")).build());

        assertEquals("rejected", session.approvalOutcome("a1").orElseThrow());
        assertTrue(session.approvalOutcome("nope").isEmpty(), "没有这次决定");
        assertTrue(session.approvalOutcome("").isEmpty(), "空 id 不匹配");
    }

    @Test
    void approvalDecisionMatchesTurnAndCallIdAndTakesTheLastOne() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(decided("a1", "write", "c1", "approved")).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(decided("a2", "write", "c1", "rejected")).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(2)
                .approval(decided("a3", "write", "c1", "approved")).build());

        Approval decision = session.approvalDecision(1, "c1").orElseThrow();
        assertEquals("a2", decision.id(), "同轮同调用取最后一次决定");
        assertEquals("rejected", decision.outcome());

        assertTrue(session.approvalDecision(3, "c1").isEmpty(), "轮次不匹配");
        assertTrue(session.approvalDecision(1, "other").isEmpty(), "调用不匹配");
    }

    @Test
    void interruptedTurnReportsTheLastUnfinishedTurn() throws IOException {
        Session session = new Session("s1", null);
        assertEquals(0, session.interruptedTurn(), "没有轮次 = 没有被中断的轮次");

        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.TURN_END).turn(1).reason("completed").build());
        assertEquals(0, session.interruptedTurn(), "正常收尾不算被中断");

        session.append(Event.builder(EventKind.TURN_START).turn(2).build());
        assertEquals(2, session.interruptedTurn(), "开了没收尾 = 被中断");

        session.append(Event.builder(EventKind.TURN_END).turn(2).reason("interrupted").build());
        assertEquals(2, session.interruptedTurn(), "以 interrupted 收尾仍然算被中断");

        session.append(Event.builder(EventKind.TURN_START).turn(3).build());
        session.append(Event.builder(EventKind.TURN_END).turn(3).reason("completed").build());
        assertEquals(2, session.interruptedTurn(), "正常收尾的更晚轮次不改变结论");
    }

    @Test
    void hangingCallsAreCallsWithoutResults() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{\"a\":1}")).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c2", "shell", "{\"b\":2}")).build());
        session.append(Event.builder(EventKind.TOOL_RESULT).turn(1)
                .toolResult(new ToolResult("c2", "done", false)).build());

        List<HangingCall> hanging = session.hangingCalls();
        assertEquals(1, hanging.size(), "只有没回填的那个悬空");
        HangingCall call = hanging.get(0);
        assertEquals("c1", call.callId());
        assertEquals("write", call.toolName());
        assertEquals("{\"a\":1}", call.args());
        assertEquals(1, call.turn());
        assertTrue(call.seq() > 0, "带事件序号");
    }

    @Test
    void callsWithoutIdAreNotHanging() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("", "write", "{}")).build());

        assertTrue(session.hangingCalls().isEmpty(), "空 call id 无法配对，不算悬空");
    }

    @Test
    void aCallIdAnsweredOnceIsNotHangingAnyMore() throws IOException {
        // Go 同口径：配对按 call id 集合，不按出现次数——同一个 id 有过一次结果就算已回答。
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{}")).build());
        session.append(Event.builder(EventKind.TOOL_RESULT).turn(1)
                .toolResult(new ToolResult("c1", "done", false)).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(2)
                .toolCall(new ToolCall("c1", "write", "{}")).build());

        assertTrue(session.hangingCalls().isEmpty(), "同 id 已回填过就不再算悬空");
    }

    @Test
    void derivationDoesNotMutateTheLog() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{}")).build());
        int before = session.events().size();
        long seqBefore = session.events().get(before - 1).seq();

        session.hangingCalls();
        session.pendingApprovals();
        session.interruptedTurn();
        session.approvalDecision(1, "c1");

        assertEquals(before, session.events().size(), "派生读不写日志");
        assertEquals(seqBefore, session.events().get(before - 1).seq(), "派生读不推进序号");
        assertFalse(session.messages().isEmpty() && session.events().isEmpty(), "日志仍在");
    }
}
