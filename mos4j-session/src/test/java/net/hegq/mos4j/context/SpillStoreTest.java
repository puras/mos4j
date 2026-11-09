package net.hegq.mos4j.context;

import net.hegq.mos4j.fs.PathDeniedError;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.2 截断落盘逃生通道：目录必须在 jail 内，落盘失败诚实降级。
 */
class SpillStoreTest {

    private Path base;
    private SandboxedFs fs;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "test-tmp", "spill-" + System.nanoTime()).toAbsolutePath());
        Files.createDirectories(base.resolve("spill"));
        fs = new SandboxedFs(RootSet.of(base));
    }

    @Test
    @DisplayName("落盘成功：内容一致，路径可回读")
    void spillWritesTheContent() throws IOException {
        SpillStore store = SpillStore.of(fs, "spill");

        SpillResult result = store.spill("output", "很长很长的一段内容");

        assertTrue(result.spilled(), result.warnings().toString());
        Path written = base.resolve(result.path());
        assertTrue(Files.exists(written), result.path());
        assertEquals("很长很长的一段内容", Files.readString(written));
    }

    @Test
    @DisplayName("落盘目录越界：构造期就报错（fail-closed）")
    void spillDirOutsideTheJailIsRejected() {
        assertThrows(PathDeniedError.class, () -> SpillStore.of(fs, "../escape"));
    }

    @Test
    @DisplayName("落盘失败：诚实降级，内容原样返回")
    void spillFailureIsReportedHonestly() throws IOException {
        SpillStore store = SpillStore.of(fs, "spill");
        // 把目录删掉：写入必然失败
        deleteRecursively(base.resolve("spill"));

        SpillResult result = store.spill("output", "内容不能丢");

        assertFalse(result.spilled());
        assertEquals("内容不能丢", result.content(), "落盘失败也不能把内容丢了");
        assertFalse(result.warnings().isEmpty(), "必须写明降级原因");
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
