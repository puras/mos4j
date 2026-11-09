package net.hegq.mos4j.examples.minimal;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.sdk.Mos4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小示例的用例：证明示例里的装配代码**真的能跑**（用替身模型，不碰网络）。
 */
class MinimalExampleTest {

    private Path tempDir() throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", "example-minimal-" + System.nanoTime()));
    }

    @Test
    @DisplayName("装配 + 跑一轮：拿到模型回答，会话落盘")
    void assemblesAndAnswers() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("1+1=2"));

        String reply = MinimalExample.answer("只回答：1+1=?", model, dir.resolve("sessions"));

        assertEquals("1+1=2", reply);
        assertTrue(Files.exists(dir.resolve("sessions").resolve("minimal.jsonl")),
                "会话日志落盘（事件日志就是事实）");
        assertEquals(1, model.requests().size(), "只调了一次模型");
    }

    @Test
    @DisplayName("装配快照：工具装上了，关掉的边界也如实写出来")
    void snapshotIsHonest() throws Exception {
        Path dir = tempDir();
        Mos4j mos4j = MinimalExample.assemble(dir.resolve("sessions"), StubModel.of(StubModel.text("ok")));

        var describe = mos4j.agent().describe();

        assertTrue(describe.tools().contains("read"), "文件工具装上了: " + describe.tools());
        assertFalse(describe.caveats().isEmpty(),
                "关掉的路径强制必须出现在 caveats 里（假装有边界比没有边界更危险）");
        assertTrue(describe.caveats().stream().anyMatch(c -> c.contains("shell")),
                "caveats 要说清 shell 不受路径强制保护: " + describe.caveats());
    }
}
