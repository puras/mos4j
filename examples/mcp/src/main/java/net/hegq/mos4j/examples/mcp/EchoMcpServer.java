package net.hegq.mos4j.examples.mcp;

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
 * 示例自带的最小 MCP 服务器（stdio 子进程）。
 *
 * <p>只有一个工具 {@code echo}：把传入的 {@code text} 原样回显。
 * 存在的意义是让这个示例**自足**：不装任何外部 MCP 服务器也能真连一次。
 * 接真实服务器时把 {@link McpExample} 里的配置换成 {@code npx ...} 之类即可。
 */
public final class EchoMcpServer {

    private EchoMcpServer() {
    }

    /**
     * 入口（stdio 服务器：等宿主把子进程关掉）。
     *
     * @param args 无用
     * @throws InterruptedException 等待被中断
     */
    public static void main(String[] args) throws InterruptedException {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper);

        McpServer.sync(transport)
                .serverInfo("echo-server", "1.0.0")
                .instructions("回声服务器：把文本原样送回")
                .tools(new McpServerFeatures.SyncToolSpecification(
                        McpSchema.Tool.builder()
                                .name("echo")
                                .description("回声：把 text 原样送回")
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

        new CountDownLatch(1).await();
    }
}
