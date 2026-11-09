package net.hegq.mos4j.mcp;

/**
 * MCP 工具返回的一段内容。
 *
 * @param type 内容类型（{@code text} / {@code image} / {@code audio} / {@code resource} /
 *             {@code resource_link} / 上游自定义类型）
 * @param text 文本内容（{@code type=text} 时有值，否则为空）
 * @param json 非文本内容的原始 JSON（截断后）——给模型看占位诊断，程序化调用方用完整内容
 */
public record McpContent(String type, String text, String json) {

    /** 构造文本内容。 */
    public static McpContent text(String text) {
        return new McpContent("text", text == null ? "" : text, null);
    }

    /** 构造非文本内容（带原始 JSON 摘要）。 */
    public static McpContent other(String type, String json) {
        return new McpContent(type == null ? "unknown" : type, null, json);
    }

    /** 是否是纯文本。 */
    public boolean isText() {
        return "text".equals(type);
    }
}
