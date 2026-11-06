package net.hegq.mos4j.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 路径强制的平台工厂。
 *
 * <p><b>后端现状</b>：macOS = Seatbelt（{@code sandbox-exec}）、Linux = Landlock LSM
 * （{@link LandlockScope}，JDK FFM 直调）；**Windows 尚未落地**（低完整性 token + Job Object）——
 * 无后端的平台 {@link #create(Path)} <b>明确报错</b>，不做假降级（部署方不能以为路径被强制了）。
 *
 * <p><b>缺省范围与 Go 对齐</b>：{@link #derive(Path)} 从工作目录派生（写根 = 工作目录），
 * 对应 Go 的 {@code deriveScope(WorkDir)}——路径强制不是「宿主可选打开的功能」，
 * 有后端就该默认生效。
 */
public final class PathScopes {

    /** macOS 自带的沙箱执行器。 */
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    private PathScopes() {
    }

    /**
     * 当前平台是否有可用后端。
     *
     * @return true 表示有
     */
    public static boolean supported() {
        return isMac() && Files.isExecutable(SANDBOX_EXEC);
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
            return new LandlockScope(List.of(baseWriteRoot));
        }
        throw new UnsupportedOperationException(
                "sandbox: 当前平台没有可用的路径强制后端（macOS 用 Seatbelt、Linux 用 Landlock；"
                        + "确认放开请在宿主显式声明写根 \"/\"）");
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