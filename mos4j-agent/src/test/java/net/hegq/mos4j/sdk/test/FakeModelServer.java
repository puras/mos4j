package net.hegq.mos4j.sdk.test;

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
 * 假 LLM 服务（sdk 模块测试专用；与 mos4j-model 的 support.FakeModelServer 同一口径）。
 *
 * <p><b>为什么复制而不是共享</b>：llm 侧的 FakeModelServer 是 test scope——
 * 依赖纪律禁止把测试基座当运行时依赖传递。测试基座跨模块复制是标准代价。</p>
 */
public final class FakeModelServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final Map<String, String> headerCapture = new ConcurrentHashMap<>();
    private volatile int statusCode = 200;
    private volatile String contentType = "text/event-stream";
    private volatile String responseBody = "";
    private volatile Duration delay = Duration.ZERO;

    public FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
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

    /** 基地址（形如 {@code http://127.0.0.1:PORT}）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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

    /** 最近一次请求体。 */
    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    /** 最近一次请求路径。 */
    public String lastPath() {
        return lastPath.get();
    }

    /** 最近一次请求头（小写名字）。 */
    public String lastHeader(String name) {
        return headerCapture.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
