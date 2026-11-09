package net.hegq.mos4j.mcp;

/**
 * 一条服务器说明（连接建立时一次性注入给模型）。
 *
 * @param server 服务器名
 * @param text   服务器给的说明正文
 */
public record McpInstruction(String server, String text) {
}
