package net.hegq.mos4j.mcp;

/**
 * MCP 传输方式。
 *
 * <p>对应 Go 参考实现的两种传输：{@code stdio}（起一个子进程当服务器）与
 * {@code streamable-http}（HTTP 端点）。
 */
public enum McpTransport {

    /** 本地子进程：命令 + 参数 + 环境变量 + 工作目录。 */
    STDIO,

    /** 远程 HTTP：streamable-http 端点（{@code url}）。 */
    STREAMABLE_HTTP
}
