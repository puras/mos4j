package net.hegq.mos4j.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具适配：schema 直通、公开名、上游错误 → 工具失败。
 */
class McpToolAdapterTest {

    /** 假客户端（不连真服务器）。 */
    private static final class FakeClient implements McpClient {
        private final McpCallResult result;

        FakeClient(McpCallResult result) {
            this.result = result;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return List.of();
        }

        @Override
        public McpCallResult callTool(String name, String argumentsJson) {
            return result;
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

    @Test
    void theUpstreamSchemaIsPassedThroughUntouched() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("q", Map.of("type", "string")),
                "required", List.of("q"));
        McpToolInfo info = new McpToolInfo("search", "搜索", schema);

        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(new McpCallResult(List.of(), null, false)),
                "gh", info);

        assertEquals(schema, adapter.schema());
    }

    @Test
    void aMissingSchemaFallsBackToAnEmptyObject() {
        McpToolInfo info = new McpToolInfo("ping", "心跳", null);

        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(new McpCallResult(List.of(), null, false)),
                "gh", info);

        assertEquals(Map.of("type", "object"), adapter.schema());
    }

    @Test
    void thePublicNameIsNamespaced() {
        McpToolInfo info = new McpToolInfo("search", "搜索", Map.of("type", "object"));

        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(new McpCallResult(List.of(), null, false)),
                "gh", info);

        assertEquals("mcp__gh__search", adapter.name());
        assertEquals("search", adapter.upstreamName());
    }

    @Test
    void textContentIsProjectedForTheModel() throws Exception {
        McpCallResult result = new McpCallResult(
                List.of(McpContent.text("第一行"), McpContent.text("第二行")), null, false);
        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(result), "gh",
                new McpToolInfo("search", "搜索", Map.of("type", "object")));

        assertEquals("第一行\n第二行", adapter.execute("{}"));
    }

    @Test
    void nonTextContentBecomesAPlaceholder() throws Exception {
        McpCallResult result = new McpCallResult(
                List.of(McpContent.other("image", "{\"data\":\"...\"}")), null, false);
        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(result), "gh",
                new McpToolInfo("shot", "截图", Map.of("type", "object")));

        String text = (String) adapter.execute("{}");

        // 口径已收紧（2026-11-07，对齐 Go 的 project/opaquePart）：
        // 占位诊断按内容类型分开，不再是一句笼统的「只把文本入模」。
        assertTrue(text.contains("图像内容未进模型上下文"), text);
        assertTrue(text.contains("原始内容"), text);
        // 类型名仍在结构化占位里（程序化调用方取得到），但不进模型上下文。
        assertEquals("image", result.opaqueParts().get(0).type());
    }

    @Test
    void anUpstreamErrorBecomesAToolFailure() {
        McpCallResult result = new McpCallResult(
                List.of(McpContent.text("缺少参数 q")), null, true);
        McpToolAdapter adapter = McpToolAdapter.of(new FakeClient(result), "gh",
                new McpToolInfo("search", "搜索", Map.of("type", "object")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> adapter.execute("{}"));

        assertTrue(error.getMessage().contains("缺少参数 q"), error.getMessage());
    }
}
