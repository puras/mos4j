package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 事件类型（见事件目录）。
 *
 * <p><b>预算制</b>：新增一种事件前先删一种。事件类型是审计与投影的共同词汇——
 * 每多一种，下游（投影、审计、统计）都要多认一种。</p>
 */
public enum EventKind {

    /** 一轮开始。 */
    TURN_START("turn/start"),
    /** 一轮结束（reason: completed / terminated / error / interrupted）。 */
    TURN_END("turn/end"),
    /** 用户输入（投影为 user 消息）。 */
    USER("user"),
    /** 模型输出（投影为 assistant 消息）。 */
    ASSISTANT("assistant"),
    /** 工具调用留痕（含被守卫拦截的调用；不投影，仅审计）。 */
    TOOL_CALL("tool/call"),
    /** 工具结果（投影为 tool 消息）。 */
    TOOL_RESULT("tool/result"),
    /** 压缩落点：旧历史由摘要遮蔽。 */
    COMPACTION("compaction"),
    /** 问了人工（审批请求发出；不投影，仅审计）。 */
    APPROVAL_ASKED("approval/asked"),
    /** 人工答了（与 asked 用同一个 id 配对；不投影，仅审计）。 */
    APPROVAL_DECIDED("approval/decided"),
    /** 技能已加载（skill 工具成功返回正文；不投影，仅审计）。 */
    SKILL_ACTIVATE("skill/activate"),
    /** 技能声明的工具当前不可用（只诊断不收窄；不投影，仅审计）。 */
    SKILL_ALLOWED_TOOLS_MISMATCH("skill/allowed-tools-mismatch");

    private final String wire;

    EventKind(String wire) {
        this.wire = wire;
    }

    /** 线格式取值。 */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** 按线格式解析（未知值返回 {@code null}——读日志时不猜）。 */
    @JsonCreator
    public static EventKind fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (EventKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
