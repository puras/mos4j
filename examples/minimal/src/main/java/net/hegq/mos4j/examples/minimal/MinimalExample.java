package net.hegq.mos4j.examples.minimal;

import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.sdk.Mos4j;

import java.io.IOException;
import java.nio.file.Path;

/**
 * MoS4J 最小示例：一次装配，一个问题，一句回答。
 *
 * <p><b>这个示例证明的事</b>：装配层够用。一个真实可跑的智能体，装配只要十几行；
 * 其余都是宿主自己的事（选工具、策略、审批问人）。
 *
 * <p><b>怎么跑</b>（示例不读 .env，配置来源归宿主）：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp "examples/minimal/target/classes:$(cat cp.txt)" \
 *      net.hegq.mos4j.examples.minimal.MinimalExample "只回答：1+1=?"
 * </pre>
 *
 * <p><b>这台机器装不了路径强制时</b>（受管 Mac 常常如此）：框架会在装配期报错而不是
 * 退化成「没隔离的假装隔离」。这时显式加 {@code MOS_NO_PATH_SCOPE=1} 关掉它——
 * 代价是 shell 不再受路径强制保护（Describe 会如实写明）。
 */
public final class MinimalExample {

    /** 环境变量名：显式关掉路径强制（这台机器用不了沙箱时）。 */
    public static final String NO_PATH_SCOPE_ENV = "MOS_NO_PATH_SCOPE";

    /** 系统提示词。 */
    public static final String SYSTEM_PROMPT = "你是 MoS4J 的最小示例助手。回答要短。";

    private MinimalExample() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数是问题（缺省问 1+1）
     * @throws Exception 装配或运行失败
     */
    public static void main(String[] args) throws Exception {
        String question = args.length > 0 && !args[0].isBlank() ? args[0] : "只回答：1+1=?";
        Mos4j mos4j = assemble(Path.of("data"));
        printSnapshot(mos4j);
        String reply = mos4j.run("minimal", question);
        System.out.println(reply);
        System.out.println("[会话] data/minimal.jsonl（事件日志就是事实，回放看它）");
    }

    /**
     * 装配（模型由门面按协议 + 环境变量装；文件工具装在当前工作目录的 jail 里）。
     *
     * @param logDir 会话日志目录
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir) throws IOException {
        Mos4j.Builder builder = Mos4j.builder()
                .logDir(logDir)
                .systemPrompt(SYSTEM_PROMPT);
        if ("1".equals(System.getenv(NO_PATH_SCOPE_ENV))) {
            builder.noPathScope();
        }
        return builder.build();
    }

    /**
     * 装配（模型由宿主给：用例与「已有模型」的宿主都走这条）。
     *
     * @param logDir 会话日志目录
     * @param model  模型
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model) throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .noPathScope()
                .systemPrompt(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 用宿主给的模型跑一轮（用例用这条路径：不碰网络也能验证装配与循环）。
     *
     * @param question 问题
     * @param model    模型
     * @param logDir   会话日志目录
     * @return 模型回答
     * @throws IOException 会话读写失败
     */
    public static String answer(String question, ChatModel model, Path logDir) throws IOException {
        return assemble(logDir, model).run("minimal", question);
    }

    /** 打印装配快照（装了什么 + 没装什么——降级必须看得见）。 */
    private static void printSnapshot(Mos4j mos4j) {
        var describe = mos4j.agent().describe();
        System.out.println("[装配快照]");
        System.out.println("  模型: " + describe.modelProvider() + " / " + describe.model());
        System.out.println("  工具: " + String.join("、", describe.tools()));
        System.out.println("  扩展: " + String.join("、", describe.features()));
        if (!describe.caveats().isEmpty()) {
            System.out.println("  边界: " + String.join("；", describe.caveats()));
        }
    }
}
