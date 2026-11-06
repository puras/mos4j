package net.hegq.mos4j.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 一次请求的 token 用量。
 *
 * <p><b>口径提醒（机制 M6）</b>：{@code promptTokens} 是<b>协议原始值</b>——
 * OpenAI/Gemini 的 prompt <b>含</b>缓存命中，Anthropic 的 input <b>不含</b>。
 * 跨协议比较一律用 {@link #freshInput()}（去缓存后的计费输入，各协议同口径）。</p>
 *
 * <p><b>不这么做会出什么事</b>：直接跨协议相加或比较缓存命中率，同样的百分比在两协议下
 * 含义完全不同（分母一个含缓存一个不含），成本结构被算错，缓存策略跟着错。</p>
 *
 * <p>JSON 字段名照抄 Go（snake_case）：{@code prompt_tokens}/{@code completion_tokens}/
 * {@code total_tokens} 无条件输出，{@code fresh_input_tokens} 为 0 时省略，
 * {@code details} 为空时省略（手写序列化器，见 {@code UsageJson}）。</p>
 *
 * @param promptTokens     协议原始输入 token（含不含缓存随协议而定）
 * @param freshInputTokens 去缓存后的计费输入（0 = 该协议未提供此口径）
 * @param completionTokens 输出 token
 * @param totalTokens      总 token（Anthropic 口径含缓存；OpenAI/Gemini 透传上游值）
 * @param details          明细（缓存命中/写入、推理 token 等）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSerialize(using = UsageJson.Serializer.class)
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("fresh_input_tokens") int freshInputTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        @JsonProperty("details") UsageDetails details) {

    /**
     * 去缓存后的输入 token（未提供该口径时退回 {@code promptTokens}）。
     *
     * <p>这是<b>跨协议唯一可比</b>的输入口径。</p>
     */
    public int freshInput() {
        return freshInputTokens > 0 ? freshInputTokens : promptTokens;
    }

    /**
     * 缓存命中率（自算指标，提供商不直接给）。
     *
     * <p><b>分母用去缓存口径的输入</b>：Anthropic 的 input_tokens 本就不含缓存命中，
     * OpenAI/Gemini 的 prompt 含——不统一口径，同一个百分比在两个协议下含义不同。
     * 缓存写入（cache_write）不算命中，不进分母。</p>
     */
    public double cacheHitRatio() {
        int read = details == null ? 0 : details.input().cacheRead();
        int den = freshInput() + read;
        if (den <= 0) {
            return 0d;
        }
        return (double) read / den;
    }

    /**
     * 不变式自检：分项之和是否等于总计。
     *
     * <p>各协议口径不同（Anthropic 的 total 含缓存创建与读取；OpenAI 的 total 含缓存命中），
     * 但「去缓存输入 + 缓存读 + 缓存写 + 输出 == 总计」这条在两边都成立——
     * 这是跨协议统一后的等式，可用于回归测试。</p>
     *
     * @return 不成立时返回差值（0 = 成立）
     */
    public int partsSumDelta() {
        int read = details == null ? 0 : details.input().cacheRead();
        int write = details == null ? 0 : details.input().cacheWrite();
        int sum = freshInput() + read + write + completionTokens;
        return sum - totalTokens;
    }
}
