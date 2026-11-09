package net.hegq.mos4j.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具结果的**投影口径**（对齐 Go 参考实现 {@code tool/mcp/client.go} 的 {@code project}）。
 *
 * <p>守护三件事：
 * <ul>
 *   <li>文本直接给模型；非文本给**按类型分开**的占位诊断（不是一句笼统的话）；</li>
 *   <li>原始 JSON 有上限（2048，对齐 Go 的 {@code maxOpaqueJSON}），且结构化占位可被程序化调用方取到；</li>
 *   <li>参数不是 JSON 对象、上游没给结果 → fail-closed，不当成成功。</li>
 * </ul>
 */
class McpResultProjectionTest {

    /** 假客户端（按名字返回预置结果）。 */
    private static final class StubClient implements McpClient {
        private final McpCallResult result;

        StubClient(McpCallResult result) {
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
            return "2025-06-18";
        }

        @Override
        public void close() {
        }
    }

    @Test
    void textGoesToTheModelAndNonTextGetsATypedNote() {
        McpCallResult result = new McpCallResult(List.of(
                McpContent.text("第一段"),
                McpContent.other("image", "{\"data\":\"AAA\"}"),
                McpContent.text("第二段"),
                McpContent.other("audio", "{\"data\":\"BBB\"}")), null, false);

        String text = result.toModelText();
        assertTrue(text.contains("第一段"), text);
        assertTrue(text.contains("第二段"), text);
        assertTrue(text.contains("图像内容未进模型上下文"), text);
        assertTrue(text.contains("音频内容未进模型上下文"), text);
        assertTrue(!text.contains("未知内容类型"), "已知类型不该落进未知分支: " + text);
    }

    @Test
    void resourceAndResourceLinkPointAtTheResourceTools() {
        McpCallResult result = new McpCallResult(List.of(
                McpContent.other("resource", "{\"uri\":\"file:///a\"}"),
                McpContent.other("resource_link", "{\"uri\":\"file:///b\"}")), null, false);

        String text = result.toModelText();
        assertTrue(text.contains("可用资源工具按 URI 读取"), text);
        assertTrue(text.contains("资源链接未展开"), text);
    }

    @Test
    void anUnknownTypeSaysSo() {
        McpCallResult result = new McpCallResult(List.of(McpContent.other("weird", "{}")), null, false);
        assertTrue(result.toModelText().contains("未知内容类型"), result.toModelText());
    }

    @Test
    void theOpaquePartsStayAvailableForProgrammaticCallers() {
        McpCallResult result = new McpCallResult(List.of(
                McpContent.text("hi"),
                McpContent.other("image", "{\"data\":\"AAA\"}")), null, false);

        List<McpContent> opaque = result.opaqueParts();
        assertEquals(1, opaque.size());
        assertEquals("image", opaque.get(0).type());
        assertTrue(opaque.get(0).json().contains("AAA"));
        assertEquals("hi", result.textContent());
    }

    @Test
    void theRawJsonIsTruncatedAtTheGoLimit() {
        String huge = "x".repeat(5000);
        McpCallResult result = new McpCallResult(List.of(McpContent.other("image", huge)), null, false);

        String text = result.toModelText();
        assertTrue(text.contains("已截断"), text);
        assertTrue(text.length() < huge.length(), "原始 JSON 应被截断");
    }

    @Test
    void argumentsThatAreNotAJsonObjectFailClosed() {
        McpToolAdapter tool = McpToolAdapter.of(new StubClient(
                        new McpCallResult(List.of(McpContent.text("ok")), null, false)),
                "a", new McpToolInfo("echo", "回显", null));

        McpError error = assertThrows(McpError.class, () -> tool.execute("[1,2,3]"));
        assertEquals(McpError.Kind.PROTOCOL, error.kind());
        assertTrue(error.getMessage().contains("不是 JSON 对象"), error.getMessage());
    }

    @Test
    void malformedArgumentsFailClosed() {
        McpToolAdapter tool = McpToolAdapter.of(new StubClient(
                        new McpCallResult(List.of(McpContent.text("ok")), null, false)),
                "a", new McpToolInfo("echo", "回显", null));

        McpError error = assertThrows(McpError.class, () -> tool.execute("{oops"));
        assertEquals(McpError.Kind.PROTOCOL, error.kind());
    }

    @Test
    void anUpstreamWithoutResultIsNotTreatedAsSuccess() {
        McpToolAdapter tool = McpToolAdapter.of(new StubClient(null),
                "a", new McpToolInfo("echo", "回显", null));

        McpError error = assertThrows(McpError.class, () -> tool.execute("{}"));
        assertEquals(McpError.Kind.PROTOCOL, error.kind());
        assertTrue(error.getMessage().contains("没有返回结果"), error.getMessage());
    }

    @Test
    void anUpstreamErrorBecomesAFailedToolCall() {
        McpToolAdapter tool = McpToolAdapter.of(new StubClient(
                        new McpCallResult(List.of(McpContent.text("上游说不行")), null, true)),
                "a", new McpToolInfo("echo", "回显", null));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> tool.execute("{}"));
        assertTrue(error.getMessage().contains("上游说不行"), error.getMessage());
    }
}
