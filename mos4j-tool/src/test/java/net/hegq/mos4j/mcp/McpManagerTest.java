package net.hegq.mos4j.mcp;

import net.hegq.mos4j.mcp.internal.SdkMcpClientFactory;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理器编排语义的守护测试。
 *
 * <p>用替身客户端验编排（换代、失败隔离、曝光分档、scope 隔离），
 * 再用一条真 stdio 服务器验「替身没骗人」。
 */
class McpManagerTest {

    /** 假客户端：按预设给工具。 */
    static final class FakeClient implements McpClient {
        private final List<McpToolInfo> tools;
        private final String instructions;
        private final String protocol;
        boolean closed;

        FakeClient(List<McpToolInfo> tools, String instructions, String protocol) {
            this.tools = tools;
            this.instructions = instructions;
            this.protocol = protocol;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return tools;
        }

        @Override
        public McpCallResult callTool(String name, String argumentsJson) {
            return new McpCallResult(List.of(McpContent.text("调用了 " + name)), null, false);
        }

        @Override
        public List<McpResourceInfo> listResources() {
            return List.of();
        }

        @Override
        public List<McpResourceTemplateInfo> listResourceTemplates() {
            return List.of();
        }

        @Override
        public List<McpResourceContent> readResource(String uri) {
            return List.of();
        }

        @Override
        public String instructions() {
            return instructions;
        }

        @Override
        public String protocolVersion() {
            return protocol;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 工厂：按服务器名给不同行为。 */
    static final class FakeFactory implements McpClientFactory {
        final Map<String, List<McpToolInfo>> tools = new LinkedHashMap<>();
        final Map<String, String> instructions = new LinkedHashMap<>();
        final List<String> failing = new ArrayList<>();
        final List<String> connected = new ArrayList<>();

        @Override
        public McpClient connect(McpServerConfig config) throws Exception {
            if (failing.contains(config.name())) {
                throw new IllegalStateException("连不上 " + config.name());
            }
            connected.add(config.name());
            List<McpToolInfo> list = tools.getOrDefault(config.name(), List.of());
            return new FakeClient(list, instructions.getOrDefault(config.name(), ""), "2026-07-28");
        }
    }

    private static McpToolInfo tool(String name) {
        return new McpToolInfo(name, "工具 " + name, Map.of("type", "object"));
    }

    private static McpServerConfig server(String name) {
        return McpServerConfig.stdio(name, "true", List.of()).withExposure(McpExposure.DIRECT);
    }

    @Test
    void toolsFromAConnectedServerReachTheRegistry() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("one")));

        McpManager manager = new McpManager(registry, factory);
        McpSnapshot snapshot = manager.start(List.of(server("a")));

