package net.hegq.mos4j.model.protocol.gemini.chat;

import net.hegq.mos4j.model.wire.StreamTermination;

/**
 * Gemini 协议的流终止规则（机制 M5 的 Gemini 侧）。
 *
 * <p><b>结束原因即结束标记</b>：{@code candidates[0].finishReason}——比 OpenAI/Anthropic 严：</p>
 * <ul>
 *   <li>{@code requireFinish=true}（缺省）：流读完仍无 finishReason = 截断（g5a）；</li>
 *   <li>{@code requireFinish=false}：读完全即完整（g5b 逃生阀，容忍上游网关丢 finishReason）。</li>
 * </ul>
 *
 * <p><b>无终止哨兵</b>：Gemini 不发「到此为止」的哨兵事件，读到 EOF 为止——
 * {@code usageMetadata} 常在 finishReason 之后的 data 行里到达（g1 语料），
 * 见到 finishReason 就停会丢用量。</p>
 */
public final class GeminiStreamRules {

    private GeminiStreamRules() {
    }

    /**
     * 终止规则（每条流一个实例）。
     *
     * @param requireFinish 是否要求 finishReason（GeminiChatModel 的逃生阀）
     */
    public static StreamTermination<GeminiDtos.GenerateContentResponse> termination(boolean requireFinish) {
        return new StreamTermination<>() {
            private boolean seenFinish;

            @Override
            public void observe(GeminiDtos.GenerateContentResponse chunk) {
                if (chunk.candidates() == null || chunk.candidates().isEmpty()) {
                    return;
                }
                String finish = chunk.candidates().get(0).finishReason();
                if (finish != null && !finish.isEmpty()) {
                    seenFinish = true;
                }
            }

            @Override
            public boolean isFinal() {
                // 无哨兵：finishReason 之后可能还有 usage chunk，读到 EOF 为止
                return false;
            }

            @Override
            public boolean streamIncomplete() {
                return requireFinish && !seenFinish;
            }
        };
    }
}
