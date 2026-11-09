package net.hegq.mos4j.session;

import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalPrior;
import net.hegq.mos4j.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批先验（Go 差异 #3）：「同一轮内不重复问人」的读侧。
 *
 * <p>对位 Go 的 {@code prior.go}（{@code approvalPrior} / {@code isHangingCall}）。
 *
 * <p><b>四个条件缺一不可</b>：同轮、同一次调用、同一工具名、那次调用仍悬空。
 * 少一条就会把「一次批准」放大成「无限次批准」——这是这条机制的全部要害。
 */
class SessionApprovalPriorTest {

    /**
     * 一轮「批过 + 声明了调用」的现场。
     *
     * @param outcome    审批结局名
     * @param withResult 是否已经回填结果（true = 不再悬空）
     */
    private static Session scene(String id, String tool, String callId, String outcome, boolean withResult)
            throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.APPROVAL_ASKED).turn(1)
                .approval(new Approval(id, tool, callId, "要写文件", null)).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED).turn(1)
                .approval(new Approval(id, tool, callId, null, outcome)).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall(callId, tool, "{\"a\":1}")).build());
        if (withResult) {
            session.append(Event.builder(EventKind.TOOL_RESULT).turn(1)
                    .toolResult(new ToolResult(callId, "done", false)).build());
        }
        return session;
    }

    @Test
    void hitsOnSameTurnSameCallSameToolWhileStillHanging() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", false);

        Optional<ApprovalPrior.Decision> hit =
                new SessionApprovalPrior(session).lookup(1, "write", "c1");

        assertTrue(hit.isPresent(), "同轮同调用同工具 + 仍悬空 → 命中");
        assertEquals(ApprovalOutcome.APPROVED, hit.get().outcome());
        assertEquals("a1", hit.get().reusedFrom(), "指回被复用的那次决定");
        assertTrue(hit.get().reusable());
    }

    @Test
    void missesWhenTheTurnDiffers() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", false);

        assertTrue(new SessionApprovalPrior(session).lookup(2, "write", "c1").isEmpty(),
                "轮次不同 = 不是同一件事");
    }

    @Test
    void missesWhenTheCallIdDiffers() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", false);

        assertTrue(new SessionApprovalPrior(session).lookup(1, "write", "c2").isEmpty(),
                "调用不同 = 不是同一件事");
    }

    @Test
    void missesWhenTheToolNameDiffers() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", false);

        assertTrue(new SessionApprovalPrior(session).lookup(1, "shell", "c1").isEmpty(),
                "工具名不同 = 不是同一件事（参数换了不能借旧批准）");
    }

    @Test
    void missesWhenTheCallAlreadyHasAResult() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", true);

        assertTrue(new SessionApprovalPrior(session).lookup(1, "write", "c1").isEmpty(),
                "已经跑完的调用不借决定：一次批准不能被放大成无限次");
    }

    @Test
    void missesWhenTheOutcomeCannotBeParsed() throws IOException {
        Session session = scene("a1", "write", "c1", "看不懂", false);

        assertTrue(new SessionApprovalPrior(session).lookup(1, "write", "c1").isEmpty(),
                "认不出来的结局 = 没有决定（不当成拒绝，也不当成批准）");
    }

    @Test
    void onlyRealDecisionsAreReusable() throws IOException {
        Session rejected = scene("a1", "write", "c1", "rejected", false);
        ApprovalPrior.Decision rejectedHit =
                new SessionApprovalPrior(rejected).lookup(1, "write", "c1").orElseThrow();
        assertTrue(rejectedHit.reusable(), "拒绝也是人的决定，可复用");

        Session cancelled = scene("a1", "write", "c1", "cancelled", false);
        ApprovalPrior.Decision cancelledHit =
                new SessionApprovalPrior(cancelled).lookup(1, "write", "c1").orElseThrow();
        assertFalse(cancelledHit.reusable(), "撤回不是决定，不可复用");

        Session unavailable = scene("a1", "write", "c1", "unavailable", false);
        ApprovalPrior.Decision unavailableHit =
                new SessionApprovalPrior(unavailable).lookup(1, "write", "c1").orElseThrow();
        assertFalse(unavailableHit.reusable(), "无人应答不是决定，不可复用");
    }

    @Test
    void missesWhenThereIsNoDecisionAtAll() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "write", "{}")).build());

        assertTrue(new SessionApprovalPrior(session).lookup(1, "write", "c1").isEmpty(),
                "没有批过 = 没有先验");
    }

    @Test
    void missesOnEmptyCallId() throws IOException {
        Session session = scene("a1", "write", "c1", "approved", false);

        assertTrue(new SessionApprovalPrior(session).lookup(1, "write", "").isEmpty(),
                "空 call id 无法配对");
    }
}
