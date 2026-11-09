package net.hegq.mos4j.model.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 假 LLM 服务（测试基座）：用 JDK 内置 {@code com.sun.net.httpserver} 起一个本地端点。
 *
 * <p><b>为什么不 mock HttpClient</b>：协议适配器的问题多半出在<b>字节层</b>
 * （SSE 分片、结束标记、换行、Content-Type）。mock 掉 HTTP 层等于把最容易出错的部分
 * 一起 mock 掉了——测完还是不知道真实网关能不能跑。</p>
 *
 * <p>用 JDK 自带的 HTTP 服务，不引入任何测试框架依赖（依赖纪律 I7）。</p>
 */
public final class FakeModelServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>("");
    private final Map<String, String> headerCapture = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private volatile int statusCode = 200;
    private volatile String contentType = "text/event-stream";
    private volatile String responseBody = "";
    private volatile Duration delay = Duration.ZERO;

    public FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 注册「/」兜底：Anthropic(/v1/messages)、Gemini(/v1beta/models/…) 等协议端点都能回放；
        // 保留「/v1/chat/completions」原 context（防以后精确路径抢占行为变化）
        server.createContext("/", exchange -> handle(exchange));
        server.createContext("/v1/chat/completions", exchange -> handle(exchange));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** 统一的回放逻辑（所有协议端点共用）。 */
    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        lastAuthHeader.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        headerCapture.clear();
        exchange.getRequestHeaders().forEach((name, values) ->
                headerCapture.put(String.valueOf(name).toLowerCase(java.util.Locale.ROOT),
                        values.isEmpty() ? "" : values.get(0)));
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            exchange.getResponseBody().write(payload);
        }
        exchange.close();
    }

    /** 基地址（形如 {@code http://127.0.0.1:PORT/v1}）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 以 SSE 响应。 */
    public FakeModelServer respondSse(String body) {
        this.contentType = "text/event-stream";
        this.responseBody = body;
        this.statusCode = 200;
        return this;
    }

    /** 以 JSON 响应。 */
    public FakeModelServer respondJson(String body) {
        this.contentType = "application/json";
        this.responseBody = body;
        this.statusCode = 200;
        return this;
    }

    /** 以指定状态码响应。 */
    public FakeModelServer respondStatus(int statusCode, String body) {
        this.contentType = "application/json";
        this.responseBody = body;
        this.statusCode = statusCode;
        return this;
    }

    /** 响应前先延迟（用于取消测试）。 */
    public FakeModelServer delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    /** 最近一次请求体。 */
    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    /** 最近一次请求的 Authorization 头。 */
    public String lastAuthHeader() {
        return lastAuthHeader.get();
    }

    /** 最近一次请求的任意请求头（小写名字；协议鉴权头不同：Authorization / x-api-key / x-goog-api-key）。 */
    public String lastHeader(String name) {
        return headerCapture.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 最近一次请求的路径（不含查询串；Gemini 端点带 /models/{model}:generateContent）。 */
    public String lastPath() {
        return lastPath.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
