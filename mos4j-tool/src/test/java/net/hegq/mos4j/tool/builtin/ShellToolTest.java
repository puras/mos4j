package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shell 工具测试（T3.11；L3-18：非零退出码不是错误）。
 */
class ShellToolTest {

    private Path workDir;
    private ShellTool tool;

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createDirectories(
                Path.of("target", "test-tmp", "shell-" + System.nanoTime()).toAbsolutePath());
        tool = new ShellTool(LocalRunner.builder(workDir)
                .defaultTimeout(Duration.ofSeconds(10))
                .maxTimeout(Duration.ofSeconds(20))
                .build());
    }

    @Test
    void l3_18_nonZeroExitIsNotAnError() throws Exception {
        Object result = tool.execute("{\"command\":\"exit 1\"}");

        String output = result.toString();
        assertTrue(output.endsWith("[exit code: 1]"), output);
        assertTrue(output.contains("[exit code: 1]"), output);
    }

    @Test
    void l3_18_successExitIsAlsoMarked() throws Exception {
        Object result = tool.execute("{\"command\":\"echo hi\"}");

        String output = result.toString();
        assertTrue(output.contains("hi"), output);
        assertTrue(output.endsWith("[exit code: 0]"), output);
    }

    @Test
    void l3_18_timeoutIsAnError() {
        ToolException error = assertThrows(ToolException.class,
                () -> tool.execute("{\"command\":\"sleep 30\",\"timeout\":1}"));

        assertTrue(error.getMessage().contains("超时"), error.getMessage());
    }

    @Test
    void workingDirectoryIsTheHostOne() throws Exception {
        Object result = tool.execute("{\"command\":\"pwd\"}");

        assertTrue(result.toString().contains(workDir.toString()), result.toString());
    }

    @Test
    void shellToolRequiresARunner() {
        // 没有 Runner 就造不出 shell 工具：沙箱未启用 = 工具不存在，而不是「裸奔」
        assertThrows(IllegalArgumentException.class, () -> new ShellTool(null));
    }

    @Test
    void missingCommandIsReported() {
        assertThrows(ToolException.class, () -> tool.execute("{}"));
    }

    @Test
    void descriptionIsEchoedForAudit() throws Exception {
        Object result = tool.execute("{\"command\":\"echo hi\",\"description\":\"打印问候\"}");

        String output = result.toString();
        assertTrue(output.contains("说明：打印问候"), output);
        // 退出码标记仍留在末尾（审计与模型都按它判断成败）
        assertTrue(output.endsWith("[exit code: 0]"), output);
    }

    @Test
    void withoutDescriptionTheOutputIsUnchanged() throws Exception {
        Object result = tool.execute("{\"command\":\"echo hi\"}");

        assertTrue(result.toString().startsWith("hi"), result.toString());
    }
}
