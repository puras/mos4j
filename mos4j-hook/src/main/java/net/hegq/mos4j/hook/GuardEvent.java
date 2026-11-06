package net.hegq.mos4j.hook;

/**
 * 守卫看到的事件。
 *
 * <p><b>为什么与 {@link HookEvent} 是两个类型</b>：不变式 I5 要求「钩子无法绕过守卫」
 * 必须是<b>结构性</b>的——不是靠约定、不是靠 code review，而是靠类型。
 * 守卫与钩子共用一个事件类型，就意味着钩子可以构造一个「守卫事件」去喂守卫，
 * 于是「守卫看到的载荷」和「真正要执行的载荷」可能不是一回事——
 * 载荷冻结（机制 M1）当场失效，守卫只跑一次也不复检，漏判无人知晓。
 *
 * <p>两个类型各自独立，谁也构造不出对方的类型，绕不过去。
 *
 * @param toolName 工具名
 * @param args     参数 JSON 原文（原始文本，不解析——机制 M1 要求逐字比对）
 * @param callId   工具调用 id
 */
public record GuardEvent(String toolName, String args, String callId) {

    public GuardEvent {
        toolName = toolName == null ? "" : toolName;
        args = args == null ? "" : args;
        callId = callId == null ? "" : callId;
    }
}
