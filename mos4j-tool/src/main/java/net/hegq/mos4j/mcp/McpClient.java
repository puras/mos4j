package net.hegq.mos4j.mcp;

import java.util.List;

/**
 * 一个 MCP 服务器的客户端。
 *
 * <p><b>类型不外泄</b>：这是 MoS4J 自己的契约，官方 SDK 的类型（{@code McpSyncClient} 等）
 * 只允许出现在实现内部——宿主换 SDK 版本时不该被迫改自己的代码。
 *
 * <p>实现必须遵守的两条：
 * <ul>
 *   <li>连接失败 / 超时 → {@link McpError.Kind#TRANSPORT}；</li>
 *   <li>协议不合法 / 工具不存在 → {@link McpError.Kind#PROTOCOL}。</li>
 * </ul>
 */
public interface McpClient extends AutoCloseable {

    /** 列出上游工具（连接已建立时调用）。 */
    List<McpToolInfo> listTools() throws McpError;

    /**
     * 调用上游工具。
     *
     * @param name 上游工具名
     * @param argumentsJson 参数 JSON 原文（{@code "{}"} 表示无参）
     */
    McpCallResult callTool(String name, String argumentsJson) throws McpError;

    /** 列出上游资源（连接已建立时调用）。 */
    List<McpResourceInfo> listResources() throws McpError;

    /** 列出上游资源模板（连接已建立时调用）。 */
    List<McpResourceTemplateInfo> listResourceTemplates() throws McpError;

    /**
     * 读一个上游资源。
     *
     * @param uri 资源 URI
     * @return 内容列表（文本进模型；二进制只给长度）
     */
    List<McpResourceContent> readResource(String uri) throws McpError;

    /** 服务器说明（instructions）；没有则返回空串。 */
    String instructions();

    /** 协商到的协议版本（如 {@code 2026-07-28}）；未知返回空串。 */
    String protocolVersion();

    /** 关闭连接与子进程（幂等）。 */
    @Override
    void close();
}
