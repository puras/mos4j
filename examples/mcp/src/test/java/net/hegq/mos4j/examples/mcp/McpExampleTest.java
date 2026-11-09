package net.hegq.mos4j.examples.mcp;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.mcp.McpExposure;
import net.hegq.mos4j.mcp.McpKit;
import net.hegq.mos4j.mcp.McpServerConfig;
import net.hegq.mos4j.mcp.McpSnapshot;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 示例的用例：真连一台 MCP 服务器（stdio 子进程），看工具进不进模型清单。
 */
class McpExampleTest {

    @Test
    @DisplayName("接上回声服务器：快照是 CONNECTED，工具按 mcp__<服务器>__<工具> 命名")
    void attachesToARealMcpServer() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(McpExample.echoServer()))) {
            McpSnapshot snapshot = kit.snapshot();

            assertEquals(1, snapshot.servers().size());
            assertEquals(McpSnapshot.Status.CONNECTED, snapshot.servers().get(0).status(),
                    "服务器应当连上: " + snapshot.servers().get(0).detail());
            assertEquals(1, snapshot.servers().get(0).direct(), "DIRECT 曝光的工具数");

            List<String> names = new ArrayList<>();
            for (var tool : registry.list()) {
                names.add(tool.name());
            }
            assertTrue(names.contains("mcp__echo__echo"), "工具按 Go 规则命名: " + names);
        }
    }

    @Test
    @DisplayName("曝光档：DEFERRED 的工具不进模型清单（要 tool_search 才激活）")
    void deferredToolsStayOutOfTheModelList() {
        ToolRegistry registry = new ToolRegistry();
        McpServerConfig deferred = McpExample.echoServer().withExposure(McpExposure.DEFERRED);
        try (McpKit kit = McpKit.attach(registry, List.of(deferred))) {
            assertEquals(1, kit.snapshot().servers().get(0).deferred(), "延迟曝光的工具数");
            assertEquals(0, kit.snapshot().servers().get(0).direct());
        }
    }

    @Test
    @DisplayName("模型看得见 DIRECT 的 MCP 工具，并能调它")
    void theModelCanCallTheMcpTool() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(McpExample.echoServer()))) {
            StubModel model = StubModel.of(
                    StubModel.toolCall("c1", "mcp__echo__echo", "{\"text\":\"hello\"}"),
                    StubModel.text("回声：hello"));

            var agent = McpExample.agent(registry, model, kit);
            String reply = agent.run(new net.hegq.mos4j.session.Session("mcp", null), "发一句 hello");

            assertEquals("回声：hello", reply);
            assertTrue(model.toolNamesOf(0).contains("mcp__echo__echo"),
                    "DIRECT 的工具进模型清单: " + model.toolNamesOf(0));
        }
    }
}
