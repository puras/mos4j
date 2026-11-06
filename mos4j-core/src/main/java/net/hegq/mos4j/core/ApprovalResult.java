package net.hegq.mos4j.core;

/**
 * 审批通道的一次答复：结局 + 原因。
 *
 * <p>为什么要带原因：{@link ApprovalOutcome#UNAVAILABLE} 有两种成因
 * ——「没人应答」和「通道自己挂了」。它们都 fail-closed，但事后审计时
 * 必须能分清：前者是人不在，后者是系统坏了。只回一个枚举就分不清了。
 *
 * @param outcome 结局
 * @param reason  原因（拒绝/不可用时非空，进文案）
 */
public record ApprovalResult(ApprovalOutcome outcome, String reason) {

    public ApprovalResult {
        outcome = outcome == null ? ApprovalOutcome.UNAVAILABLE : outcome;
        reason = reason == null ? "" : reason;
    }

    /** 批准的答复。 */
    public static ApprovalResult approved() {
        return new ApprovalResult(ApprovalOutcome.APPROVED, "");
    }

    /** 人明确拒绝。 */
    public static ApprovalResult rejected(String reason) {
        return new ApprovalResult(ApprovalOutcome.REJECTED, reason);
    }

    /** 人取消。 */
    public static ApprovalResult cancelled(String reason) {
        return new ApprovalResult(ApprovalOutcome.CANCELLED, reason);
    }

    /** 无人应答 / 通道故障。 */
    public static ApprovalResult unavailable(String reason) {
        return new ApprovalResult(ApprovalOutcome.UNAVAILABLE, reason);
    }

    /** 是否允许执行。 */
    public boolean permits() {
        return outcome.permits();
    }
}
