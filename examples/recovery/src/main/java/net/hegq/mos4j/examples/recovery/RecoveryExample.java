package net.hegq.mos4j.examples.recovery;

import net.hegq.mos4j.driver.ResumeOptions;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.HangingCall;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.session.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MoS4J 恢复示例：崩在半路的现场 → 派生现场 → 显式续跑。
 *
 * <p><b>这一例演示的事</b>：进程会挂、会被 Ctrl-C、会被机器回收。
 * MoS4J 的口径是<b>「日志即事实」</b>：崩掉的那一轮在日志里就是缺尾巴，
 * 重开时框架认得出「这一轮没收尾」「那次调用还悬空」，剩下的由宿主决定。
 *
 * <p><b>三条纪律</b>：
 * <ul>
 *   <li><b>派生是纯读</b>：{@code interruptedTurn()} / {@code hangingCalls()} /
 *       {@code pendingApprovals()} 不写日志、不改状态、不推进序号——想知道现场就问，问多少次都一样；</li>
 *   <li><b>不自动续跑</b>：框架不会自己拾起挂起的轮次（那等于替你决定「刚才那次副作用要不要重做」）；
 *       必须宿主显式 {@code resume(...)}；</li>
 *   <li><b>续跑沿用原轮次号</b>：审批决定的复用键是 {@code (轮次, 调用 id)}，
 *       轮次号一变就匹配不上。</li>
 * </ul>
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.recovery.RecoveryExample
 * </pre>
 */
public final class RecoveryExample {

    /** 会话 id。 */
    public static final String SESSION_ID = "recovery";

    /** 系统提示词。 */
    public static final String SYSTEM_PROMPT = "你是一个助手。";

    private RecoveryExample() {
    }

    /**
     * 入口：造一个「崩在半路」的现场 → 派生 → 续跑。
     *
     * @param args 无用（保留位置）
     * @throws IOException 会话读写失败
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("data");
        ChatModel model = net.hegq.mos4j.sdk.Mos4j.defaultModel(
                net.hegq.mos4j.sdk.Mos4j.Protocol.OPENAI, null, null, null, null);
        Mos4j mos4j = assemble(root, model, root);
        crashHalfway(mos4j, root.resolve("workspace"));
        describe(mos4j);
        System.out.println("[续跑] " + mos4j.resume(SESSION_ID, ResumeOptions.latest()));
    }

    /**
     * 装配（模型由宿主给；工作目录 = 文件工具的 jail）。
     *
     * @param logDir    会话日志目录
     * @param model     模型
     * @param workspace 文件工具的工作目录
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model, Path workspace) throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .workspace(workspace)
                .noPathScope()
                .systemPrompt(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 造一个「崩在半路」的现场：开了轮、批了准、声明了调用，但没回填结果、没收尾。
     *
     * <p>这就是进程被杀掉时留在日志里的样子——本例直接把它写出来，
     * 好让「恢复」这一步有确定的起点（真机上这一步是崩溃自己留下的）。
     *
     * @param mos4j    门面
     * @param workspace 工作目录（放被读的文件）
     * @throws IOException 会话读写失败
     */
    public static void crashHalfway(Mos4j mos4j, Path workspace) throws IOException {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("notes.txt"), "部署清单：构建 → 冒烟 → 灰度\n",
                StandardCharsets.UTF_8);

        Session session = mos4j.session(SESSION_ID);
        String args = "{\"path\":\"notes.txt\"}";
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.USER).turn(1).text("读一下部署清单").build());
        session.append(Event.builder(EventKind.ASSISTANT).turn(1).text("我去读")
                .toolCalls(List.of(new ToolCall("c1", "read", args))).build());
        session.append(Event.builder(EventKind.TOOL_CALL).turn(1)
                .toolCall(new ToolCall("c1", "read", args)).build());
        // 到此「崩掉」：没有 tool/result，也没有 turn/end。
    }

    /**
     * 派生现场（纯读：不写日志、不改状态）。
     *
     * @param mos4j 门面
     * @return 现场描述
     * @throws IOException 会话读失败
     */
    public static String describe(Mos4j mos4j) throws IOException {
        Session session = mos4j.session(SESSION_ID);
        int turn = session.interruptedTurn();
        List<HangingCall> hanging = session.hangingCalls();
        StringBuilder sb = new StringBuilder();
        sb.append("[现场] 被中断的轮次=").append(turn).append('\n');
        for (HangingCall call : hanging) {
            sb.append("  悬空调用: ").append(call.callId()).append(' ')
                    .append(call.toolName()).append(' ').append(call.args()).append('\n');
        }
        sb.append("  待审批: ").append(session.pendingApprovals().size()).append('\n');
        return sb.toString();
    }

    /**
     * 显式续跑：把悬空的调用原样重放一次（走普通安全链：守卫 → 审批 → 执行），
     * 回填**真实**结果，然后那一轮正常收尾。
     *
     * @param mos4j 门面
     * @return 续跑后的回答
     * @throws IOException 会话读写失败
     * @throws net.hegq.mos4j.driver.NothingToResumeException 没有可续的调用（那一轮保持挂起）
     */
    public static String resume(Mos4j mos4j) throws IOException,
            net.hegq.mos4j.driver.NothingToResumeException {
        return mos4j.resume(SESSION_ID, ResumeOptions.latest());
    }

    /**
     * 写一条工具结果（给用例与手工实验用：模拟「那次调用其实成功了」）。
     *
     * @param session 会话
     * @param callId  调用 id
     * @param content 结果
     * @throws IOException 会话写失败
     */
    public static void backfill(Session session, String callId, String content) throws IOException {
        session.append(Event.builder(EventKind.TOOL_RESULT).turn(1)
                .toolResult(new ToolResult(callId, content, false)).build());
    }
}
