package net.hegq.mos4j.llm.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Anthropic 流式事件 DTO（SSE {@code data:} 行的载荷）。
 */
final class AnthropicStreamDtos {

    private AnthropicStreamDtos() {
    }

    /** 流事件（type 区分；未知类型忽略但不炸）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StreamEvent(
            String type,
            Integer index,
            @JsonProperty("content_block") ContentBlockStart contentBlock,
            DeltaBody delta,
            MessageStart message,
            UsageSnapshot usage,
            ErrorPayload error) {

        /** content_block_start 的载荷（线上键是 content_block）。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ContentBlockStart(String type, String id, String name, Map<String, Object> input) {
        }

        /** content_block_delta / message_delta 的 delta 体。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record DeltaBody(
                String type,
                String text,
                String thinking,
                @JsonProperty("partial_json") String partialJson,
                @JsonProperty("stop_reason") String stopReason) {
        }

        /** message_start 携带的 message（role + usage）。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record MessageStart(String role, UsageSnapshot usage) {
        }

        /** message_delta / message_start 携带的增量 usage（两处形状相同）。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record UsageSnapshot(
                @JsonProperty("input_tokens") Integer inputTokens,
                @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
                @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
                @JsonProperty("output_tokens") Integer outputTokens) {
        }

        /** 错误事件载荷。 */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorPayload(String type, ErrorDetail error) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            record ErrorDetail(String type, String message) {
            }
        }
    }
}
