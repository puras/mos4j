package net.hegq.mos4j.mcp;

import net.hegq.mos4j.mcp.internal.SdkMcpClientFactory;
import net.hegq.mos4j.tool.ToolRegistry;

import java.util.List;
import java.util.function.Supplier;

/**
 * MCP 接入的门面：把「连服务器 → 工具进注册表 → 说明每会话注入一次」收成几步。
 *
 * <p><b>模块归属</b>：MCP 接入已从原按需模块 {@code mos4j-mcp} 并入 {@code mos4j-tool}（2026-11-08 的六模块合并）。
 * 所以它现在是默认 reactor 的一部分，宿主不用额外开关就能用。
 *
 * <p><b>但官方 MCP SDK 是 optional 依赖</b>：{@code mos4j-tool} 只声明
 * {@code io.modelcontextprotocol.sdk:mcp-core} 与 {@code mcp-json-jackson3} 为 {@code optional=true}，
 * 不传递给下游宿主。理由是官方 SDK 硬依赖 slf4j-api / reactor / json-schema-validator——
 * 不用 MCP 的宿主不该被这些传递依赖污染。因此：
 * <ul>
 *   <li>要用 MCP 的宿主必须自己显式声明官方 SDK 坐标；</li>
 *   <li>不用 MCP 的宿主完全不受影响（optional 依赖不传递）。</li>
 * </ul>
 * 宿主自己 {@code attach} 一次即可。
 *
 * <p>典型用法：
 * <pre>{@code
 * McpKit kit = McpKit.attach(registry, List.of(McpServerConfig.stio("fs", "mcp-fs", List.of())));
 * Config config = Config.builder()
 *         .model(model)
 *         .tools(registry)
 *         .mcpInstructions(kit.instructions())
 *         .build();
 * }</pre>
 */
public final class McpKit implements AutoCloseable {

    private final McpManager manager;

    private McpKit(McpManager manager) {
        this.manager = manager;
    }

    /**
     * 接上 MCP 服务器（用官方 SDK 建连接）。
     *
     * @param registry 工具注册表
     * @param configs  服务器配置
     * @return 门面（宿主负责关闭）
     */
    public static McpKit attach(ToolRegistry registry, List<McpServerConfig> configs) {
        return attach(registry, configs, new SdkMcpClientFactory());
    }

    /**
     * 接上 MCP 服务器（自带工厂，便于测试或换实现）。
     *
     * @param registry 工具注册表
     * @param configs  服务器配置
     * @param factory  客户端工厂
     * @return 门面（宿主负责关闭）
     */
    public static McpKit attach(ToolRegistry registry, List<McpServerConfig> configs,
                                McpClientFactory factory) {
        McpManager manager = new McpManager(registry, factory);
        manager.start(configs);
        return new McpKit(manager);
    }

    /** 装配快照（装了什么、哪台失败）。 */
    public McpSnapshot snapshot() {
        return manager.snapshot();
    }

    /**
     * 服务器说明的提供器（喂给 {@code Config.mcpInstructions(...)}）。
     *
     * <p>每次会话取一次当时的说明：连接可能掉线重连，说明可能变——
     * 装配期就拍死会失真。
     *
     * @return 提供器
     */
    public Supplier<String> instructions() {
        return manager::instructionsText;
    }

    /**
     * {@code mcp_servers} 分节文本（进系统提示词）。
     *
     * <p>模型要知道有哪些服务器、能调什么工具——不然它只看到一堆
     * {@code mcp__<server>__<tool>} 名字，却不知道那是哪台服务器。
     *
     * <p>用 {@code PromptSections.insert(prompt, "mcp_servers", text)} 插入，
     * 位置由中心顺序决定（技能清单之后、工具指引之前）。
     *
     * @return 分节文本（无服务器时返回空串）
     */
    public String serversSection() {
        McpSnapshot snapshot = manager.snapshot();
        if (snapshot.servers().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("可用 MCP 服务器（工具名形如 mcp__<服务器>__<工具>）：\n");
        for (McpSnapshot.ServerStatus server : snapshot.servers()) {
            out.append("- ").append(server.name())
                    .append("：").append(server.status())
                    .append("（直接 ").append(server.direct())
                    .append("，延迟 ").append(server.deferred())
                    .append("，隐藏 ").append(server.hidden()).append("）");
            if (server.detail() != null && !server.detail().isEmpty()) {
                out.append("，").append(server.detail());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Describe 快照补充者（喂给 {@code Config.describeContributor(...)}）。
     *
     * <p>把 MCP 的装配事实写进快照：一台连不上、几台禁用、工具多少——
     * 这些不写进快照，部署方就会以为「其余能力也有」。
     *
     * @return 补充者
     */
    public net.hegq.mos4j.core.DescribeContributor describeContributor() {
        return (features, caveats) -> {
            McpSnapshot snapshot = manager.snapshot();
            if (snapshot.servers().isEmpty()) {
                return;
            }
            long connected = snapshot.connectedServers().size();
            features.add("MCP 服务器（" + connected + "/" + snapshot.servers().size() + " 台已连接）");
            for (McpSnapshot.ServerStatus server : snapshot.servers()) {
                if (server.status() != McpSnapshot.Status.CONNECTED) {
                    caveats.add("MCP 服务器 " + server.name() + " 未就绪："
                            + server.status() + (server.detail() == null || server.detail().isEmpty()
                            ? "" : "（" + server.detail() + "）"));
                }
            }
        };
    }

    /** 管理器（重连、换代、关闭）。 */
    public McpManager manager() {
        return manager;
    }

    /** 关闭全部连接（幂等）。 */
    @Override
    public void close() {
        manager.close();
    }
}
