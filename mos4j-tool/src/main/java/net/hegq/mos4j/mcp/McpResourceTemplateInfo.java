package net.hegq.mos4j.mcp;

/**
 * 上游 MCP 资源模板（对齐 Go 参考实现 {@code tool/mcp.ResourceTemplateInfo}）。
 *
 * <p>模板是「带参数的 URI 模板」：按模板填参数得到资源 URI，再用读取工具取内容。
 *
 * @param uriTemplate URI 模板
 * @param name 模板名（可空）
 * @param description 说明（可空）
 * @param mimeType MIME 类型（可空）
 */
public record McpResourceTemplateInfo(String uriTemplate, String name, String description,
                                       String mimeType) {
}
