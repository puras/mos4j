package net.hegq.mos4j.llm.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Anthropic 协议线格式 DTO（与统一模型分离——协议形状不污染领域模型）。
 *
 * <p>字段名照抄 Anthropic Messages API（snake_case）；未知字段忽略
 * （服务商加字段不应炸掉适配器）。
 */
final class AnthropicDtos {

    private AnthropicDtos() {
    }

    /** 非流式响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageResponse(
            String id,
            String type,
            String role,
            List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            UsageDto usage,
            ErrorDto error) {
    }

    /** 内容块（非流式完整形态；tool_use 块的身份字段是 id，不是 tool_use_id）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(
            String type,
            String text,
            String thinking,
            String id,
            String name,
            Map<String, Object> input) {
    }

    /** 用量（Anthropic 口径：input 不含缓存命中）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageDto(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
            @JsonProperty("output_tokens") Integer outputTokens) {
    }

    /** 错误载荷。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorDto(String type, ErrorDetail error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorDetail(String type, String message) {
        }
    }

    // ---------------------------------------------------------------- 请求

    /** 消息参数。 */
    record MessageParam(String role, Object content) {

        static MessageParam of(net.hegq.mos4j.llm.model.Message message) {
            return new MessageParam(message.role().wire(), contentOf(message));
        }

        /** 文本内容直传字符串；工具回执用带 tool_use_id 的块数组；多模态展开成块。 */
        private static Object contentOf(net.hegq.mos4j.llm.model.Message message) {
            List<net.hegq.mos4j.llm.model.ContentPart> parts = message.contentParts();
            if (parts != null && !parts.isEmpty()) {
                List<Map<String, Object>> blocks = new java.util.ArrayList<>();
                for (net.hegq.mos4j.llm.model.ContentPart part : parts) {
                    if ("text".equals(part.type())) {
                        blocks.add(Map.of("type", "text",
                                "text", part.text() == null ? "" : part.text()));
                    } else if ("image".equals(part.type())) {
                        Map<String, Object> source = new java.util.LinkedHashMap<>();
                        if (part.url() != null) {
                            source.put("type", "url");
                            source.put("url", part.url());
                        } else {
                            source.put("type", "base64");
                            source.put("media_type", part.mimeType() == null ? "image/png" : part.mimeType());
                            source.put("data", part.data() == null ? "" : part.data());
                        }
                        blocks.add(Map.of("type", "image", "source", source));
                    } else {
                        blocks.add(Map.of("type", String.valueOf(part.type())));
                    }
                }
                return blocks;
            }
            if (message.role() == net.hegq.mos4j.llm.model.Role.TOOL
                    || (message.toolCallId() != null && !message.toolCallId().isEmpty())) {
                // 工具回执：tool_result 块（user 消息携带）
                Map<String, Object> block = new java.util.LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", message.toolCallId());
                block.put("content", message.content() == null ? "" : message.content());
                return List.of(block);
            }
            return message.content() == null ? "" : message.content();
        }
    }

    /** 工具定义。 */
    record ToolParam(String name, String description, Map<String, Object> input_schema) {

        static ToolParam of(net.hegq.mos4j.llm.model.Tool tool) {
            return new ToolParam(tool.name(), tool.description(), tool.parameters());
        }
    }

    /** 请求体。 */
    record MessageRequest(
            String model,
            Integer max_tokens,
            List<MessageParam> messages,
            Object system,
            List<ToolParam> tools,
            Double temperature,
            Boolean stream,
            ThinkingParam thinking) {
    }

    /** thinking 配置（启用时 max_tokens 必须大于 budget_tokens）。 */
    record ThinkingParam(String type, Integer budget_tokens) {
    }
}
