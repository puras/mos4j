package net.hegq.mos4j.session;

import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalPrior;

import java.util.Optional;

/**
 * 从会话日志派生审批先验（先验复用的读侧）。
 *
 * <p><b>为什么放在会话包</b>：需要的两样事实都只在会话日志里——
 * {@code approval/decided} 带 {@code (turn, callId)}，{@code tool/call} 没有配对的
 * {@code tool/result} = 悬空调用。框架层不绑宿主的会话形态，所以这层胶水在会话侧
 * （框架只给出去匹配键 {@code (turn, toolName, callId)}）。
 *
 * <p><b>命中条件（四个缺一不可）</b>：
 * <ol>
 *   <li>同一轮、同一次调用上有过决定（取最后一次决定——最新的一次最贴近当下的意图）；</li>
 *   <li>工具名相同（工具名不同 = 不是同一件事）；</li>
 *   <li>结局名能解析（认不出来 = 没有决定，不当成拒绝也不当成批准）；</li>
 *   <li>那次调用仍悬空（已经有结果的调用不借决定——「批准一次」不能被放大成无限次）。</li>
 * </ol>
 *
 * <p><b>纯读</b>：不写日志、不改状态、不推进序号——派生视图随时可重算。
 */
public final class SessionApprovalPrior implements ApprovalPrior {

    private final Session session;

    /**
     * @param session 会话（不能为 null）
     */
    public SessionApprovalPrior(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session: 审批先验的会话不能为 null");
        }
        this.session = session;
    }

    @Override
    public Optional<Decision> lookup(int turn, String toolName, String callId) {
        if (callId == null || callId.isEmpty()) {
            // 空 call id 无法配对：不算命中（也不当成悬空）。
            return Optional.empty();
        }
        Optional<Approval> found = session.approvalDecision(turn, callId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Approval approval = found.get();
        if (toolName != null && !toolName.equals(approval.toolName())) {
            // 工具名不同 = 不是同一件事：模型的参数换了不能借旧批准。
            return Optional.empty();
        }
        Optional<ApprovalOutcome> outcome = ApprovalOutcome.fromWire(approval.outcome());
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        if (!stillHanging(turn, callId)) {
            // 已经跑完的调用不借决定：模型换个参数重发同一个 call id 时必须重新问人。
            return Optional.empty();
        }
        return Optional.of(new Decision(outcome.get(), approval.id()));
    }

    /** 那次调用是否仍悬空（声明了但没有结果）。 */
    private boolean stillHanging(int turn, String callId) {
        for (HangingCall hanging : session.hangingCalls()) {
            if (hanging.turn() == turn && hanging.callId().equals(callId)) {
                return true;
            }
        }
        return false;
    }
}
