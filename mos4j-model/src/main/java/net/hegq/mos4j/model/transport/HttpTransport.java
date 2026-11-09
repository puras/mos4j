package net.hegq.mos4j.model.transport;

import net.hegq.mos4j.core.CancellationToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 传输层（共享 envelope：鉴权头、超时、状态码穿透）。
 *
 * <p><b>为什么单独一层</b>：三个协议的请求信封一样（POST JSON + Bearer 鉴权 +
 * 同样的错误处理），差别只在 body 与解析。把信封抽出来，
 * 协议适配器只关心「怎么把统一模型映射成线格式」和「怎么把线格式映射回来」。</p>
 *
 * <p><b>取消语义</b>：取消令牌触发时中断正在进行的 HTTP 调用
 * （JDK 25 的 HttpClient 支持 {@code sendAsync} + 取消；这里用同步 send +
 * 令牌回调取消 Future 的等价路径）。</p>
 */
public final class HttpTransport {

    private final HttpClient client;
    private final Duration requestTimeout;

    /**
     * @param client         JDK HttpClient（宿主可注入自定义实例以复用连接池）
     * @param requestTimeout 单次请求超时（可空 = 不设超时，靠取消令牌）
     */
    public HttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    /** 默认传输层（连接超时 10s，请求超时由调用方给）。 */
    public HttpTransport(Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), requestTimeout);
    }

    /**
     * 发送一次请求。
     *
     * @param url      目标地址
     * @param body     请求体（JSON）
     * @param headers  附加请求头（鉴权等）
     * @param stream   是否为流式响应（决定返回流还是整包）
     * @param token    取消令牌
     * @return 响应（流式时 body 是流，调用方负责关闭）
     */
    public HttpResponseBody send(String url, String body, Map<String, String> headers, boolean stream,
                                  CancellationToken token) throws IOException, InterruptedException {
        token.throwIfCancelled();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        headers.forEach(builder::header);
        if (requestTimeout != null) {
            builder.timeout(requestTimeout);
        }

        HttpRequest request = builder.build();
        // 取消：令牌触发 → 取消底层 HTTP 交换（Future.cancel 会中止 JDK HttpClient 的请求）。
        // 这是“真取消”：不是只置一个标志，而是真的断开 HTTP 交互。
        CompletableFuture<HttpResponse<java.io.InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        var registration = token.onCancel(() -> future.cancel(true));
        try {
            HttpResponse<java.io.InputStream> response = future.get();
            int code = response.statusCode();
            java.io.InputStream in = response.body();
            if (code < 200 || code >= 300) {
                // 口径：所有非 2xx 都是失败（201/204 之类的 2xx 也算成功）
                try (in) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    throw new TransportStatusException(code, text);
                }
            }
            return new HttpResponseBody(code, in);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            // JDK HttpClient 在取消时以 IOException("Request cancelled") 完成 future，
            // 不会抛 CancellationException——这里把「取消」重新归类，
            // 否则上层分不清「被取消」和「网络故障」（前者不该重试）。
            if (token.isCancelled() || cause instanceof java.util.concurrent.CancellationException) {
                throw new java.util.concurrent.CancellationException("HTTP 请求已取消");
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("HTTP 调用失败", cause);
        } finally {
            registration.close();
        }
    }

    /** 响应体（非 2xx 时抛 {@link TransportStatusException}）。 */
    public record HttpResponseBody(int statusCode, java.io.InputStream body) {
    }

    /** 非 2xx 响应（携带状态码与原始体，由协议层翻译成 {@code StatusError}）。 */
    public static final class TransportStatusException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int statusCode;
        private final String body;

        public TransportStatusException(int statusCode, String body) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() {
            return statusCode;
        }

        public String body() {
            return body;
        }
    }
}
