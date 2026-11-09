package net.hegq.mos4j.examples.chat;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.policy.ToolNamePolicy;
import net.hegq.mos4j.sdk.Mos4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * MoS4J 多轮对话示例：会话恢复 + 审批 + 策略。
 *
 * <p><b>这一例演示的事</b>：
 * <ul>
 *   <li><b>会话是事实</b>：每轮的 user / assistant / tool / turn-end 事件落 JSONL，
 *       重启后按 id 重开即恢复上下文（不用宿主自己拼历史）；</li>
 *   <li><b>审批是可选纵深</b>：装了审批通道，需要人确认的调用才问人；不装就是
 *       「不需要人确认」——两者的差别写在装配里，不在运行期猜；</li>
 *   <li><b>策略与可见性同源</b>：被拒的工具既跑不了，也不进模型的工具清单。</li>
 * </ul>
 *
 * <p><b>怎么跑</b>（示例不读 .env，配置来源归宿主）：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.chat.ChatExample            # 恢复上次会话
 * java -cp ... net.hegq.mos4j.examples.chat.ChatExample --new     # 开新会话
 * java -cp ... net.hegq.mos4j.examples.chat.ChatExample --session s2
 * </pre>
 * 退出：输入 {@code q}。
 */
public final class ChatExample {

    /** 审批要问人的工具（写文件与跑命令）。 */
    public static final List<String> ASK_FIRST = List.of("write", "edit", "shell");

    /** 系统提示词。 */
    public static final String SYSTEM_PROMPT =
            "你是 MoS4J 的对话助手。用中文回答；需要动手时用工具，别空谈。";

    private ChatExample() {
    }

    /**
     * 入口（交互式）。
     *
     * @param args 命令行参数（{@code --new} / {@code --session <id>}）
     * @throws Exception 装配或运行失败
     */
    public static void main(String[] args) throws Exception {
        String sessionId = sessionIdFrom(args);
        Mos4j mos4j = assemble(Path.of("data"), new ConsoleApproval());
        System.out.println("[会话] " + sessionId + "（事件日志在 data/" + sessionId + ".jsonl）");

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("> ");
            String line = reader.readLine();
            if (line == null || line.strip().equalsIgnoreCase("q")) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                System.out.println(mos4j.run(sessionId, line));
            } catch (Exception e) {
                // 一轮失败不该把整个进程带走：把原因说清楚，宿主自己决定要不要继续。
                System.out.println("[这一轮失败] " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    /**
     * 装配（模型由门面按协议 + 环境变量装）。
     *
     * @param logDir    会话日志目录
     * @param approvals 审批通道
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ApprovalChannel approvals) throws IOException {
        Mos4j.Builder builder = Mos4j.builder()
                .logDir(logDir)
                .systemPrompt(SYSTEM_PROMPT)
                .guards(guards())
                .approvals(approvals)
                // 策略：策略管「能不能调」，审批管「这次要不要人点头」——两件事分开。
                .policy(ToolNamePolicy.unrestricted());
        if ("1".equals(System.getenv("MOS_NO_PATH_SCOPE"))) {
            builder.noPathScope();
        }
        return builder.build();
    }

    /**
     * 守卫总线：<b>要问人的工具在这里点名</b>。
     *
     * <p>审批通道不是「自动生效」的：守卫先判，判成 {@code ask}（要人点头）
     * 才会走审批通道。守卫直接拒绝的，压根不问人；守卫放行的，也不用问。
     *
     * @return 守卫总线
     */
    public static GuardBus guards() {
        return new GuardBus().register(event -> ASK_FIRST.contains(event.toolName())
                ? GuardDecision.ask("需要人工确认：" + event.toolName())
                : GuardDecision.allow());
    }

    /**
     * 装配（模型由宿主给：用例走这条，不碰网络）。
     *
     * @param logDir    会话日志目录
     * @param model     模型
     * @param approvals 审批通道
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model, ApprovalChannel approvals) throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .noPathScope()
                .systemPrompt(SYSTEM_PROMPT)
                .guards(guards())
                .approvals(approvals)
                .build();
    }

    /**
     * 控制台审批：把「要不要问人」变成「问到了什么」。
     *
     * <p>非交互环境（没有终端）时直接给「不可用」而不是默认放行——
     * 默认放行等于「没人看着也照样执行」。
     */
    public static final class ConsoleApproval implements ApprovalChannel {

        private final BufferedReader reader;
        private final java.io.PrintStream out;

        /** 用标准输入输出。 */
        public ConsoleApproval() {
            this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        }

        /**
         * 构造器（可注入输入输出：用例里用假终端）。
         *
         * @param reader 输入
         * @param out    输出
         */
        public ConsoleApproval(BufferedReader reader, java.io.PrintStream out) {
            this.reader = reader;
            this.out = out;
        }

        @Override
        public ApprovalResult request(ApprovalRequest request) {
            out.println();
            out.println("[需要你点头] 工具: " + request.toolName()
                    + "（调用 " + request.callId() + "）");
            out.println("  理由: " + request.reason());
            out.print("  放行吗？(y/N) ");
            try {
                String answer = reader.readLine();
                if (answer == null) {
                    return ApprovalResult.unavailable("没有终端可问（输入已结束）");
                }
                String trimmed = answer.strip().toLowerCase();
                if ("y".equals(trimmed) || "yes".equals(trimmed)) {
                    return ApprovalResult.approved();
                }
                if ("n".equals(trimmed) || "no".equals(trimmed)) {
                    return ApprovalResult.rejected("用户拒绝了这次调用");
                }
                return ApprovalResult.cancelled("用户没有明确答复");
            } catch (IOException e) {
                return ApprovalResult.unavailable("读终端失败: " + e.getMessage());
            }
        }
    }

    /** 从命令行参数里取会话 id（{@code --new} = 新会话，{@code --session <id>} = 指定）。 */
    private static String sessionIdFrom(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--session".equals(args[i]) && i + 1 < args.length) {
                return args[i + 1];
            }
            if ("--new".equals(args[i])) {
                return "chat-" + System.currentTimeMillis();
            }
        }
        return "chat";
    }
}
