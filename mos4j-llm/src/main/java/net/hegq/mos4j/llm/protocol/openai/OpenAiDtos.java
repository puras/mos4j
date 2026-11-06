package net.hegq.mos4j.llm.protocol.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的请求 DTO（<b>协议内私有</b>，与统一模型不同形）。
 *
 * <p><b>为什么必须独立 DTO</b>：协议形状是协议的真相，统一模型是对协议的<b>抽象</b>。
 * 两者混用就会出现「为了迁就某个协议而污染统一模型」——
 * 边界检查 C3 专门拦这件事（协议层不得与日志/事件共用一套 DTO）。</p>
 */
public final class OpenAiDtos {

    private OpenAiDtos() {
    }

    /** 聊天补全请求。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionRequest(
            @JsonProperty("model") String model,
            @JsonProperty("messages") List<MessageParam> messages,
            @JsonProperty("tools") List<ToolParam> tools,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            @JsonProperty("stream") Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions,
            @JsonProperty("modalities") List<String> modalities,
            @JsonProperty("reasoning_effort") String reasoningEffort,
            @JsonProperty("response_format") Object responseFormat) {
    }

    /** 流式选项（{@code include_usage} 让用量随流下发）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
    }

    /** 消息参数（content 可为字符串或内容块数组）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageParam(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content,
            @JsonProperty("tool_calls") List<ToolCallParam> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId,
            @JsonProperty("name") String name) {
    }

    /** 消息里的工具调用。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallParam(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") FunctionParam function) {
    }

    /** 函数名与参数（参数是 JSON 字符串，原样透传）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionParam(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }

    /** 工具定义。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolParam(
            @JsonProperty("type") String type,
            @JsonProperty("function") ToolFunction function) {
    }

    /** 工具的函数定义。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolFunction(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") Map<String, Object> parameters) {
    }

    /** 响应体（服务商加字段不炸——ignoreUnknown）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionResponse(
            @JsonProperty("choices") List<Choice> choices,
            @JsonProperty("usage") UsageDto usage,
            @JsonProperty("error") ErrorDto error) {
    }

    /** 候选。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            @JsonProperty("index") Integer index,
            @JsonProperty("message") MessageDto message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    /** 候选消息。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageDto(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("tool_calls") List<ToolCallDto> toolCalls) {
    }

    /** 响应里的工具调用。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDto(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("function") FunctionDto function) {
    }

    /** 响应里的函数（参数是 JSON 字符串）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDto(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments) {
    }

    /** 服务端错误体。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDto(
            @JsonProperty("message") String message,
            @JsonProperty("code") String code,
            @JsonProperty("type") String type) {
    }

    /** 用量（线格式）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageDto(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens,
            @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
            @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails) {
    }

    /** 输入侧明细（缓存命中）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptTokensDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
    }

    /** 输出侧明细（推理 token）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompletionTokensDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
    }
}
