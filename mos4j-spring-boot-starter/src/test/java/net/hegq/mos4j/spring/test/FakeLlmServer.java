package net.hegq.mos4j.spring.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 假 LLM 服务（starter 模块测试专用；与 mos4j-llm / mos4j-sdk 的 FakeLlmServer 同一口径）。
 *
 * <p><b>为什么复制而不是共享</b>：llm 侧的 FakeLlmServer 是 test scope——
 * 依赖纪律禁止把测试基座当运行时依赖传递。测试基座跨模块复制是标准代价。
 */
public final class FakeLlmServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private volatile int statusCode = 200;
    private volatile String contentType = "application/json";
    private volatile String responseBody = "";

    public FakeLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
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

    /** 以 JSON 响应。 */
    public FakeLlmServer respondJson(String body) {
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

    @Override
    public void close() {
        server.stop(0);
    }
}
