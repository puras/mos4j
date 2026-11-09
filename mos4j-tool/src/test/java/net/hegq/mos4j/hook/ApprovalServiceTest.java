package net.hegq.mos4j.hook;

import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批服务测试（T2.6）：四档结局、通道报错 ≠ 人拒绝、返回 null = 无人应答。
 */
class ApprovalServiceTest {

    private static ApprovalRequest request() {
        return new ApprovalRequest("ap_1", "shell", "call_1", "要执行危险命令");
    }

    @Test
    void approvedPermits() {
        ApprovalService service = new ApprovalService(req -> ApprovalResult.approved());
        ApprovalResult result = service.request(request());
        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        assertTrue(result.permits());
    }

    @Test
    void rejectedDoesNotPermit() {
        ApprovalService service = new ApprovalService(req -> ApprovalResult.rejected("不行"));
        ApprovalResult result = service.request(request());
        assertEquals(ApprovalOutcome.REJECTED, result.outcome());
        assertFalse(result.permits());
        assertEquals("不行", result.reason());
    }

    @Test
    void cancelledDoesNotPermit() {
        ApprovalService service = new ApprovalService(req -> ApprovalResult.cancelled("用户关掉了弹窗"));
        assertEquals(ApprovalOutcome.CANCELLED, service.request(request()).outcome());
    }

    @Test
    void channelFailureIsUnavailableNotRejected() {
        ApprovalService service = new ApprovalService(req -> {
            throw new IllegalStateException("IM 服务连不上");
        });

        ApprovalResult result = service.request(request());

        assertEquals(ApprovalOutcome.UNAVAILABLE, result.outcome(),
                "通道报错 ≠ 人拒绝");
        assertTrue(result.reason().contains("IM 服务连不上"), result.reason());
    }

    @Test
    void nullAnswerIsUnavailable() {
        ApprovalService service = new ApprovalService(req -> null);
        ApprovalResult result = service.request(request());
        assertEquals(ApprovalOutcome.UNAVAILABLE, result.outcome());
    }
}
