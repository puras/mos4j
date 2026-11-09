package net.hegq.mos4j.hook;

/**
 * 扩展点（钩子）看到的事件。
 *
 * <p><b>与 {@link GuardEvent} 分开的原因</b>见 {@link GuardEvent} 的说明：
 * 守卫的 API 里不出现钩子的类型，钩子的 API 里也不出现守卫的类型——
 * 「钩子无法绕过守卫」是类型层面的事实，不是约定。
 *
 * @param toolName 工具名
 * @param args     参数 JSON 原文（原始文本——载荷冻结要求逐字比对）
 * @param callId   工具调用 id
 */
public record HookEvent(String toolName, String args, String callId) {

    public HookEvent {
        toolName = toolName == null ? "" : toolName;
        args = args == null ? "" : args;
        callId = callId == null ? "" : callId;
    }

    /** 用另一个事件的载荷构造（钩子想「原样传下去」时用）。 */
    public static HookEvent of(HookEvent other) {
        return new HookEvent(other.toolName(), other.args(), other.callId());
    }
}
