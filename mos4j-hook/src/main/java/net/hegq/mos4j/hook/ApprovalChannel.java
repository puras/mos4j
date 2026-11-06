package net.hegq.mos4j.hook;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;

/**
 * 审批通道：把「要不要问人」变成「问到了什么」。
 *
 * <p><b>批准只对本次调用生效</b>（不缓存）：批准一次 ≠ 永久放行。
 * 缓存批准的机制会让「这次我确认过」变成「以后都算我确认过」——
 * 参数变了、目标文件变了，风险完全不一样。
 */
@FunctionalInterface
public interface ApprovalChannel {

    /**
     * 请求一次人工确认。
     *
     * @param request 请求
     * @return 答复（结局 + 原因）；不返回 null
     */
    ApprovalResult request(ApprovalRequest request);
}
