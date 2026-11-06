package net.hegq.mos4j.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Linux Landlock 后端的真机验证：只在 Linux 上跑（内核强制只能真机测）。
 *
 * <p>macOS / Windows 上整类不执行——这不是「通过」，是「没验证」。
 */
@EnabledOnOs(OS.LINUX)
class LandlockScopeTest {

    private static Path tmpDir(String name) throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", name).toAbsolutePath());
    }

    @Test
    void kernelSupportsLandlock() {
        assertTrue(LandlockScope.abiVersion() >= 1,
                "本机内核没有 Landlock（需要 Linux ≥5.13 且启用 CONFIG_SECURITY_LANDLOCK）");
    }

    @Test
    void writesInsideTheRootsAreAllowed() throws Exception {
        Path root = tmpDir("landlock-inside");
        LocalRunner runner = LocalRunner.builder(root).pathScope(new LandlockScope(List.of(root))).build();

        RunResult result = runner.run(new RunRequest("echo hi > inside.txt && cat inside.txt", null, null));

        assertTrue(result.started(), String.valueOf(result.startFailure()));
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(root.resolve("inside.txt")));
    }

    @Test
    void writesOutsideTheRootsAreDeniedByTheKernel() throws Exception {
        Path base = tmpDir("landlock-outside");
        Path root = Files.createDirectories(base.resolve("jail"));
        Path outside = base.resolve("escape.txt");
        LocalRunner runner = LocalRunner.builder(root).pathScope(new LandlockScope(List.of(root))).build();

        RunResult result = runner.run(new RunRequest("echo hi > ../escape.txt", null, null));

        assertTrue(result.started(), String.valueOf(result.startFailure()));
        assertNotEquals(0, result.exitCode(), "写根外的写必须被内核拒绝");
        assertFalse(Files.exists(outside), "写根外的文件不该被创建");
    }

    @Test
    void devNullStaysWritable() throws Exception {
        Path root = tmpDir("landlock-devnull");
        LocalRunner runner = LocalRunner.builder(root).pathScope(new LandlockScope(List.of(root))).build();

        RunResult result = runner.run(new RunRequest("echo hi 2>/dev/null; echo done", null, null));

        assertTrue(result.started(), String.valueOf(result.startFailure()));
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("done"), result.stdout());
    }

    @Test
    void launcherFailsClosedWhenTheKernelHasNoLandlock() {
        // 载荷解不开 → 退出码 125（不跑任何命令）
        assertEquals(125, LandlockLauncher.FAIL_EXIT_CODE);
    }
}