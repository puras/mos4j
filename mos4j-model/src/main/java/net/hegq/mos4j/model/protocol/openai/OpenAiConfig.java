package net.hegq.mos4j.model.protocol.openai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容协议的连接配置。
 *
 * @param apiKey       鉴权密钥（可空——本地 Ollama 等无鉴权端点）
 * @param baseUrl      基地址（如 {@code https://api.openai.com/v1}）
 * @param model        模型名
 * @param timeout      单次请求超时（可空 = 不设超时）
 * @param extraHeaders 附加请求头（如网关要求的租户头）
 */
public record OpenAiConfig(
        String apiKey,
        String baseUrl,
        String model,
        Duration timeout,
        Map<String, String> extraHeaders) {

    /** 默认基地址。 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** 常用配置（无附加头）。 */
    public static OpenAiConfig of(String apiKey, String baseUrl, String model) {
        return new OpenAiConfig(apiKey, baseUrl, model, Duration.ofSeconds(120), Map.of());
    }

    /** 补齐默认值：基地址为空时用默认，超时为空时用 120s。 */
    public OpenAiConfig normalized() {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new OpenAiConfig(apiKey, base, model,
                timeout == null ? Duration.ofSeconds(120) : timeout,
                extraHeaders == null ? Map.of() : extraHeaders);
    }

    /** 聊天补全端点。 */
    public String chatCompletionsUrl() {
        return normalized().baseUrl() + "/chat/completions";
    }

    /** 请求头（鉴权 + 附加头）。 */
    public Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.putAll(extraHeaders == null ? Map.of() : extraHeaders);
        return headers;
    }
}
