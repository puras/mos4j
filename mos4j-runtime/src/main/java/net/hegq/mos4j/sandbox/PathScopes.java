package net.hegq.mos4j.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 路径强制的平台工厂。
 *
 * <p><b>后端现状</b>：
 * <ul>
 *   <li><b>macOS</b> = Seatbelt（{@code sandbox-exec}，内核级）——<b>已验证</b>
 *       （profile 形态与 Go 逐条对齐，且能被内核的 profile 解析器接受；强制效果需允许应用沙箱的宿主）；</li>
 *   <li><b>Linux</b> = Landlock LSM（{@link LandlockScope}）——<b>代码在位但尚未在 Linux 上验证</b>，
 *       因此<b>默认不装配</b>（见 {@link #supported()}）；要用请显式传
 *       {@code new LandlockScope(roots)}；</li>
 *   <li><b>Windows</b> = 未实现。</li>
 * </ul>
 *
 * <p><b>缺省范围与 Go 对齐</b>：{@link #derive(Path)} 从工作目录派生（写根 = 工作目录），
 * 对应 Go 的 {@code deriveScope(WorkDir)}——路径强制不是「宿主可选打开的功能」，
 * 有<b>已验证</b>的后端就该默认生效。
 */
public final class PathScopes {

    /** macOS 自带的沙箱执行器。 */
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    /** 探测用的空 profile（放行一切；只用来问「能不能 apply」）。 */
    private static final String PROBE_PROFILE = "(version 1) (allow default)";

    /** 探测用的被测命令（存在且什么都不做）。 */
    private static final String PROBE_COMMAND = "/usr/bin/true";

    /** 探测超时（秒）。 */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Seatbelt profile 解析失败的退出码（内核解析器报语法错时）。 */
    private static final int SEATBELT_PARSE_ERROR_EXIT = 65;

    /** Seatbelt apply 被宿主策略拒绝的退出码。 */
    private static final int SEATBELT_APPLY_DENIED_EXIT = 71;

    private PathScopes() {
    }

    /**
     * 当前平台是否有<b>已验证</b>、可供缺省装配的后端。
     *
     * <p>口径：<b>「代码在位」不等于「可缺省启用」</b>。Linux 的 Landlock 后端还没在
     * Linux 真机上跑过，缺省装它等于把未验证的隔离面直接顶到生产路径上；所以这里只认
     * macOS Seatbelt。Linux 想要路径强制，走显式装配（{@code new LandlockScope(roots)}）。
     *
     * @return true 表示有
     */
    public static boolean supported() {
        return isMac() && Files.isExecutable(SANDBOX_EXEC);
    }

    /**
     * 平台后端状态（一句话，进 Describe 的 caveat）。
     *
     * @return 状态说明
     */
    public static String status() {
        if (isMac()) {
            return Files.isExecutable(SANDBOX_EXEC)
                    ? "macOS Seatbelt（sandbox-exec，内核级，已验证）"
                    : "macOS Seatbelt 不可用（" + SANDBOX_EXEC + " 缺失或不可执行）";
        }
        if (isLinux()) {
            return "Linux Landlock 后端代码在位但尚未在 Linux 上验证 → 默认不装配"
                    + "（显式传 LandlockScope 才启用）";
        }
        return "Windows 后端未实现";
    }

    /**
     * 从工作目录派生路径强制（写根 = 工作目录，与 Go 的 {@code deriveScope} 同口径）。
     *
     * @param workDir 工作目录
     * @return 路径强制
     * @throws UnsupportedOperationException 当前平台没有后端
     */
    public static PathScope derive(Path workDir) {
        return create(workDir);
    }

    /**
     * 创建路径强制。
     *
     * @param baseWriteRoot 初始允许写入的根
     * @return 路径强制
     * @throws UnsupportedOperationException 当前平台没有后端
     */
    public static PathScope create(Path baseWriteRoot) {
        if (isMac()) {
            if (!Files.isExecutable(SANDBOX_EXEC)) {
                throw new UnsupportedOperationException(
                        "sandbox: 当前平台没有可用的路径强制后端（macOS 的 " + SANDBOX_EXEC
                                + " 缺失或不可执行）：不能假装有路径强制");
            }
            return new SeatbeltScope(List.of(baseWriteRoot));
        }
        if (isLinux()) {
            // 显式装配路径（后端未在 Linux 上验证：见类注释）
            return new LandlockScope(List.of(baseWriteRoot));
        }
        throw new UnsupportedOperationException(
                "sandbox: 当前平台没有可用的路径强制后端（macOS 用 Seatbelt、Linux 用 Landlock；"
                        + "确认放开请在宿主显式声明写根 \"/\"）");
    }

    /**
     * 路径强制工厂：按写根现造一份强制层（动态写根用）。
     *
     * <p>macOS → Seatbelt；Linux → Landlock（未验证，调用方自己负责要不要用）。
     *
     * @return 工厂
     * @throws UnsupportedOperationException 当前平台没有后端
     */
    public static PathScopeFactory factory() {
        if (isMac()) {
            return SeatbeltScope::new;
        }
        if (isLinux()) {
            return LandlockScope::new;
        }
        throw new UnsupportedOperationException(
                "sandbox: 当前平台没有可用的路径强制后端（macOS 用 Seatbelt、Linux 用 Landlock）");
    }

    /**
     * 探测当前机器**能不能真的把路径强制起来**。
     *
     * <p>「有后端」不等于「能用」：macOS 上 {@code sandbox-exec} 存在，但宿主策略拒绝应用沙箱
     * （{@code sandbox_apply: Operation not permitted}）——那时命令会失败，而且是运行期才发现。
     * 装配期探一次，就能把失败提前到启动时，并说清两条出路。
     *
     * <p>判据（与实测对齐）：退出码 0 = 可用；71 = 宿主不允许 apply；
     * 65 = profile 解析失败（实现缺陷）；其余 = 不可用（原因带退出码与输出）。
     *
     * @return 探测结果
     */
    public static PathScopeProbe.Result probe() {
        if (isMac()) {
            if (!Files.isExecutable(SANDBOX_EXEC)) {
                return PathScopeProbe.Result.unavailable(
                        "macOS Seatbelt 不可用（" + SANDBOX_EXEC + " 缺失或不可执行）");
            }
            return probeSeatbelt();
        }
        if (isLinux()) {
            return PathScopeProbe.Result.unavailable(
                    "Linux Landlock 后端代码在位但尚未在 Linux 上验证 → 默认不装配（不探测）");
        }
        return PathScopeProbe.Result.unavailable("Windows 后端未实现");
    }

    /**
     * 真的跑一次 {@code sandbox-exec}，问「内核收不收、宿主准不准 apply」。
     */
    private static PathScopeProbe.Result probeSeatbelt() {
        Process process;
        try {
            process = new ProcessBuilder(SANDBOX_EXEC.toString(), "-p", PROBE_PROFILE, PROBE_COMMAND)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return PathScopeProbe.Result.unavailable("探测失败（sandbox-exec 起不来）：" + e.getMessage());
        }
        boolean finished;
        try {
            finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return PathScopeProbe.Result.unavailable("探测被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            return PathScopeProbe.Result.unavailable("探测超时（" + PROBE_TIMEOUT_SECONDS + "s）：sandbox-exec 没有返回");
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            output = "";
        }
        int code = process.exitValue();
        if (code == 0) {
            return PathScopeProbe.Result.available("macOS Seatbelt 可用（sandbox-exec 探测通过）");
        }
        if (code == SEATBELT_APPLY_DENIED_EXIT) {
            return PathScopeProbe.Result.unavailable(
                    "宿主不允许应用 macOS 沙箱（sandbox_apply: Operation not permitted）");
        }
        if (code == SEATBELT_PARSE_ERROR_EXIT) {
            return PathScopeProbe.Result.unavailable(
                    "Seatbelt profile 解析失败（实现缺陷，退出码 65）：" + output);
        }
        return PathScopeProbe.Result.unavailable(
                "sandbox-exec 探测退出码 " + code + (output.isEmpty() ? "" : "：" + output));
    }

    static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux");
    }
}