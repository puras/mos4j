package net.hegq.mos4j.session;

import net.hegq.mos4j.core.ApprovalAudit;
import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalRequest;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 把审批事件写进会话日志（机制 M10：审批事件<b>全量落日志</b>，不走预算制）。
 *
 * <p><b>为什么单独一个类</b>：审批链在 {@code mos4j-hook}，日志在
 * {@code mos4j-session}。审计接口放在 {@code mos4j-core}（共享词汇表），
 * 两边都只依赖 core，既不用让 session 反向依赖 hook，
 * 也不用让 hook 反向依赖 session。
 *
 * <p><b>写失败即中止</b>：审计是法律事实。写不进去还继续执行工具调用
 * = 做了没人能查的事。所以这里把写失败抛成 {@link UncheckedIOException}，
 * 由调用方（execpath → driver）中止本轮。
 *
 * <p><b>不进投影</b>：审批事件只进日志。投影是给模型看的历史，
 * 审批记录进对话历史会让模型看到一堆「问过人」但没有对应工具结果的噪声消息。
 */
public final class SessionApprovalAudit implements ApprovalAudit {

    private final Session session;
    private final int turn;

    /**
     * @param session 会话
     * @param turn    轮次（写进事件的 turn 字段）
     */
    public SessionApprovalAudit(Session session, int turn) {
        if (session == null) {
            throw new IllegalArgumentException("session: 审批审计的会话不能为 null");
        }
        this.session = session;
        this.turn = turn;
    }

    @Override
    public void asked(ApprovalRequest request) {
        append(EventKind.APPROVAL_ASKED, request, null);
    }

    @Override
    public void decided(ApprovalRequest request, ApprovalOutcome outcome) {
        // 契约（API.md）：reason 只在 asked 事件里出现，decided 不重复携带。
        append(EventKind.APPROVAL_DECIDED, request, outcome);
    }

    private void append(EventKind kind, ApprovalRequest request, ApprovalOutcome outcome) {
        Approval approval = new Approval(
                request.id(),
                request.toolName(),
                request.callId(),
                kind == EventKind.APPROVAL_ASKED ? request.reason() : null,
                outcome == null ? null : outcome.wire());
        try {
            session.append(Event.builder(kind)
                    .turn(turn)
                    .approval(approval)
                    .build());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "session: 审批审计写入失败（审计是法律事实，写不进去就不能继续执行）", e);
        }
    }
}
