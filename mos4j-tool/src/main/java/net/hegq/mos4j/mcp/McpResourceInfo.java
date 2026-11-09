package net.hegq.mos4j.mcp;

/**
 * 上游 MCP 资源（对齐 Go 参考实现 {@code tool/mcp.ResourceInfo}）。
 *
 * @param uri 资源 URI（读取时用它）
 * @param name 资源名（可空）
 * @param description 资源说明（可空）
 * @param mimeType MIME 类型（可空）
 * @param size 字节数（可空 = 上游没给）
 */
public record McpResourceInfo(String uri, String name, String description, String mimeType, Long size) {
}
