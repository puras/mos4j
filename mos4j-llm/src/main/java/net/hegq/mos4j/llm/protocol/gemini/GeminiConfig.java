package net.hegq.mos4j.llm.protocol.gemini;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemini 协议的连接配置。
 *
 * @param apiKey       鉴权密钥（Gemini 用 {@code key} 查询参数或 {@code x-goog-api-key} 头）
 * @param baseUrl      基地址（如 {@code https://generativelanguage.googleapis.com/v1beta}）
 * @param model        模型名
 * @param timeout      单次请求超时（可空 = 不设超时）
 * @param extraHeaders 附加请求头
 */
public record GeminiConfig(
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout,
        Map<String, String> extraHeaders) {

    /** 默认基地址。 */
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    /** 常用配置（无附加头）。 */
    public static GeminiConfig of(String apiKey, String baseUrl, String model) {
        return new GeminiConfig(apiKey, baseUrl, model, Duration.ofSeconds(120), Map.of());
    }

    /** 补齐默认值。 */
    public GeminiConfig normalized() {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new GeminiConfig(apiKey, base, model,
                timeout == null ? Duration.ofSeconds(120) : timeout,
                extraHeaders == null ? Map.of() : extraHeaders);
    }

    /** 流式端点（Gemini 的流式是 {@code :streamGenerateContent}）。 */
    public String streamUrl() {
        return normalized().baseUrl() + "/models/" + model + ":streamGenerateContent?alt=sse";
    }

    /** 非流式端点。 */
    public String generateUrl() {
        return normalized().baseUrl() + "/models/" + model + ":generateContent";
    }

    /** 请求头（{@code x-goog-api-key} + 附加头）。 */
    public Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-goog-api-key", apiKey);
        }
        headers.putAll(extraHeaders == null ? Map.of() : extraHeaders);
        return headers;
    }
}
