package net.hegq.mos4j.mcp;

/**
 * 建立 MCP 客户端的工厂。
 *
 * <p>抽出来是为了让管理器能在测试里用替身（不连真服务器也能验编排语义），
 * 同时不把官方 SDK 的类型漏进对外契约。
 */
@FunctionalInterface
public interface McpClientFactory {

    /**
     * 建立一个连接。
     *
     * @param config 服务器配置
     * @return 客户端（调用方负责关闭）
     * @throws Exception 连接失败（管理器会记为该服务器失败，不影响其他服务器）
     */
    McpClient connect(McpServerConfig config) throws Exception;
}
