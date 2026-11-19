package net.hegq.mos4j.model.protocol.openai.chat;

import net.hegq.mos4j.model.wire.StreamTermination;

/**
 * OpenAI 兼容协议的流终止规则（机制 M5 的 OpenAI 侧）。
 *
 * <p><b>结束标记（原始层）</b>：{@code [DONE]} 哨兵与空 data 行——两者在兼容网关里都出现
 * （有的只发 {@code [DONE]}、不带 finish_reason，所以都是合法结束）。</p>
 *
 * <p><b>结束原因（事件层）</b>：{@code choices[0].finish_reason}。</p>
 *
 * <p><b>完整性</b>：两者<b>任一到即完整</b>；皆无 = 截断（半截文本 + 残缺工具调用
 * 不能当正常响应返回）。</p>
 */
public final class OpenAiStreamRules {

    private OpenAiStreamRules() {
    }

    /**
     * 原始层结束标记：{@code [DONE]} 哨兵或空 data 行。
     *
     * @param data 原始 data 文本（可空）
     */
    public static boolean isTerminalData(String data) {
        String payload = data == null ? "" : data.trim();
        return payload.isEmpty() || "[DONE]".equals(payload);
    }

    /** 终止规则（每条流一个实例）。 */
    public static StreamTermination<OpenAiStreamDtos.ChatCompletionChunk> termination() {
        return new StreamTermination<>() {
            private String finishReason;
            private boolean sawDone;

            @Override
            public boolean observeRaw(String data) {
                if (isTerminalData(data)) {
                    sawDone = true;
                    return true;
                }
                return false;
            }

            @Override
            public void observe(OpenAiStreamDtos.ChatCompletionChunk chunk) {
                if (chunk.choices() == null || chunk.choices().isEmpty()) {
                    return;
                }
                String reason = chunk.choices().get(0).finishReason();
                if (reason != null && !reason.isEmpty()) {
                    finishReason = reason;
                }
            }

            @Override
            public boolean isFinal() {
                // OpenAI 的停止发生在原始层（observeRaw 返回 true 即停），事件层无哨兵
                return false;
            }

            @Override
            public boolean streamIncomplete() {
                return finishReason == null && !sawDone;
            }
        };
    }
}
