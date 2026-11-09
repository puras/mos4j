package net.hegq.mos4j.mcp;

/**
 * MCP 访问凭据的来源（对齐 Go 参考实现 {@code tool/mcp.TokenProvider}）。
 *
 * <p><b>契约（边界清晰，不越界）</b>：
 * <ul>
 *   <li>框架<b>每次请求取一次</b>，<b>不缓存、不刷新</b>——凭据生命周期归宿主
 *       （宿主决定什么时候刷新、怎么存、怎么过期）。框架拿到 token 只往请求头一放，不留副本；</li>
 *   <li>返回<b>空串</b> = 不注入 {@code Authorization} 头（这台服务器不需要凭据，
 *       或宿主想让它自己 401）；</li>
 *   <li>抛错 = 该请求失败，归<b>认证类</b>错误（连接期表现为「这台服务器不可用：取凭据失败」）；</li>
 *   <li>只对 <b>streamable-http</b> 生效：stdio 是本地子进程，不走 HTTP。</li>
 * </ul>
 *
 * <p><b>为什么框架不做认证流程</b>：完整 OAuth（发现 + 动态注册 + 回调服务器）会把框架
 * 绑到外部系统与浏览器交互上。框架只提供「取凭据」的缝，认证流程归宿主。
 */
@FunctionalInterface
public interface McpTokenProvider {

    /**
     * 取当前可用的访问凭据。
     *
     * @return Bearer token（空串 = 不注入请求头）
     * @throws McpError 取凭据失败（认证类）
     */
    String token() throws McpError;
}
