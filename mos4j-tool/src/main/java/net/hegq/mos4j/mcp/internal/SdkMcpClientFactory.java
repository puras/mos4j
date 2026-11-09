package net.hegq.mos4j.mcp.internal;

import net.hegq.mos4j.mcp.McpClient;
import net.hegq.mos4j.mcp.McpClientFactory;
import net.hegq.mos4j.mcp.McpError;
import net.hegq.mos4j.mcp.McpServerConfig;

/**
 * 用官方 SDK 建连接的工厂。
 *
 * <p>放在 {@code internal} 包：官方 SDK 的类型不外泄。
 */
public final class SdkMcpClientFactory implements McpClientFactory {

    @Override
    public McpClient connect(McpServerConfig config) throws McpError {
        return SdkMcpClient.connect(config);
    }
}
