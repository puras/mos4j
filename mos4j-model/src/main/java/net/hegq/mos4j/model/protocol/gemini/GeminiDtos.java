package net.hegq.mos4j.model.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Gemini 协议线格式 DTO。
 */
final class GeminiDtos {

    private GeminiDtos() {
    }

    /** 生成响应（流式 chunk 与非流式响应同形）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateContentResponse(
            List<Candidate> candidates,
            @JsonProperty("usageMetadata") UsageMetadata usageMetadata,
            @JsonProperty("promptFeedback") PromptFeedback promptFeedback) {
    }

    /** 候选（只取 [0]）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(Content content, @JsonProperty("finishReason") String finishReason) {
    }

    /** 内容（role + parts）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(String role, List<Part> parts) {
    }

    /** part（开放形状：text / functionCall / inlineData / functionData…）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(String text, FunctionCall functionCall, InlineData inlineData,
            FunctionResponse functionResponse) {

        /** 文本 part 工厂。 */
        public static Part ofText(String text) {
            return new Part(text, null, null, null);
        }

        /** 图片 part 工厂（inlineData base64）。 */
        public static Part ofImage(String mimeType, String base64Data) {
            return new Part(null, null, new InlineData(mimeType, base64Data), null);
        }

        /** 工具调用 part 工厂。 */
        public static Part ofFunctionCall(FunctionCall call) {
            return new Part(null, call, null, null);
        }

        /** 工具回执 part 工厂。 */
        public static Part ofFunctionResponse(String name, Map<String, Object> response) {
            return new Part(null, null, null, new FunctionResponse(name, response));
        }
    }

    /** inlineData（base64 媒体）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InlineData(String mimeType, String data) {
    }

    /** functionResponse（工具回执；response 是对象）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionResponse(String name, Map<String, Object> response) {
    }

    /** functionCall（args 已是完整 JSON 对象）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunctionCall(String id, String name, Map<String, Object> args) {
    }

    /** 用量（Gemini 口径：prompt 含缓存读）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(
            @JsonProperty("promptTokenCount") Integer promptTokenCount,
            @JsonProperty("candidatesTokenCount") Integer candidatesTokenCount,
            @JsonProperty("totalTokenCount") Integer totalTokenCount,
            @JsonProperty("cachedContentTokenCount") Integer cachedContentTokenCount,
            @JsonProperty("thoughtsTokenCount") Integer thoughtsTokenCount) {
    }

    /** 非流式空响应的提示（如安全拦截）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    // ---------------------------------------------------------------- 请求

    /** 请求体。 */
    record GenerateContentRequest(
            List<Content> contents,
            @JsonProperty("systemInstruction") Content systemInstruction,
            List<ToolDeclaration> tools,
            GenerationConfig generationConfig) {
    }

    /** 工具声明容器。 */
    record ToolDeclaration(List<FunctionDeclaration> functionDeclarations) {
    }

    /** 单个函数声明。 */
    record FunctionDeclaration(String name, String description, Map<String, Object> parameters) {
    }

    /** 生成配置。 */
    record GenerationConfig(Double temperature, @JsonProperty("maxOutputTokens") Integer maxOutputTokens) {
    }
}
