package net.hegq.mos4j.context;

import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 + T4.2 接线：限流器把「截断 + 落盘提示」合成一段给模型看的内容。
 *
 * <p><b>硬上限是硬的</b>：模型最终看到的那段内容（含落盘提示）不得超过字节上限。
 */
class ToolOutputLimiterTest {

    private Path base;
    private SpillStore store;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "test-tmp", "limiter-" + System.nanoTime()).toAbsolutePath());
        Files.createDirectories(base.resolve("spill"));
        SandboxedFs fs = new SandboxedFs(RootSet.of(base));
        store = SpillStore.of(fs, "spill");
    }

    @Test
    @DisplayName("不超限：逐字原样返回，不落盘")
    void shortOutputIsUntouched() {
        ToolOutputLimiter limiter = ToolOutputLimiter.of(Truncation.defaultPolicy(), store);

        ToolOutputLimiter.LimitedOutput out = limiter.limit("read", "c1", "短内容");

        assertFalse(out.truncated());
        assertEquals("短内容", out.content());
        assertEquals(null, out.spillPath());
    }

    @Test
    @DisplayName("超限：模型看到的内容（含落盘提示）不超过字节硬上限")
    void theFinalContentRespectsTheHardCap() throws IOException {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(400).build();
        ToolOutputLimiter limiter = ToolOutputLimiter.of(truncation, store);
        String huge = "行内容".repeat(5000);

        ToolOutputLimiter.LimitedOutput out = limiter.limit("grep", "c1", huge);

        assertTrue(out.truncated());
        int bytes = out.content().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 400, "模型看到 " + bytes + " 字节，超过上限 400");
        assertTrue(out.content().contains("已落盘"), out.content());
        assertTrue(out.spillPath() != null && !out.spillPath().isBlank(), "落盘路径要给模型");
    }

    @Test
    @DisplayName("落盘的是完整内容：文件里必须有全文（不是截断版）")
    void theSpillFileHoldsTheFullContent() throws IOException {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(400).build();
        ToolOutputLimiter limiter = ToolOutputLimiter.of(truncation, store);
        String huge = "行内容".repeat(5000);

        ToolOutputLimiter.LimitedOutput out = limiter.limit("grep", "c1", huge);

        Path written = base.resolve(out.spillPath());
        assertTrue(Files.exists(written), out.spillPath());
        assertEquals(huge, Files.readString(written), "落盘必须是完整内容");
    }

    @Test
    @DisplayName("未装配落盘通道：内容仍守上限，降级写进 warnings（不在内容里加料）")
    void withoutSpillStoreTheCapStillHolds() {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(400).build();
        ToolOutputLimiter limiter = ToolOutputLimiter.of(truncation, null);

        ToolOutputLimiter.LimitedOutput out = limiter.limit("grep", "c1", "行内容".repeat(5000));

        int bytes = out.content().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 400, "模型看到 " + bytes + " 字节，超过上限 400");
        assertFalse(out.warnings().isEmpty(), "必须写明完整内容没保留");
        assertTrue(out.spillPath() == null, "没落盘就没有路径");
    }

    @Test
    @DisplayName("落盘失败：内容里明说没保存，warnings 也留原因")
    void spillFailureIsHonest() throws IOException {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(400).build();
        deleteRecursively(base.resolve("spill"));
        ToolOutputLimiter limiter = ToolOutputLimiter.of(truncation, store);

        ToolOutputLimiter.LimitedOutput out = limiter.limit("grep", "c1", "行内容".repeat(5000));

        assertTrue(out.content().contains("落盘失败"), out.content());
        assertFalse(out.warnings().isEmpty(), "必须写明降级原因");
        int bytes = out.content().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 400, "模型看到 " + bytes + " 字节，超过上限 400");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
        Files.delete(dir);
    }
}
