package net.hegq.mos4j.examples.chat;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.model.chat.ChatModel;
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
    static void main(String[] args) throws Exception {
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
                // 流式：增量在 onChunk 里实时输出，run(...) 的返回值也是完整回复。
                // 两者都印会重复一遍，所以这里只靠增量渲染，返回值丢掉
                // （对齐 Go 示例的 `_ = reply // 回复已由 OnChunk 流式渲染`）。
                mos4j.run(sessionId, line);
                System.out.println();
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
                // 流式：真人的对话界面等不了一整段文本回完。
                // 增量的思维链用灰色前缀区分开——不然推理内容和正式回答会粘在一起，
                // 看不出哪句是真话。
                .stream(true)
                .onChunk(ChatExample::render)
                // 策略：策略管「能不能调」，审批管「这次要不要人点头」——两件事分开。
                .policy(ToolNamePolicy.unrestricted());
        if ("1".equals(System.getenv("MOS_NO_PATH_SCOPE"))) {
            builder.noPathScope();
        }
        return builder.build();
    }

    /**
     * 增量渲染：思维链与正式回答分开输出。
     *
     * <p><b>为什么必须分开</b>：推理模型的思维链增量与正文增量走同一个回调，
     * 只是 {@code reasoning} 标志不同。不区分就会把「草稿」当「答案」印给用户。
     *
     * @param chunk 增量块
     */
    public static void render(net.hegq.mos4j.model.chat.StreamChunk chunk) {
        if (chunk.reasoning() != null && !chunk.reasoning().isEmpty()) {
            System.out.print("\u001b[90m" + chunk.reasoning() + "\u001b[0m");
        }
        if (chunk.delta() != null && !chunk.delta().isEmpty()) {
            System.out.print(chunk.delta());
        }
        System.out.flush();
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
     * 装配（流式；用例走这条，不碰网络，也不往 stdout 写）。
     *
     * <p>与主入口的区别只在「增量回调可注入」：主入口用 {@link #render} 直写终端，
     * 用例需要把增量收起来断言。
     *
     * @param logDir    会话日志目录
     * @param model     模型
     * @param approvals 审批通道
     * @param onChunk   增量回调
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assembleStreaming(Path logDir, ChatModel model, ApprovalChannel approvals,
                                          java.util.function.Consumer<net.hegq.mos4j.model.chat.StreamChunk> onChunk)
            throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .noPathScope()
                .systemPrompt(SYSTEM_PROMPT)
                .guards(guards())
                .approvals(approvals)
                .stream(true)
                .onChunk(onChunk)
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
