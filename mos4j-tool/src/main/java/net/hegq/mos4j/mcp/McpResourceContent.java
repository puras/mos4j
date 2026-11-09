package net.hegq.mos4j.mcp;

/**
 * 读到的资源内容（对齐 Go 参考实现 {@code tool/mcp} 的内容投影）。
 *
 * <p><b>二进制不进模型</b>：模型读不了 base64。二进制内容只保留**长度与类型**，
 * 程序化调用方需要完整内容时自己走 MCP 协议读——与工具结果里的图像/资源降级同一条路。
 *
 * @param uri 资源 URI
 * @param text 文本内容（可空 = 二进制）
 * @param mimeType MIME 类型（可空）
 * @param blobBytes 二进制字节数（{@code 0} = 文本内容）
 */
public record McpResourceContent(String uri, String text, String mimeType, int blobBytes) {

    /** 是否是二进制内容。 */
    public boolean binary() {
        return blobBytes > 0;
    }

    /** 文本内容（可空）。 */
    public String text() {
        return text;
    }
}
