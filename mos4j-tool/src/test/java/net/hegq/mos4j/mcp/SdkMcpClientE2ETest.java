package net.hegq.mos4j.mcp;

import net.hegq.mos4j.mcp.internal.SdkMcpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真 stdio 子进程 E2E：官方 SDK 能不能真的连上、列工具、调工具。
 *
 * <p>这类用例的价值在于**不能被替身替代**：协议层（握手、版本协商、schema 传输）一旦对不上，
 * 替身测试全绿而真实服务器连不上。所以这里起一个真的子进程当 MCP 服务器。
 */
class SdkMcpClientE2ETest {

    private static McpServerConfig echoServerConfig() {
        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        return new McpServerConfig("echo", McpTransport.STDIO, java,
                List.of("-cp", classpath, EchoMcpServer.class.getName()),
                Map.of(), null, null, Map.of(), McpExposure.DEFERRED, Map.of(), false, null);
    }

    @Test
    void listsTheToolsOfARealServer() throws Exception {
        try (McpClient client = SdkMcpClient.connect(echoServerConfig())) {
            List<McpToolInfo> tools = client.listTools();

            assertEquals(1, tools.size(), "真实服务器应当只有一个工具");
            assertEquals("echo", tools.get(0).name());
            assertEquals("回声", tools.get(0).description());
        }
    }

    @Test
    void callsAToolOnARealServer() throws Exception {
        try (McpClient client = SdkMcpClient.connect(echoServerConfig())) {
            McpCallResult result = client.callTool("echo", "{\"text\":\"你好\"}");

            assertFalse(result.isError());
            assertEquals("你好", result.toModelText());
        }
    }

    @Test
    void theServerInstructionsAndProtocolVersionAreVisible() throws Exception {
        try (McpClient client = SdkMcpClient.connect(echoServerConfig())) {
            assertEquals("回声服务器", client.instructions());
            assertFalse(client.protocolVersion().isEmpty(), "应当协商到协议版本");
        }
    }

    @Test
    void theAdaptedToolCallsThroughToTheRealServer() throws Exception {
        try (McpClient client = SdkMcpClient.connect(echoServerConfig())) {
            McpToolInfo info = client.listTools().get(0);
            McpToolAdapter adapter = McpToolAdapter.of(client, "echo", info);

            assertEquals("mcp__echo__echo", adapter.name());
            assertEquals("你好", adapter.execute("{\"text\":\"你好\"}"));
            assertTrue(adapter.schema().containsKey("type"), "schema 应当原样透传");
        }
    }
}
