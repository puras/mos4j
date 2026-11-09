package net.hegq.mos4j.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地运行器测试（T3.6、T3.7）。
 *
 * <p>重点：宿主固定的边界（工作目录、环境、超时上界、输出上限）真的落到进程上。
 */
class LocalRunnerTest {

    private Path workDir;
    private LocalRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createDirectories(
                Path.of("target", "test-tmp", "sandbox-" + System.nanoTime()).toAbsolutePath());
        runner = LocalRunner.builder(workDir)
                .defaultTimeout(Duration.ofSeconds(10))
                .maxTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Test
    void normalCommandRuns() {
        RunResult result = runner.run(RunRequest.of("echo hello"));

        assertTrue(result.started());
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout().trim());
        assertFalse(result.timedOut());
    }

    @Test
    void nonZeroExitCodeIsAResultNotAnError() {
        RunResult result = runner.run(RunRequest.of("exit 3"));

        assertTrue(result.started());
        assertEquals(3, result.exitCode());
        assertFalse(result.timedOut());
        assertEquals(null, result.startFailure());
    }

    @Test
    void workingDirectoryIsFixedByHost() {
        RunResult result = runner.run(RunRequest.of("pwd"));

        assertEquals(workDir.toString(), result.stdout().trim());
    }

    @Test
    void modelCannotOverrideWorkingDirectory() {
        // 请求里没有工作目录字段：模型给不了，也落不下去
        RunRequest request = new RunRequest("pwd", null, null);

        RunResult result = runner.run(request);

        assertEquals(workDir.toString(), result.stdout().trim());
    }

    @Test
    void environmentIsTrimmedToTheAllowlist() {
        runner = LocalRunner.builder(workDir)
                .inheritEnvironment(java.util.Map.of("PATH", "/bin", "MOS4J_SECRET", "s3cret"), List.of("PATH"))
                .build();

        RunResult result = runner.run(RunRequest.of("echo \"[$MOS4J_SECRET]\""));

        assertEquals("[]", result.stdout().trim());
        assertFalse(runner.environment().containsKey("MOS4J_SECRET"));
    }

    @Test
    void explicitlyAllowedEnvironmentReachesTheChild() {
        runner = LocalRunner.builder(workDir).env("MOS4J_ALLOWED", "yes").build();

        RunResult result = runner.run(RunRequest.of("echo \"[$MOS4J_ALLOWED]\""));

        assertEquals("[yes]", result.stdout().trim());
    }

    @Test
    void pathIsKeptSoCommandsCanStart() {
        RunResult result = runner.run(RunRequest.of("echo $PATH"));

        assertFalse(result.stdout().trim().isEmpty());
    }

    @Test
    void timeoutKillsTheCommand() {
        LocalRunner quick = LocalRunner.builder(workDir)
                .defaultTimeout(Duration.ofMillis(300))
                .maxTimeout(Duration.ofSeconds(5))
                .build();

        RunResult result = quick.run(RunRequest.of("sleep 30"));

        assertTrue(result.timedOut());
        assertTrue(result.duration().compareTo(Duration.ofSeconds(10)) < 0,
                "耗时应该远小于命令本身的 30 秒，实际 " + result.duration());
    }

    @Test
    void requestedTimeoutIsCappedByHostMaximum() {
        LocalRunner capped = LocalRunner.builder(workDir)
                .defaultTimeout(Duration.ofSeconds(5))
                .maxTimeout(Duration.ofSeconds(2))
                .build();

        Duration effective = capped.effectiveTimeout(new RunRequest("echo x", Duration.ofHours(1), null));

        assertEquals(Duration.ofSeconds(2), effective);
    }

    @Test
    void defaultTimeoutIsUsedWhenRequestHasNone() {
        LocalRunner quick = LocalRunner.builder(workDir)
                .defaultTimeout(Duration.ofMillis(700))
                .maxTimeout(Duration.ofSeconds(5))
                .build();

        assertEquals(Duration.ofMillis(700), quick.effectiveTimeout(RunRequest.of("echo x")));
    }

    @Test
    void outputIsCappedAndTruncationIsReported() {
        LocalRunner capped = LocalRunner.builder(workDir).maxOutputBytes(100).build();

        RunResult result = capped.run(RunRequest.of("head -c 5000 /dev/zero | tr '\\0' 'x'"));

        assertTrue(result.truncated());
        assertEquals(100, result.stdout().length());
    }

    @Test
    void requestedOutputCapIsCappedByHostMaximum() {
        LocalRunner capped = LocalRunner.builder(workDir).maxOutputBytes(100).build();

        assertEquals(100, capped.effectiveCap(new RunRequest("echo x", null, 999999)));
    }

    @Test
    void resourceLimitsAreApplied() {
        LocalRunner limited = LocalRunner.builder(workDir)
                .limits(ResourceLimits.of(3, 1L))
                .build();

        RunResult result = limited.run(RunRequest.of("ulimit -f; ulimit -t"));

        assertTrue(result.stdout().contains("1"), "文件大小上限应为 1 块，实际: " + result.stdout());
        assertTrue(result.stdout().contains("3"), "CPU 上限应为 3 秒，实际: " + result.stdout());
    }

    @Test
    void unknownCommandIsANormalNonZeroResult() {
        // shell 起来了并报 127：这属于「命令没找到」，仍是正常结果（不是启动失败）
        RunResult result = runner.run(RunRequest.of("definitely-not-a-real-command-xyz"));

        assertTrue(result.started());
        assertEquals(127, result.exitCode());
    }
}
