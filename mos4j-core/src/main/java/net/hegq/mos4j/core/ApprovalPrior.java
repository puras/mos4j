package net.hegq.mos4j.core;

import java.util.Optional;

/**
 * 审批先验查询：「同一轮内不重复问人」的读侧。
 *
 * <p><b>为什么存在</b>：进程在工具执行期间被硬杀时，那次调用已经有人批过了——
 * 宿主恢复会话后续跑它，如果再问一遍，人得重新点一次批准，而这一次批准
 * 对应的参数可能都已经变了。先验查询把「这次调用的批准还在不在」变成
 * <b>从会话日志派生</b>的读操作，不新建 checkpoint 文件、不引入第二套状态机。
 *
 * <p><b>命中条件（四个缺一不可）</b>：
 * <ol>
 *   <li><b>同一轮</b>：轮次号变了就不是同一件事（审批决定按 {@code (turn, callId)} 匹配）；</li>
 *   <li><b>同一次调用</b>：按 call id 匹配，不按审批 id（每次请求都会签发新 id）；</li>
 *   <li><b>同一工具名</b>：工具名不同 = 不是同一件事；</li>
 *   <li><b>那次调用仍悬空</b>：已经有 {@code tool/result} 的调用不借决定——
 *       否则「批准一次」会被放大成无限次（模型换个参数重发同一个 call id 时必须重新问人）。</li>
 * </ol>
 *
 * <p><b>只有真决定可复用</b>：批准与拒绝是人的决定；撤回（{@code cancelled}）与
 * 无人应答（{@code unavailable}）不是——复用它们等于把「没人答」当成「批过了」。
 *
 * <p><b>实现方在会话侧</b>：框架不绑宿主的会话形态，具体怎么从日志派生由实现方决定
 * （框架侧只给出去 {@code (turn, toolName, callId)} 这把匹配键）。
 */
public interface ApprovalPrior {

    /**
     * 查同一轮、同一次调用上已有的审批决定。
     *
     * @param turn     轮次号
     * @param toolName 本次要执行的工具名
     * @param callId   本次要执行的调用 id
     * @return 命中的先验决定；没有命中（或不满足复用条件）时为空
     */
    Optional<Decision> lookup(int turn, String toolName, String callId);

    /**
     * 一次可复用的先验决定。
     *
     * @param outcome    被复用的结局（只有批准 / 拒绝可复用）
     * @param reusedFrom 被复用的那次审批 id（审计线索：看得出「这次没重新问人」）
     */
    record Decision(ApprovalOutcome outcome, String reusedFrom) {

        public Decision {
            outcome = outcome == null ? ApprovalOutcome.UNAVAILABLE : outcome;
            reusedFrom = reusedFrom == null ? "" : reusedFrom;
        }

        /** 这个决定能不能被复用（只有真决定可以）。 */
        public boolean reusable() {
            return outcome.reusable();
        }
    }
}
