package net.hegq.mos4j.llm;

import net.hegq.mos4j.llm.model.ToolCall;

/**
 * 流式输出的一个增量块。
 *
 * @param delta        增量文本
 * @param reasoning    推理内容增量（思维链）
 * @param toolCall     工具调用增量（协议按需给出；OpenAI 在流结束时才完整）
 * @param finishReason 停止原因（终止块携带；可能为空——空值合法，不代表截断）
 */
public record StreamChunk(String delta, String reasoning, ToolCall toolCall, String finishReason) {

    /** 是否为空块（无任何内容）。 */
    public boolean isEmpty() {
        return (delta == null || delta.isEmpty())
                && (reasoning == null || reasoning.isEmpty())
                && toolCall == null
                && (finishReason == null || finishReason.isEmpty());
    }
}
