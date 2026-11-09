package net.hegq.mos4j.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Landlock 启动器：一个「重新执行出来的自己」。
 *
 * <p>调用形状（由 {@link LandlockScope#wrap(List)} 拼出）：
 * {@code java … LandlockLauncher <base64 写根> <命令…>}。
 *
 * <p>它做的只有三件事：解析写根 → 给<b>本进程</b>套上 Landlock → 起命令并透传退出码。
 * 套上之后再起进程，约束由内核在 fork/exec 时继承，命令及其所有后代都在约束里。
 *
 * <p><b>fail-closed</b>：载荷坏了、内核不支持、套用失败，一律退出 {@link #FAIL_EXIT_CODE}
 * 并往 stderr 写原因——<b>绝不退化成「没隔离的假装隔离」</b>。
 */
public final class LandlockLauncher {

    /** 启动器自身失败时的退出码（与 shell 的 126/127 区分开）。 */
    public static final int FAIL_EXIT_CODE = 125;

    private LandlockLauncher() {
    }

    /**
     * 启动器入口。
     *
     * @param args {@code [base64 写根, 命令…]}
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            fail("载荷缺失：需要「写根 + 命令」");
        }
        List<String> roots;
        try {
            String joined = new String(Base64.getUrlDecoder().decode(args[0]), StandardCharsets.UTF_8);
            roots = joined.isEmpty() ? List.of() : List.of(joined.split("\n"));
        } catch (IllegalArgumentException e) {
            fail("写根载荷解不开: " + e.getMessage());
            return;
        }
        try {
            LandlockScope.applyToCurrentProcess(roots);
        } catch (RuntimeException | LinkageError e) {
            fail("套 Landlock 失败: " + e.getMessage());
            return;
        }

        List<String> command = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            command.add(args[i]);
        }
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            System.exit(process.waitFor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待命令时被中断");
        } catch (Exception e) {
            fail("起命令失败: " + e.getMessage());
        }
    }

    private static void fail(String message) {
        System.err.println("mos4j-runtime（Landlock）: " + message);
        System.exit(FAIL_EXIT_CODE);
    }
}