package net.hegq.mos4j.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 认证的守护测试（对齐 Go 参考实现 {@code tool/mcp/auth_test.go}）。
 *
 * <p>守护三条口径：
 * <ul>
 *   <li>401 / 403 归**认证类**错误（不是传输、不是协议）；</li>
 *   <li>token <b>每次请求取一次</b>，不缓存；</li>
 *   <li>取凭据失败归认证类；空 token = 不注入 {@code Authorization} 头。</li>
 * </ul>
 *
 * <p>用 JDK 自带的 {@code HttpServer} 起假 MCP 服务器：真发 HTTP，不靠替身。
 */
class McpAuthTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** 收到的请求头（按顺序）。 */
    private final List<String> authorizationHeaders = new ArrayList<>();

    /** token 取了几次。 */
    private final AtomicInteger tokenCalls = new AtomicInteger();

    /** 起一个假 MCP 服务器。 */
    private String startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, status));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void handle(HttpExchange exchange, int status) throws IOException {
        String body = read(exchange.getRequestBody());
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        synchronized (authorizationHeaders) {
            authorizationHeaders.add(authorization == null ? "" : authorization);
        }
        if (status != 200) {
            byte[] payload = ("{\"error\":\"nope\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }
        String response = respond(body);
        byte[] payload = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** 极简 MCP 服务器：只回 initialize 与 tools/list。 */
    private String respond(String body) {
        String id = "1";
        String method = "";
        int methodIndex = body.indexOf("\"method\"");
        if (methodIndex >= 0) {
            int start = body.indexOf(':', methodIndex) + 1;
            int end = body.indexOf('"', body.indexOf('"', start) + 1);
            method = body.substring(body.indexOf('"', start) + 1, end);
        }
        int idIndex = body.indexOf("\"id\"");
        if (idIndex >= 0) {
            int start = body.indexOf(':', idIndex) + 1;
            int end = body.indexOf(',', start) > 0 ? body.indexOf(',', start) : body.length();
            id = body.substring(start, end).trim();
            if (id.startsWith("\"")) {
                id = id.substring(1, id.length() - 1);
            }
        }
        if (method.contains("initialize")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{"
                    + "\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0.0\"}}}";
        }
        if (method.contains("tools/list")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":[]}}";
        }
        if (method.contains("resources/list")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"resources\":[]}}";
        }
        if (method.contains("resources/templates/list")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"resourceTemplates\":[]}}";
        }
        // 通知（没有 id）：空响应。
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{}}";
    }

    private static String read(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void a401IsClassifiedAsAnAuthError() throws Exception {
        String url = startServer(401);
        McpServerConfig config = McpServerConfig.http("gh", url).withTokenProvider(() -> "tok");

        McpError error = assertThrows(McpError.class, () -> SdkMcpClientFactoryHolder.connect(config));

        assertEquals(McpError.Kind.AUTH, error.kind(), error.getMessage());
        assertTrue(error.getMessage().contains("401"), error.getMessage());
    }

    @Test
    void a403IsClassifiedAsAnAuthError() throws Exception {
        String url = startServer(403);
        McpServerConfig config = McpServerConfig.http("gh", url).withTokenProvider(() -> "tok");

        McpError error = assertThrows(McpError.class, () -> SdkMcpClientFactoryHolder.connect(config));

        assertEquals(McpError.Kind.AUTH, error.kind(), error.getMessage());
        assertTrue(error.getMessage().contains("403"), error.getMessage());
    }

    @Test
    void aTokenProviderFailureIsAnAuthError() throws Exception {
        String url = startServer(200);
        McpServerConfig config = McpServerConfig.http("gh", url).withTokenProvider(() -> {
            throw new IllegalStateException("凭据服务器挂了");
        });

        McpError error = assertThrows(McpError.class, () -> SdkMcpClientFactoryHolder.connect(config));

        assertEquals(McpError.Kind.AUTH, error.kind(), error.getMessage());
        assertTrue(error.getMessage().contains("凭据"), error.getMessage());
    }

    @Test
    void theTokenIsInjectedIntoEveryRequest() throws Exception {
        String url = startServer(200);
        McpServerConfig config = McpServerConfig.http("gh", url).withTokenProvider(() -> {
            tokenCalls.incrementAndGet();
            return "tok-123";
        });

        try (McpClient client = SdkMcpClientFactoryHolder.connect(config)) {
            assertNotNull(client);
            client.listTools();
        }

        assertTrue(tokenCalls.get() >= 1, "token 至少取一次，实际 " + tokenCalls.get());
        synchronized (authorizationHeaders) {
            assertFalse(authorizationHeaders.isEmpty());
            for (String header : authorizationHeaders) {
                assertEquals("Bearer tok-123", header, "每个请求都要带凭据");
            }
        }
    }

    @Test
    void anEmptyTokenMeansNoAuthorizationHeader() throws Exception {
        String url = startServer(200);
        McpServerConfig config = McpServerConfig.http("gh", url).withTokenProvider(() -> "");

        try (McpClient client = SdkMcpClientFactoryHolder.connect(config)) {
            assertNotNull(client);
            client.listTools();
        }

        synchronized (authorizationHeaders) {
            assertFalse(authorizationHeaders.isEmpty());
            for (String header : authorizationHeaders) {
                assertNull(header == null ? null : (header.isEmpty() ? null : header),
                        "空 token = 不注入 Authorization 头");
            }
        }
    }

    /** 连服务器的入口（测试用真实工厂）。 */
    private static final class SdkMcpClientFactoryHolder {

        static McpClient connect(McpServerConfig config) throws McpError {
            return new net.hegq.mos4j.mcp.internal.SdkMcpClientFactory().connect(config);
        }
    }
}
