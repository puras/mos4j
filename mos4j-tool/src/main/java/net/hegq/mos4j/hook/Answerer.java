package net.hegq.mos4j.hook;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;

/**
 * 回答者：真正「问人」的那一端（终端弹窗、IM 机器人、网页审批…）。
 *
 * <p>与 {@link ApprovalChannel} 分开：通道是<b>机制</b>（怎么把问题送出去、
 * 怎么把答复收回来），回答者是<b>人机界面</b>。分开后，宿主可以替换界面
 * 而不碰执行语义；测试可以直接塞一个假回答者。
 */
@FunctionalInterface
public interface Answerer {

    /**
     * 把问题交给人，等答复。
     *
     * @param request 请求
     * @return 答复（结局 + 原因）；返回 null 视为无人应答
     */
    ApprovalResult answer(ApprovalRequest request);
}
