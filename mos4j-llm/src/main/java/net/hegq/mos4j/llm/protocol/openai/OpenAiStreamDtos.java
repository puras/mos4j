package net.hegq.mos4j.llm.protocol.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 兼容协议的<b>流式</b> DTO（协议内私有）。
 */
public final class OpenAiStreamDtos {

    private OpenAiStreamDtos() {
    }

    /** 流式块。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionChunk(
            @JsonProperty("object") String object,
            @JsonProperty("choices") List<ChunkChoice> choices,
            @JsonProperty("usage") OpenAiDtos.UsageDto usage) {
    }

    /** 流式候选。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkChoice(
            @JsonProperty("index") Integer index,
            @JsonProperty("delta") Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    /** 增量内容。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls) {
    }

    /** 工具调用增量（按 {@code index} 分片）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(
            @JsonProperty("index") Integer index,
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") FunctionDelta function) {
    }

    /** 函数增量（参数分片）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDelta(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }
}
