package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.sandbox.RunRequest;
import net.hegq.mos4j.sandbox.RunResult;
import net.hegq.mos4j.sandbox.Runner;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.time.Duration;

/**
 * shell 工具：在宿主沙箱里跑一条命令。
 *
 * <p><b>非零退出码不是错误</b>（机制 M9）：exit 1 是<b>正常结果</b>，
 * {@code isError=false}，输出末尾附 {@code [exit code: N]}。
 * 把非零当失败，模型就会写 {@code ; echo "exit=$?"} 把失败藏进输出——
 * 失败一旦被掩进输出，事实就被污染了。
 *
 * <p><b>真正算失败的是</b>：命令根本没起来、超时。这两种才 {@code isError=true}。
 *
 * <p><b>工作目录与环境由宿主固定</b>：模型只能提命令，改不了边界。
 */
public final class ShellTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "shell";

    private final Runner runner;

    /**
     * @param runner 运行器
     */
    public ShellTool(Runner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("tool: shell 需要 runner");
        }
        this.runner = runner;
    }

    /**
     * 参数。
     *
     * @param command     命令行
     * @param description 这条命令要做什么（人类可读，便于审计）
     * @param timeout     超时秒数（会被宿主的超时上界压住）
     */
    public record Params(
            @ToolParam(desc = "要执行的命令行", required = true) String command,
            @ToolParam(desc = "这条命令要做什么（人类可读，便于审计）") String description,
            @ToolParam(desc = "超时秒数（可省略，用宿主缺省值）") Integer timeout) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在沙箱里执行一条 shell 命令。非零退出码是正常结果（输出末尾会带 [exit code: N]）；"
                + "命令没起来或超时才算失败。工作目录与超时由宿主固定。"
                + "description 可写一句人类可读说明（会回显在输出开头，便于审计）。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.command() == null || params.command().isBlank()) {
            throw new ToolException("shell 失败：缺少参数 command");
        }
        Duration timeout = params.timeout() == null ? null : Duration.ofSeconds(params.timeout());
        RunResult result = runner.run(new RunRequest(params.command(), timeout, null));

        if (!result.started()) {
            throw new ToolException("shell 失败：命令启动失败: " + result.startFailure());
        }
        String output = render(result);
        if (result.timedOut()) {
            throw new ToolException("shell 失败：命令超时（已到宿主上限）\n" + note(params.description()) + output);
        }
        return note(params.description()) + output;
    }

    /**
     * 把模型给的说明贴在回执最前面（相当于 Go 侧回执里的 {@code description} 字段）。
     *
     * <p>说明放在输出之前：输出末尾的 {@code [exit code: N]} 是标记，标记后面不能再加东西。
     */
    private static String note(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        return "说明：" + description.strip() + "\n";
    }

    /**
     * 渲染输出（含退出码标记）。
     *
     * @param result 运行结果
     * @return 输出文本
     */
    static String render(RunResult result) {
        StringBuilder sb = new StringBuilder();
        if (!result.stdout().isEmpty()) {
            sb.append(result.stdout());
            if (!result.stdout().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (!result.stderr().isEmpty()) {
            sb.append(result.stderr());
            if (!result.stderr().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (result.truncated()) {
            sb.append("[输出已截断]\n");
        }
        sb.append("[exit code: ").append(result.exitCode()).append(']');
        return sb.toString();
    }
}
