package net.hegq.mos4j.execpath;

import net.hegq.mos4j.model.ToolCall;

/**
 * 一次工具调用的执行结果。
 *
 * @param call      这次调用对应的原始请求（含 CallID 与模型给定的参数）
 * @param content   回填给模型的文本：成功为工具返回值的 JSON，失败为失败原因
 * @param isError   是否是显式失败（工具报错、参数非法、工具未注册、被拒绝）
 * @param terminate 是否请求终止本轮
 */
public record Outcome(ToolCall call, String content, boolean isError, boolean terminate) {

    /** 成功结果。 */
    public static Outcome ok(ToolCall call, String content) {
        return new Outcome(call, content, false, false);
    }

    /** 失败结果。 */
    public static Outcome error(ToolCall call, String message) {
        return new Outcome(call, message, true, false);
    }
}
