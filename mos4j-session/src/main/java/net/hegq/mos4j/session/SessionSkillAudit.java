package net.hegq.mos4j.session;

import net.hegq.mos4j.core.SkillAudit;

import java.io.IOException;
import java.util.List;

/**
 * 把技能事件写进会话日志（机制 M10：{@code skill/*} 事件全量落日志，不走预算制）。
 *
 * <p><b>站位同 {@link SessionApprovalAudit}</b>：审计接口在 {@code mos4j-core}（共享词汇表），
 * 技能能力在 {@code mos4j-tool}，日志在 {@code mos4j-session}——两边都只依赖 core，
 * 不用让 tool 反向依赖 session。
 *
 * <p><b>两类写入的失败后果不同</b>：</p>
 * <ul>
 *   <li>{@link #activate}：抛出去（调用方 fail-closed，正文不外泄）；</li>
 *   <li>{@link #allowedToolsMismatch}：也抛（接口不带「已处理」语义），但调用方
 *       <b>吞掉</b>——诊断记不上只损失可观测性，不损失安全性。</li>
 * </ul>
 *
 * <p><b>不进投影</b>：技能事件只进日志。投影是给模型看的历史，而技能正文已经在
 * {@code tool/result} 里了——再来一条「加载了 X」只会是噪声消息。
 */
public final class SessionSkillAudit implements SkillAudit {

    private final Session session;
    private final int turn;

    /**
     * @param session 会话
     * @param turn    轮次（写进事件的 turn 字段）
     */
    public SessionSkillAudit(Session session, int turn) {
        if (session == null) {
            throw new IllegalArgumentException("session: 技能审计的会话不能为 null");
        }
        this.session = session;
        this.turn = turn;
    }

    @Override
    public void activate(String name, String description, String dir) throws IOException {
        // 只记元数据（名字 / 描述 / 目录），不记正文——正文已经在 tool/result 里。
        session.append(Event.builder(EventKind.SKILL_ACTIVATE)
                .turn(turn)
                .skill(new SkillEvent(name, description, dir, null))
                .build());
    }

    @Override
    public void allowedToolsMismatch(String name, List<String> missingAllowed) throws IOException {
        session.append(Event.builder(EventKind.SKILL_ALLOWED_TOOLS_MISMATCH)
                .turn(turn)
                .skill(new SkillEvent(name, null, null, missingAllowed))
                .build());
    }
}