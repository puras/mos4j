package net.hegq.mos4j.llm.model;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量的 JSON 口径测试（照抄 Go 侧 {@code Usage.MarshalJSON} 的省略规则）。
 *
 * <p><b>为什么单独测</b>：省略规则错了不会立刻报错——日志里多一个 {@code "fresh_input_tokens":0}
 * 没人会发现，但下游按「键存在即有值」消费时会拿到假数据。</p>
 */
class UsageJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void freshInputOmittedWhenZero() throws Exception {
        Usage usage = new Usage(10, 0, 3, 13, null);

        String json = MAPPER.writeValueAsString(usage);

        assertFalse(json.contains("fresh_input_tokens"), "fresh_input_tokens=0 必须省略：" + json);
        assertTrue(json.contains("\"prompt_tokens\":10"));
        assertTrue(json.contains("\"completion_tokens\":3"));
        assertTrue(json.contains("\"total_tokens\":13"));
    }

    @Test
    void emptyDetailsOmitted() throws Exception {
        Usage usage = new Usage(10, 10, 3, 13, UsageDetails.empty());

        String json = MAPPER.writeValueAsString(usage);

        assertFalse(json.contains("details"), "空 details 必须省略：" + json);
    }

    @Test
    void detailsKeepOnlyNonZeroFields() throws Exception {
        Usage usage = new Usage(100, 60, 10, 110,
                new UsageDetails(new InputTokenDetails(40, 0, 0), new OutputTokenDetails(0, 0)));

        String json = MAPPER.writeValueAsString(usage);

        assertTrue(json.contains("\"cache_read\":40"), json);
        assertFalse(json.contains("cache_write"), "零值字段必须省略：" + json);
        assertFalse(json.contains("\"output\""), "空的 output 必须省略：" + json);
        assertTrue(json.contains("\"fresh_input_tokens\":60"), json);
    }

    @Test
    void cacheHitRatioUsesUnifiedDenominator() {
        // OpenAI 口径：prompt 含缓存命中 → 分母 = 去缓存输入 + 缓存命中 = prompt
        Usage openai = new Usage(100, 60, 10, 110,
                new UsageDetails(new InputTokenDetails(40, 0, 0), null));
        assertEquals(0.4d, openai.cacheHitRatio(), 1e-9);

        // Anthropic 口径：input 不含缓存命中 → 分母 = input + 缓存命中
        Usage anthropic = new Usage(60, 60, 10, 110,
                new UsageDetails(new InputTokenDetails(40, 0, 0), null));
        assertEquals(0.4d, anthropic.cacheHitRatio(), 1e-9);
    }
}
