package net.hegq.mos4j.mcp;

import net.hegq.mos4j.mcp.internal.SdkMcpClientFactory;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面接线的守护测试（真 stdio 服务器）。
 */
class McpKitTest {

    private static McpServerConfig echoServer() {
        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        return new McpServerConfig("echo", McpTransport.STDIO, java,
                List.of("-cp", classpath, EchoMcpServer.class.getName()),
                Map.of(), null, null, Map.of(), McpExposure.DIRECT, Map.of(), false, null);
    }

    @Test
    void attachingPutsTheToolsIntoTheRegistry() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(echoServer()))) {
            assertTrue(registry.visibleNames().contains("mcp__echo__echo"));
            assertEquals(List.of("echo"), kit.snapshot().connectedServers());
        }
    }

    @Test
    void theInstructionsProviderIsReadyForTheConfig() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(echoServer()))) {
            String text = kit.instructions().get();
            assertTrue(text.contains("回声服务器"), "说明要能直接喂给 Config：\n" + text);
        }
    }

    @Test
    void theFactoryCanBeSwappedForTests() {
        ToolRegistry registry = new ToolRegistry();
        McpServerConfig config = McpServerConfig.stdio("x", "true", List.of())
                .withExposure(McpExposure.DIRECT);
        try (McpKit kit = McpKit.attach(registry, List.of(config), config1 -> new McpManagerTest.FakeClient(
                List.of(new McpToolInfo("t", "工具", Map.of("type", "object"))), "", ""))) {
            assertTrue(registry.visibleNames().contains("mcp__x__t"));
        }
    }

    @Test
    void theDefaultFactoryIsTheOfficialSdkOne() {
        assertTrue(new SdkMcpClientFactory() instanceof McpClientFactory);
    }
}
