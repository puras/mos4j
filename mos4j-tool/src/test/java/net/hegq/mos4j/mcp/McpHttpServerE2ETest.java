package net.hegq.mos4j.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import net.hegq.mos4j.mcp.internal.SdkMcpClientFactory;
import net.hegq.mos4j.tool.ToolRegistry;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * streamable-http 的**真服务器** E2E（对齐 Go 参考实现的 {@code tool/mcp/http_test.go}）。
 *
 * <p>与已有认证用例的区别：那台是「真 HTTP + 假服务器」（只验到传输层）；
 * 这台是**官方 SDK 的 servlet 传输 + 嵌入式 Jetty**，协议整条链路都真的过一遍——
 * initialize、会话头、tools/list、tools/call。
 *
 * <p>Jetty 只在测试作用域（不进运行时依赖）。
 */
class McpHttpServerE2ETest {

    private Server server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint("/mcp")
                        .build();

        McpServer.sync(transport)
                .serverInfo("echo-http", "1.0.0")
                .instructions("回声 HTTP 服务器")
                .tools(new McpServerFeatures.SyncToolSpecification(
                        McpSchema.Tool.builder()
                                .name("echo")
                                .description("回声")
                                .inputSchema(Map.of("type", "object",
                                        "properties", Map.of("text", Map.of("type", "string"))))
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> arguments = request.arguments();
                            String text = arguments == null || arguments.get("text") == null
                                    ? "" : String.valueOf(arguments.get("text"));
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent(text)), false, null, null);
                        }))
                .build();

        server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), "/mcp");
        server.setHandler(context);
        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private String endpoint() {
        return "http://localhost:" + port + "/mcp";
    }

    @Test
    void theClientListsAndCallsAToolOnARealHttpServer() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("http", endpoint()).withExposure(McpExposure.DIRECT)),
                new SdkMcpClientFactory())) {

            List<String> visible = registry.visibleNames();
            assertTrue(visible.contains("mcp__http__echo"), "工具应出现在清单里: " + visible);

            Object out = registry.call("mcp__http__echo", "{\"text\":\"你好\"}");
            assertEquals("你好", String.valueOf(out), "上游回声应原样回来");
        }
    }

    @Test
    void theSnapshotReportsTheServerAsConnected() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("http", endpoint()).withExposure(McpExposure.DIRECT)),
                new SdkMcpClientFactory())) {

            McpSnapshot snapshot = kit.snapshot();
            McpSnapshot.ServerStatus status = snapshot.servers().stream()
                    .filter(s -> "http".equals(s.name()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(status, "快照里应有这台服务器: " + snapshot);
            assertEquals(McpSnapshot.Status.CONNECTED, status.status(), status.toString());
            assertEquals(1, status.direct(), status.toString());
        }
    }

    @Test
    void theServerInstructionsReachTheSession() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("http", endpoint()).withExposure(McpExposure.DIRECT)),
                new SdkMcpClientFactory())) {

            String instructions = kit.instructions().get();
            assertTrue(instructions.contains("回声 HTTP 服务器"),
                    "上游 instructions 应被采信: " + instructions);
        }
    }
}
