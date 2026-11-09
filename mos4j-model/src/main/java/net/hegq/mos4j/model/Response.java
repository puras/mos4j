package net.hegq.mos4j.model;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;

/**
 * 一次对话响应（协议无关）。
 *
 * @param message      模型产出的消息（角色恒为 assistant）
 * @param usage        token 用量（可能为零值——某些网关不返回用量）
 * @param finishReason 停止原因（openai: stop/length/tool_calls；anthropic: end_turn/max_tokens/tool_use；
 *                      gemini: STOP/MAX_TOKENS/… 已转小写）。
 *                      <b>length/max_tokens 表示输出被截断</b>——循环层据此对未执行完的工具调用
 *                      做 fail-closed 处理（拒执行，而不是拿着残缺参数去调工具）
 */
public record Response(Message message, Usage usage, String finishReason) {

    /** 结束原因：正常结束。 */
    public static final String FINISH_END_TURN = "end_turn";
    /** 结束原因：输出被长度截断。 */
    public static final String FINISH_LENGTH = "length";
    /** 结束原因：模型请求调用工具。 */
    public static final String FINISH_TOOL_CALLS = "tool_calls";

    /**
     * 是否因长度被截断。
     *
     * <p>不这么做会出什么事：截断的助手消息里的工具参数是残缺的
     * （2026-10 事故：空参数的 write 进了执行通道）。</p>
     */
    public boolean truncatedByLength() {
        return FINISH_LENGTH.equals(finishReason) || FINISH_MAX_TOKENS.equals(finishReason);
    }

    /** 结束原因：达到输出上限（Anthropic 口径）。 */
    public static final String FINISH_MAX_TOKENS = "max_tokens";
}