        assertEquals(List.of("a"), snapshot.connectedServers());
        assertTrue(registry.visibleNames().contains("mcp__a__one"));
        assertEquals(1, snapshot.toolCount());
        manager.close();
    }

    @Test
    void deferredToolsStayOutOfTheListUntilActivated() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("big")));

        McpManager manager = new McpManager(registry, factory);
        manager.start(List.of(McpServerConfig.stdio("a", "true", List.of())
                .withExposure(McpExposure.DEFERRED)));

        assertFalse(registry.visibleNames().contains("mcp__a__big"), "deferred 档激活前不进清单");
        assertNotNull(registry.deferredCatalog().entry("mcp__a__big"), "但要能搜到");

        registry.deferredCatalog().activate(List.of("mcp__a__big"));
        assertTrue(registry.visibleNames().contains("mcp__a__big"));
        manager.close();
    }

    @Test
    void hiddenToolsAreInvisibleAndUnsearchableButStillCallable() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("secret")));

        McpManager manager = new McpManager(registry, factory);
        manager.start(List.of(McpServerConfig.stdio("a", "true", List.of())
                .withExposure(McpExposure.HIDDEN)));

        assertFalse(registry.visibleNames().contains("mcp__a__secret"), "hidden 档模型看不见");
        assertEquals(null, registry.deferredCatalog().entry("mcp__a__secret"), "hidden 档搜不到");
        assertEquals("调用了 secret", registry.call("mcp__a__secret", "{}"), "但宿主仍可程序化调用");
        assertEquals(Set.of("mcp__a__secret"), manager.hiddenTools());
        manager.close();
    }

    @Test
    void aFailingServerDoesNotStopTheOthers() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("good", List.of(tool("one")));
        factory.failing.add("bad");

        McpManager manager = new McpManager(registry, factory);
        McpSnapshot snapshot = manager.start(List.of(server("good"), server("bad")));

        assertEquals(List.of("good"), snapshot.connectedServers());
        assertEquals(List.of("bad"), snapshot.failedServers());
        assertTrue(registry.visibleNames().contains("mcp__good__one"), "好的那台照样装上");
        assertNotNull(snapshot.server("bad").detail());
        manager.close();
    }

    @Test
    void aFailedReloadKeepsThePreviousGeneration() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("one")));

        McpManager manager = new McpManager(registry, factory);
        manager.start(List.of(server("a")));
        assertTrue(registry.visibleNames().contains("mcp__a__one"));

        factory.failing.add("a");
        McpSnapshot snapshot = manager.reload("a");

        assertEquals(List.of("a"), snapshot.failedServers());
        assertTrue(registry.visibleNames().contains("mcp__a__one"), "换代失败保留上一代");
        manager.close();
    }

    @Test
    void aDisabledServerIsReportedAsSkippedAndContributesNoTools() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();

        McpManager manager = new McpManager(registry, factory);
        McpSnapshot snapshot = manager.start(List.of(server("a").asDisabled()));

        assertEquals(List.of("a"), snapshot.skippedServers());
        assertTrue(factory.connected.isEmpty(), "禁用 = 故意不连");
        assertEquals(0, snapshot.toolCount());
        assertTrue(snapshot.server("a").detail().contains("禁用"));
        manager.close();
    }

    @Test
    void serversAreIsolatedFromEachOtherByScope() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("one")));
        factory.tools.put("b", List.of(tool("two")));

        McpManager manager = new McpManager(registry, factory);
        manager.start(List.of(server("a"), server("b")));

        factory.tools.put("a", List.of(tool("fresh")));
        manager.reload("a");

        assertTrue(registry.visibleNames().contains("mcp__a__fresh"));
        assertFalse(registry.visibleNames().contains("mcp__a__one"));
        assertTrue(registry.visibleNames().contains("mcp__b__two"), "另一台不受影响");
        manager.close();
    }

    @Test
    void instructionsCarryTheirServerAndRespectTheBudget() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.instructions.put("a", "这是甲服务器的说明");

        McpManager manager = new McpManager(registry, factory);
        manager.start(List.of(server("a")));

        String text = manager.instructionsText();
        assertTrue(text.contains("a"), "说明要带服务器归属：\n" + text);
        assertTrue(text.contains("这是甲服务器的说明"));

        String tiny = manager.instructionsText(10);
        assertTrue(tiny.contains("超出预算"), "超预算要整段省略：\n" + tiny);
        assertFalse(tiny.contains("这是甲服务器的说明"));
        manager.close();
    }

    @Test
    void theSnapshotCountsEachExposureTier() {
        ToolRegistry registry = new ToolRegistry();
        FakeFactory factory = new FakeFactory();
        factory.tools.put("a", List.of(tool("d"), tool("f"), tool("h")));

        McpManager manager = new McpManager(registry, factory);
        McpServerConfig config = McpServerConfig.stdio("a", "true", List.of())
                .withExposure(McpExposure.DIRECT)
                .withToolExposure("f", McpExposure.DEFERRED)
                .withToolExposure("h", McpExposure.HIDDEN);
        McpSnapshot snapshot = manager.start(List.of(config));

        McpSnapshot.ServerStatus status = snapshot.server("a");
        assertEquals(1, status.direct());
        assertEquals(1, status.deferred());
        assertEquals(1, status.hidden());
        manager.close();
    }

    @Test
    void aRealStdioServerWorksThroughTheManager() {
        ToolRegistry registry = new ToolRegistry();
        String java = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        McpServerConfig config = new McpServerConfig("echo", McpTransport.STDIO, java,
                List.of("-cp", classpath, EchoMcpServer.class.getName()),
                Map.of(), null, null, Map.of(), McpExposure.DIRECT, Map.of(), false, null);

        McpManager manager = new McpManager(registry, new SdkMcpClientFactory());
        McpSnapshot snapshot = manager.start(List.of(config));

        assertEquals(List.of("echo"), snapshot.connectedServers());
        assertTrue(registry.visibleNames().contains("mcp__echo__echo"));
        assertEquals("hi", registry.call("mcp__echo__echo", "{\"text\":\"hi\"}"),
                "注册表→适配器→上游服务器 整条链路要通");
        assertTrue(manager.instructionsText().contains("回声服务器"));
        manager.close();
    }
}
