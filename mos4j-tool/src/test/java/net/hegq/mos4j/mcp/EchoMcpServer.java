package net.hegq.mos4j.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 测试用的最小 MCP 服务器（stdio 子进程）。
 *
 * <p>只有一个工具 {@code echo}：把传入的 {@code text} 原样回显。
 * 存在的意义是让 E2E 用例能连一个**真的** MCP 服务器——用替身验证的都是我们自己写的那一半。
 */
public final class EchoMcpServer {

    private EchoMcpServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);

        McpServer.sync(transport)
                .serverInfo("echo-server", "1.0.0")
                .instructions("回声服务器")
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

        // stdio 服务器靠 stdin 结束信号退出；这里一直等着，等宿主把子进程关掉。
        new CountDownLatch(1).await();
    }
}
