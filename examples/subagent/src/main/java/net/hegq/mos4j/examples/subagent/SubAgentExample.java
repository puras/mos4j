package net.hegq.mos4j.examples.subagent;

import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.sdk.Mos4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * MoS4J 子代理示例：父模型点名派活，子代理在自己的会话里跑，带固定 JSON 回执。
 *
 * <p><b>这一例演示的事</b>：子代理就是**一个普通工具**。
 * <ul>
 *   <li><b>清单</b>（name / description）在装配期进静态系统提示词——父模型进门就看得见有哪些人可派；</li>
 *   <li><b>工具面收窄</b>：子代理实际可用工具 = 声明白名单 ∩ 父当前可见工具集，
 *       并且恒定排除 {@code subagent__*} 与 {@code tool_search}（子代理不能再派子代理）；</li>
 *   <li><b>独立会话</b>：子代理跑在自己的会话里，父会话只拿到一段回执；</li>
 *   <li><b>固定回执</b>：{@code {status, summary, findings, error}}；
 *       子代理失败（出错、超时、坏回执）也只是一次工具失败，**不会把父这一轮带崩**。</li>
 * </ul>
 *
 * <p><b>目录就是授权边界</b>：框架只读你声明的子代理目录（每个子代理一个目录，
 * 里面一份 {@code AGENT.md}），别的一概不碰。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.subagent.SubAgentExample "帮我审一下改动"
 * </pre>
 */
public final class SubAgentExample {

    /** 子代理目录（相对本示例的工作目录）。 */
    public static final Path SUBAGENT_DIR = Path.of("agents");

    /** 系统提示词：先看子代理清单，要派活就点名。 */
    public static final String SYSTEM_PROMPT =
            "你是一个协调者。需要专业判断时，先看子代理清单，"
                    + "用 subagent__<名字> 工具点名派活；拿到回执后自己复核再下结论。";

    private SubAgentExample() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数是任务（缺省问审阅）
     * @throws IOException 会话读写失败
     */
    public static void main(String[] args) throws IOException {
        Mos4j mos4j = assemble(Path.of("data"), null);
        printSubAgents(mos4j);

        String task = args.length > 0 && !args[0].isBlank() ? args[0] : "帮我审一下这处改动";
        System.out.println(mos4j.run("subagent", task));
    }

    /**
     * 装配（模型由门面按协议 + 环境变量装）。
     *
     * @param logDir 会话日志目录
     * @param model  模型（可空 = 用环境变量装）
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model) throws IOException {
        Mos4j.Builder builder = Mos4j.builder()
                .logDir(logDir)
                .subAgents(SUBAGENT_DIR)
                .systemPrompt(SYSTEM_PROMPT);
        if (model != null) {
            builder.model(model);
        }
        if ("1".equals(System.getenv("MOS_NO_PATH_SCOPE"))) {
            builder.noPathScope();
        }
        return builder.build();
    }

    /**
     * 装配（模型由宿主给，用例走这条）。
     *
     * @param logDir 会话日志目录
     * @param model  模型
     * @param dirs   子代理目录
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model, Path dirs) throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .noPathScope()
                .subAgents(dirs)
                .systemPrompt(SYSTEM_PROMPT)
                .build();
    }

    /** 打印子代理清单。 */
    private static void printSubAgents(Mos4j mos4j) {
        System.out.println("子代理清单：");
        for (String name : names(mos4j)) {
            System.out.println("  - " + name);
        }
    }

    /**
     * 子代理名清单（从装配快照里读——快照是「装了什么」的事实来源）。
     *
     * @param mos4j 门面
     * @return 名字清单
     */
    public static List<String> names(Mos4j mos4j) {
        for (String feature : mos4j.agent().describe().features()) {
            if (feature.startsWith("子代理: ")) {
                int open = feature.indexOf('（');
                int close = feature.lastIndexOf('）');
                if (open >= 0 && close > open) {
                    String inner = feature.substring(open + 1, close);
                    if (inner.isEmpty()) {
                        return List.of();
                    }
                    return List.of(inner.split("、"));
                }
            }
        }
        return List.of();
    }
}
