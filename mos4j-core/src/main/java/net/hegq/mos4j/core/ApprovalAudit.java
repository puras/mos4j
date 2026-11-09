package net.hegq.mos4j.core;

/**
 * 审批审计出口（机制 M10：审批事件<b>全量落日志</b>）。
 *
 * <p><b>为什么放在 core</b>：审批链在 {@code net.hegq.mos4j.hook} 包，审计事件在
 * {@code mos4j-session}。若接口放在任一侧，另一侧就得反向依赖它——
 * 依赖方向会被绕成环。core 是共享词汇表，两边都只依赖它（既有依赖边，不新增）。
 *
 * <p><b>写失败即中止</b>：审计是法律事实。写不进去说明事实没被记录，
 * 这时继续执行工具调用 = 做了没人能查的事。实现方在写失败时<b>必须抛异常</b>，
 * 调用方（execpath）把异常上抛，中止本轮。
 */
public interface ApprovalAudit {

    /** 记录「已询问」。 */
    void asked(ApprovalRequest request);

    /** 记录「已决定」。 */
    void decided(ApprovalRequest request, ApprovalOutcome outcome);
}
