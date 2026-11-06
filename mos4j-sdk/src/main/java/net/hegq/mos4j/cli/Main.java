package net.hegq.mos4j.cli;

import net.hegq.mos4j.sdk.Mos4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mos4j} 命令行入口（T6.9 最小形态）：装配 + 跑一轮。
 *
 * <p><b>用法</b>：</p>
 * <pre>
 *   mos4j --api-key KEY [--base-url URL] [--model NAME]
 *         [--protocol openai|anthropic|gemini] [--system PROMPT]
 *         [--workspace DIR] [--log-dir DIR] [--no-file-tools]
 *         [--session ID] "一轮的输入文本"
 * </pre>
 *
 * <p><b>最小形态的边界（诚实声明）</b>：单轮执行——输入一条、跑完即退；
 * 多轮 REPL、审批交互（--approvals tty）、流式打点属后续增强。
 * 协议缺省 OpenAI（{@code OPENAI_API_KEY}），与 {@link Mos4j} 门面同一口径。
 *
 * <p><b>为什么是单轮（不这么做会出什么事）</b>：多轮 REPL 需要交互式审批与流式打点，
 * 而这两件事都依赖宿主的输入通道（tty / 管道）——没有它们，CLI 要么假装能审批
 * （把需要人拍板的调用默默放行，违反不变式 I5 的 fail-closed），要么每轮都卡住。
 * 所以这里只承诺「装配 + 跑一轮」：能验证的事就做真，做不到的写进上面那段边界里。</p>
 */
public final class Main {

    private Main() {
    }

    /** 入口。 */
    public static void main(String[] args) {
        try {
            int code = run(args);
            System.exit(code);
        } catch (Exception error) {
            System.err.println("mos4j: " + error.getMessage());
            System.exit(2);
        }
    }

    /** 可测的执行路径（返回退出码）。 */
    static int run(String[] args) throws IOException {
        String apiKey = null;
        String baseUrl = null;
        String model = null;
        String protocol = "openai";
        String systemPrompt = null;
        String workspace = null;
        String logDir = null;
        String sessionId = "cli";
        boolean fileTools = true;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--api-key" -> apiKey = next(args, ++i, arg);
                case "--base-url" -> baseUrl = next(args, ++i, arg);
                case "--model" -> model = next(args, ++i, arg);
                case "--protocol" -> protocol = next(args, ++i, arg).toLowerCase(java.util.Locale.ROOT);
                case "--system" -> systemPrompt = next(args, ++i, arg);
                case "--workspace" -> workspace = next(args, ++i, arg);
                case "--log-dir" -> logDir = next(args, ++i, arg);
                case "--session" -> sessionId = next(args, ++i, arg);
                case "--no-file-tools" -> fileTools = false;
                case "--help", "-h" -> {
                    usage(System.out);
                    return 0;
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("未知参数: " + arg + "（--help 查看用法）");
                    }
                    positional.add(arg);
                }
            }
        }
        if (positional.isEmpty()) {
            usage(System.err);
            return 2;
        }
        String input = String.join(" ", positional);

        Mos4j.Builder builder = Mos4j.builder()
                .protocol(switch (protocol) {
                    case "openai" -> Mos4j.Protocol.OPENAI;
                    case "anthropic" -> Mos4j.Protocol.ANTHROPIC;
                    case "gemini" -> Mos4j.Protocol.GEMINI;
                    default -> throw new IllegalArgumentException("未知协议: " + protocol);
                })
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .systemPrompt(systemPrompt)
                .fileTools(fileTools);
        if (workspace != null) {
            builder.workspace(Path.of(workspace));
        }
        if (logDir != null) {
            builder.logDir(Path.of(logDir));
        }
        Mos4j mos4j = builder.build();

        // 会话落盘：ensure 存在（restore 已自动补 Header；追加历史在 Session.append 内完成）
        System.out.println(mos4j.run(sessionId, input));
        return 0;
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 需要一个值");
        }
        return args[index];
    }

    private static void usage(java.io.PrintStream out) {
        out.println("""
                mos4j — MoS4J 命令行（最小形态：装配 + 跑一轮）

                用法:
                  mos4j [选项] "输入文本"

                选项:
                  --api-key KEY        API 密钥（缺省读 OPENAI_API_KEY / ANTHROPIC_API_KEY / GEMINI_API_KEY）
                  --base-url URL       兼容网关地址
                  --model NAME         模型名
                  --protocol NAME      openai（缺省）| anthropic | gemini
                  --system PROMPT      系统提示词
                  --workspace DIR      文件工具的 jail 锚点（缺省当前目录）
                  --log-dir DIR        会话日志目录（缺省 .mos4j/sessions）
                  --session ID         会话 id（缺省 cli；同 id = 续聊）
                  --no-file-tools      不装配文件工具
                  -h, --help           本说明
                """);
    }

    /** 供测试确认日志目录确实落了文件。 */
    static boolean sessionExists(Path logDir, String id) {
        return Files.exists(logDir.resolve(id + ".jsonl"));
    }
}
