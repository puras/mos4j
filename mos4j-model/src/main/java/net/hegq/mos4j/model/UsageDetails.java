package net.hegq.mos4j.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * token 用量的细分明细。
 *
 * <p>Go 用自定义 {@code MarshalJSON} 省略空的 input/output（{@code omitempty} 对结构体无效）；
 * Jackson 的 {@code NON_EMPTY} 对 record <b>不生效</b> → Java 侧同样手写序列化器
 * （{@code UsageJson}），保证「空 details 不出现、空 input/output 不出现」。</p>
 *
 * @param input  输入侧明细（缓存读/写、音频）
 * @param output 输出侧明细（推理、音频）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSerialize(using = UsageJson.DetailsSerializer.class)
public record UsageDetails(
        @JsonProperty("input") InputTokenDetails input,
        @JsonProperty("output") OutputTokenDetails output) {

    /** 空明细。 */
    public static UsageDetails empty() {
        return new UsageDetails(null, null);
    }

    /** 是否为空（两侧都无内容）。 */
    public boolean isEmpty() {
        return (input == null || input.isEmpty()) && (output == null || output.isEmpty());
    }
}
