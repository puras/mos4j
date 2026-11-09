package net.hegq.mos4j.mcp;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 资源工具的守护测试。
 *
 * <p>守护的口径（对齐 Go 参考实现 {@code tool/mcp/resource.go}）：
 * 三个共享工具、工具面恒为 3、hidden 服务器的资源不泄露、二进制不进模型上下文。
 */
class McpResourceToolsTest {

    /** 带资源的假客户端。 */
    private static final class ResourceClient implements McpClient {

        private final List<McpResourceInfo> resources;
        private final List<McpResourceTemplateInfo> templates;
        private final List<McpResourceContent> contents;

        ResourceClient(List<McpResourceInfo> resources, List<McpResourceTemplateInfo> templates,
                       List<McpResourceContent> contents) {
            this.resources = resources;
            this.templates = templates;
            this.contents = contents;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return List.of();
        }

        @Override
        public McpCallResult callTool(String name, String argumentsJson) {
            return new McpCallResult(List.of(McpContent.text("")), null, false);
        }

        @Override
        public List<McpResourceInfo> listResources() {
            return resources;
        }

        @Override
        public List<McpResourceTemplateInfo> listResourceTemplates() {
            return templates;
        }

        @Override
        public List<McpResourceContent> readResource(String uri) {
            return contents;
        }

        @Override
        public String instructions() {
            return "";
        }

        @Override
        public String protocolVersion() {
            return "2026-07-28";
        }

        @Override
        public void close() {
        }
    }

    /** 工厂：按服务器名给不同客户端。 */
    private static final class Factory implements McpClientFactory {

        private final List<McpServerConfig> seen = new ArrayList<>();

        @Override
        public McpClient connect(McpServerConfig config) {
            seen.add(config);
            if ("rich".equals(config.name())) {
                return new ResourceClient(
                        List.of(new McpResourceInfo("file://a.txt", "a", "第一个", "text/plain", 12L)),
                        List.of(new McpResourceTemplateInfo("file://{id}", "t", "模板", "text/plain")),
                        List.of(new McpResourceContent("file://a.txt", "正文", "text/plain", 0)));
            }
            if ("binary".equals(config.name())) {
                return new ResourceClient(
                        List.of(new McpResourceInfo("file://b.bin", "b", null, "application/octet-stream", 8L)),
                        List.of(),
                        List.of(new McpResourceContent("file://b.bin", null, "application/octet-stream", 8)));
            }
            return new ResourceClient(List.of(), List.of(), List.of());
        }
    }

    @Test
    void theThreeSharedToolsAreRegisteredWhenAServerHasResources() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(McpServerConfig.http("rich", "http://localhost:1/").asDisabled()
                    .withExposure(McpExposure.DIRECT)));

            // disabled 服务器不连接 → 没有资源 → 不注册。
            assertFalse(registry.visibleNames().contains(McpResourceTools.LIST_NAME));
        } finally {
            manager.close();
        }
    }

    @Test
    void aServerWithResourcesGetsTheSharedTools() {
        ToolRegistry registry = new ToolRegistry();
        Factory factory = new Factory();
        McpManager manager = new McpManager(registry, factory);
        try {
            // disabled=false 才连接；这里用假工厂，连接必然成功。
            manager.start(List.of(new McpServerConfig("rich", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            List<String> names = registry.visibleNames();
            assertTrue(names.contains(McpResourceTools.LIST_NAME), names.toString());
            assertTrue(names.contains(McpResourceTools.TEMPLATES_NAME), names.toString());
            assertTrue(names.contains(McpResourceTools.READ_NAME), names.toString());
        } finally {
            manager.close();
        }
    }

    @Test
    void aServerWithoutResourcesGetsNoResourceTools() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("plain", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            assertFalse(registry.visibleNames().contains(McpResourceTools.LIST_NAME));
            assertFalse(manager.hasResourceServers());
        } finally {
            manager.close();
        }
    }

    @Test
    void theListToolReturnsTheResourcesSortedByUri() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("rich", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            Tool tool = registry.get(McpResourceTools.LIST_NAME).orElseThrow();
            String json = (String) tool.execute("{\"server\":\"rich\"}");

            assertTrue(json.contains("\"uri\":\"file://a.txt\""), json);
            assertTrue(json.contains("\"mimeType\":\"text/plain\""), json);
            assertTrue(json.contains("\"size\":12"), json);
        } finally {
            manager.close();
        }
    }

    @Test
    void theReadToolPutsTextIntoTheResult() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("rich", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            Tool tool = registry.get(McpResourceTools.READ_NAME).orElseThrow();
            String json = (String) tool.execute("{\"server\":\"rich\",\"uri\":\"file://a.txt\"}");

            assertTrue(json.contains("\"content\":\"正文\""), json);
        } finally {
            manager.close();
        }
    }

    @Test
    void binaryContentIsReducedToAPlaceholder() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("binary", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            Tool tool = registry.get(McpResourceTools.READ_NAME).orElseThrow();
            String json = (String) tool.execute("{\"server\":\"binary\",\"uri\":\"file://b.bin\"}");

            assertTrue(json.contains("\"bytes\":8"), json);
            assertTrue(json.contains("application/octet-stream"), json);
            assertFalse(json.contains("\"content\""), "二进制不进模型上下文：" + json);
        } finally {
            manager.close();
        }
    }

    @Test
    void anUnknownServerIsRejectedWithAReadableMessage() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("rich", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.DIRECT, java.util.Map.of(), false, null)));

            Tool tool = registry.get(McpResourceTools.LIST_NAME).orElseThrow();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> tool.execute("{\"server\":\"nope\"}"));

            assertTrue(error.getMessage().contains("nope"), error.getMessage());
            assertTrue(error.getMessage().contains("mcp_servers"), error.getMessage());
        } finally {
            manager.close();
        }
    }

    @Test
    void aHiddenServerDoesNotLeakItsResources() {
        ToolRegistry registry = new ToolRegistry();
        McpManager manager = new McpManager(registry, new Factory());
        try {
            manager.start(List.of(new McpServerConfig("rich", McpTransport.STREAMABLE_HTTP, null,
                    List.of(), java.util.Map.of(), null, "http://localhost:1/", java.util.Map.of(),
                    McpExposure.HIDDEN, java.util.Map.of(), false, null)));

            assertEquals(null, manager.resourceClient("rich"), "hidden 服务器的资源不泄露");
        } finally {
            manager.close();
        }
    }
}
