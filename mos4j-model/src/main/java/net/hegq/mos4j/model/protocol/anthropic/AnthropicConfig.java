package net.hegq.mos4j.model.protocol.anthropic;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic 协议的连接配置。
 *
 * @param apiKey       鉴权密钥（Anthropic 用 {@code x-api-key} 头，不是 Bearer）
 * @param baseUrl      基地址（如 {@code https://api.anthropic.com/v1}）
 * @param model        模型名
 * @param version      协议版本（进 {@code anthropic-version} 头；空 = 缺省）
 * @param timeout      单次请求超时（可空 = 不设超时）
 * @param extraHeaders 附加请求头
 */
public record AnthropicConfig(
        String apiKey,
        String baseUrl,
        String model,
        String version,
        Duration timeout,
        Map<String, String> extraHeaders) {

    /** 默认基地址。 */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";

    /** 默认协议版本。 */
    public static final String DEFAULT_VERSION = "2023-06-01";

    /** 常用配置（无附加头）。 */
    public static AnthropicConfig of(String apiKey, String baseUrl, String model) {
        return new AnthropicConfig(apiKey, baseUrl, model, null,
                Duration.ofSeconds(120), Map.of());
    }

    /** 补齐默认值。 */
    public AnthropicConfig normalized() {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new AnthropicConfig(apiKey, base, model,
                version == null || version.isBlank() ? DEFAULT_VERSION : version,
                timeout == null ? Duration.ofSeconds(120) : timeout,
                extraHeaders == null ? Map.of() : extraHeaders);
    }

    /** 消息端点。 */
    public String messagesUrl() {
        return normalized().baseUrl() + "/messages";
    }

    /** 请求头（Anthropic 用 {@code x-api-key}；网关兼容 Bearer 时宿主用 extraHeaders 补）。 */
    public Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-api-key", apiKey);
        }
        headers.put("anthropic-version", normalized().version());
        headers.putAll(extraHeaders == null ? Map.of() : extraHeaders);
        return headers;
    }
}
