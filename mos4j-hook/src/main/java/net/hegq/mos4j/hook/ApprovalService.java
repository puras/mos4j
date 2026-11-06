package net.hegq.mos4j.hook;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;

/**
 * 审批服务：把 {@link Answerer} 包成 {@link ApprovalChannel}。
 *
 * <p><b>两个关键语义</b>：
 * <ul>
 *   <li><b>通道报错 ≠ 人拒绝</b>：回答者抛异常 → 归一到 {@code unavailable}
 *       并带原因，而不是当成「人拒绝了」。两者对用户说的话完全不同，
 *       混为一谈会让「通道挂了」看起来像「用户拒绝」，事后查不出真因；</li>
 *   <li><b>返回 null 也算 unavailable</b>：回答者没给出答复 = 没人应答 → fail-closed。</li>
 * </ul>
 */
public final class ApprovalService implements ApprovalChannel {

    private final Answerer answerer;

    /**
     * @param answerer 回答者（不返回 null）
     */
    public ApprovalService(Answerer answerer) {
        if (answerer == null) {
            throw new IllegalArgumentException("hook: 回答者不能为 null（未装配审批通道时不要构造本类）");
        }
        this.answerer = answerer;
    }

    @Override
    public ApprovalResult request(ApprovalRequest request) {
        try {
            ApprovalResult result = answerer.answer(request);
            if (result == null) {
                return ApprovalResult.unavailable("无人应答");
            }
            return result;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return ApprovalResult.unavailable("审批通道异常: " + message);
        }
    }
}
