package net.hegq.mos4j.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源上限（{@code ulimit} 口径）。
 *
 * <p><b>macOS 要跳过 {@code -v}</b>：BSD 的 {@code ulimit -v} 在 macOS 上不是虚拟内存上限，
 * 设了会直接报 {@code cannot modify limit: Invalid argument}——不是「限制生效」，是「限制没设上」。
 * 所以这里按平台生成命令，并把「哪些生效、哪些被跳过」如实报出来（{@link #applied()} / {@link #skipped()}），
 * 由 {@code Describe} 交给部署方，不藏着。
 *
 * @param cpuSeconds     CPU 时间上限秒数（-1 表示不限）
 * @param virtualMemoryKb 虚拟内存上限 KB（null 表示不设）
 * @param fileSizeBlocks 单文件大小上限块数（-1 表示不限）
 */
public record ResourceLimits(int cpuSeconds, Long virtualMemoryKb, Long fileSizeBlocks) {

    /** 不设任何上限。 */
    public static ResourceLimits none() {
        return new ResourceLimits(-1, null, -1L);
    }

    /**
     * 只有 CPU 与文件大小上限（跨平台都能用的那部分）。
     *
     * @param cpuSeconds     CPU 秒数
     * @param fileSizeBlocks 文件大小块数
     * @return 上限
     */
    public static ResourceLimits of(int cpuSeconds, long fileSizeBlocks) {
        return new ResourceLimits(cpuSeconds, null, fileSizeBlocks);
    }

    /**
     * 是否什么都没设。
     *
     * @return true 表示无上限
     */
    public boolean isEmpty() {
        return cpuSeconds < 0 && virtualMemoryKb == null && (fileSizeBlocks == null || fileSizeBlocks < 0);
    }

    /**
     * 生成 shell 片段。
     *
     * @return 例如 {@code ulimit -t 5; ulimit -f 100}
     */
    public String shellSnippet() {
        List<String> parts = new ArrayList<>();
        if (cpuSeconds >= 0) {
            parts.add("ulimit -t " + cpuSeconds);
        }
        if (virtualMemoryKb != null && virtualMemoryKb > 0 && !skipVirtualMemory()) {
            parts.add("ulimit -v " + virtualMemoryKb);
        }
        if (fileSizeBlocks != null && fileSizeBlocks >= 0) {
            parts.add("ulimit -f " + fileSizeBlocks);
        }
        return String.join("; ", parts);
    }

    /**
     * 实际生效的上限说明。
     *
     * @return 生效项
     */
    public List<String> applied() {
        List<String> applied = new ArrayList<>();
        if (cpuSeconds >= 0) {
            applied.add("cpu=" + cpuSeconds + "s");
        }
        if (virtualMemoryKb != null && virtualMemoryKb > 0 && !skipVirtualMemory()) {
            applied.add("virtualMemory=" + virtualMemoryKb + "KB");
        }
        if (fileSizeBlocks != null && fileSizeBlocks >= 0) {
            applied.add("fileSize=" + fileSizeBlocks + "blocks");
        }
        return applied;
    }

    /**
     * 被跳过的上限说明（macOS 的 {@code -v}）。
     *
     * @return 跳过项
     */
    public List<String> skipped() {
        List<String> skipped = new ArrayList<>();
        if (virtualMemoryKb != null && virtualMemoryKb > 0 && skipVirtualMemory()) {
            skipped.add("virtualMemory=" + virtualMemoryKb + "KB（macOS 的 ulimit -v 不适用，已跳过）");
        }
        return skipped;
    }

    private static boolean skipVirtualMemory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }
}
