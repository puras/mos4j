package net.hegq.mos4j.llm.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * 用量的<b>手写</b>序列化器（照抄 Go 侧 {@code Usage.MarshalJSON} /
 * {@code UsageDetails.MarshalJSON} 的省略规则）。
 *
 * <p><b>为什么必须手写</b>：Go 的 {@code omitempty} 对 <b>int 0</b> 也省略，
 * 且对<b>结构体</b>无效（所以 Go 额外手写了 {@code MarshalJSON} 来省略空 details）。
 * Jackson 的 {@code NON_EMPTY} 对数字<b>不</b>省略 0，对 record <b>不</b>生效——
 * 直接用注解会得到「空 details 变成一串零值字段」「fresh_input_tokens=0 也出现」
 * 的日志/请求体差异。</p>
 *
 * <p><b>不这么做会出什么事</b>：日志里多出空键噪声；更糟的是——同一份用量在不同序列化器下
 * 键集合不同，下游按键消费时会读到「有键但值为 0」并当成真实数据。</p>
 */
public final class UsageJson {

    private UsageJson() {
    }

    /** 用量序列化器。 */
    @JsonSerialize(using = Serializer.class)
    public static final class Serializer extends ValueSerializer<Usage> {
        @Override
        public void serialize(Usage value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            gen.writeNumberProperty("prompt_tokens", value.promptTokens());
            if (value.freshInputTokens() > 0) {
                gen.writeNumberProperty("fresh_input_tokens", value.freshInputTokens());
            }
            gen.writeNumberProperty("completion_tokens", value.completionTokens());
            gen.writeNumberProperty("total_tokens", value.totalTokens());
            UsageDetails details = value.details();
            if (details != null && !details.isEmpty()) {
                gen.writeName("details");
                new DetailsSerializer().serialize(details, gen, ctxt);
            }
            gen.writeEndObject();
        }
    }

    /** 用量明细序列化器（省略空的 input/output）。 */
    @JsonSerialize(using = DetailsSerializer.class)
    public static final class DetailsSerializer extends ValueSerializer<UsageDetails> {
        @Override
        public void serialize(UsageDetails value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            InputTokenDetails input = value.input();
            if (input != null && !input.isEmpty()) {
                gen.writeName("input");
                gen.writeStartObject();
                if (input.cacheRead() != 0) {
                    gen.writeNumberProperty("cache_read", input.cacheRead());
                }
                if (input.cacheWrite() != 0) {
                    gen.writeNumberProperty("cache_write", input.cacheWrite());
                }
                if (input.audio() != 0) {
                    gen.writeNumberProperty("audio", input.audio());
                }
                gen.writeEndObject();
            }
            OutputTokenDetails output = value.output();
            if (output != null && !output.isEmpty()) {
                gen.writeName("output");
                gen.writeStartObject();
                if (output.reasoning() != 0) {
                    gen.writeNumberProperty("reasoning", output.reasoning());
                }
                if (output.audio() != 0) {
                    gen.writeNumberProperty("audio", output.audio());
                }
                gen.writeEndObject();
            }
            gen.writeEndObject();
        }
    }
}
