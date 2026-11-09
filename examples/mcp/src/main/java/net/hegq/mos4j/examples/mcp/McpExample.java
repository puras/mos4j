package net.hegq.mos4j.examples.mcp;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.mcp.McpExposure;
import net.hegq.mos4j.mcp.McpKit;
import net.hegq.mos4j.mcp.McpServerConfig;
import net.hegq.mos4j.mcp.McpSnapshot;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MoS4J MCP 示例：接一台 MCP 服务器，让它的工具进模型的工具清单。
 *
 * <p><b>这一例演示的事</b>：
 * <ul>
 *   <li><b>MCP 的官方 SDK 是 optional</b>：{@code mos4j-tool} 只声明官方 MCP SDK 的可选依赖（传递依赖
 *       不进 SDK 依赖基线），宿主自己显式声明官方 SDK 坐标、再 {@code attach} 一次即可；</li>
 *   <li><b>工具命名有规矩</b>：{@code mcp__<服务器>__<工具>}（与子代理的
 *       {@code subagent__<名字>} 同一套命名空间纪律）；</li>
 *   <li><b>三档曝光</b>：DIRECT（直接进模型清单）/ DEFERRED（要 tool_search 才激活）/
 *       HIDDEN（模型看不见，但程序化调用仍可）；</li>
 *   <li><b>失败要看得见</b>：连不上的服务器、没就绪的工具都写进装配快照，不静默消失。</li>
 * </ul>
 *
 * <p><b>怎么跑</b>（自带一台回声服务器，不需要装任何东西）：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.mcp.McpExample
 * </pre>
 * 接真实服务器：把 {@link #echoServer()} 换成
 * {@code McpServerConfig.stdio("fs", "npx", List.of("-y", "@modelcontextprotocol/server-filesystem", dir))}。
 */
public final class McpExample {

    /** 系统提示词。 */
    public static final String SYSTEM_PROMPT =
            "你可以用 MCP 工具。需要回声时调用 mcp__echo__echo。";

    private McpExample() {
    }

    /**
     * 入口。
     *
     * @param args 无用
     */
    public static void main(String[] args) throws Exception {
        ChatModel model = net.hegq.mos4j.sdk.Mos4j.defaultModel(
                net.hegq.mos4j.sdk.Mos4j.Protocol.OPENAI, null, null, null, null);

        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(echoServer()))) {
            printSnapshot(kit.snapshot());
            Agent agent = agent(registry, model, kit);
            printTools(registry);
            Session session = new Session("mcp", null);
            System.out.println(agent.run(session, "用回声工具发一句 hello"));
        }
    }

    /**
     * 装配：MCP 工具进同一张注册表；服务器说明每会话取一次（连接会掉，文本不能拍死）。
     *
     * @param registry 工具注册表
     * @param model    模型
     * @param kit      MCP 门面
     * @return 智能体
     */
    public static Agent agent(ToolRegistry registry, ChatModel model, McpKit kit) {
        return Agent.of(Config.builder()
                .model(model)
                .tools(registry)
                .systemPrompt(SYSTEM_PROMPT)
                .mcpInstructions(kit.instructions())
                .describeContributor(kit.describeContributor())
                .closer(kit)
                .build());
    }

    /**
     * 自带的回声服务器配置（stdio 子进程）。
     *
     * @return 配置
     */
    public static McpServerConfig echoServer() {
        return McpServerConfig.stdio("echo", javaBinary(),
                        List.of("-cp", classpath(), EchoMcpServer.class.getName()))
                .withExposure(McpExposure.DIRECT);
    }

    /** 打印服务器快照（装了什么、哪台没就绪）。 */
    private static void printSnapshot(McpSnapshot snapshot) {
        System.out.println("[MCP 快照]");
        for (McpSnapshot.ServerStatus server : snapshot.servers()) {
            System.out.println("  " + server.name() + "：" + server.status()
                    + "（直接 " + server.direct() + "，延迟 " + server.deferred()
                    + "，隐藏 " + server.hidden() + "）");
        }
    }

    /** 打印进了注册表的工具名。 */
    private static void printTools(ToolRegistry registry) {
        List<String> names = new ArrayList<>();
        for (var tool : registry.list()) {
            names.add(tool.name());
        }
        System.out.println("[工具面] " + String.join("、", names));
    }

    /** 当前 JVM 的 java 可执行文件。 */
    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 当前进程的 classpath（stdio 子进程要用同一份类路径才能起服务器）。 */
    private static String classpath() {
        return System.getProperty("java.class.path");
    }
}
