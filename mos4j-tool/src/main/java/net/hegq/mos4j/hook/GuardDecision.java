package net.hegq.mos4j.hook;

/**
 * 守卫的判定结果。
 *
 * <p>四档而不是两档：
 * <ul>
 *   <li>{@link Verdict#ALLOW} 放行；</li>
 *   <li>{@link Verdict#ASK} 需要人工确认（交给审批通道；没装通道 = 拒绝，fail-closed）；</li>
 *   <li>{@link Verdict#DENY} 拒绝；</li>
 *   <li>{@link Verdict#CANCEL} 取消整轮（比拒绝更强：连「换个工具重试」都不给）。</li>
 * </ul>
 *
 * @param verdict 判定
 * @param reason  理由（拒绝/询问时非空，进文案）
 */
public record GuardDecision(Verdict verdict, String reason) {

    /** 判定档位。 */
    public enum Verdict {
        /** 放行。 */
        ALLOW,
        /** 需要人工确认。 */
        ASK,
        /** 拒绝。 */
        DENY,
        /** 取消整轮。 */
        CANCEL
    }

    public GuardDecision {
        verdict = verdict == null ? Verdict.ALLOW : verdict;
        reason = reason == null ? "" : reason;
    }

    /** 放行。 */
    public static GuardDecision allow() {
        return new GuardDecision(Verdict.ALLOW, "");
    }

    /** 拒绝。 */
    public static GuardDecision deny(String reason) {
        return new GuardDecision(Verdict.DENY, reason);
    }

    /** 需要人工确认。 */
    public static GuardDecision ask(String reason) {
        return new GuardDecision(Verdict.ASK, reason);
    }

    /** 取消整轮。 */
    public static GuardDecision cancel(String reason) {
        return new GuardDecision(Verdict.CANCEL, reason);
    }

    /** 是否允许执行。 */
    public boolean permits() {
        return verdict == Verdict.ALLOW;
    }
}
