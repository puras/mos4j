package net.hegq.mos4j.examples.recovery;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恢复示例的用例：派生现场是纯读、续跑显式、续跑回填真实结果。
 */
class RecoveryExampleTest {

    private Path tempDir() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "test-tmp", "example-recovery-" + System.nanoTime()));
        Files.createDirectories(dir.resolve("workspace"));
        Files.createDirectories(dir.resolve("sessions"));
        return dir;
    }

    @Test
    @DisplayName("派生：认得出被中断的轮次与悬空调用（纯读）")
    void derivesTheScene() throws Exception {
        Path dir = tempDir();
        Mos4j mos4j = RecoveryExample.assemble(dir.resolve("sessions"),
                StubModel.of(StubModel.text("读完了")), dir.resolve("workspace"));
        RecoveryExample.crashHalfway(mos4j, dir.resolve("workspace"));

        String scene = RecoveryExample.describe(mos4j);

        assertTrue(scene.contains("被中断的轮次=1"), scene);
        assertTrue(scene.contains("悬空调用: c1"), scene);
    }

    @Test
    @DisplayName("派生是纯读：问多少次现场都不变，日志也不多写一行")
    void derivationIsPure() throws Exception {
        Path dir = tempDir();
        Mos4j mos4j = RecoveryExample.assemble(dir.resolve("sessions"),
                StubModel.of(StubModel.text("读完了")), dir.resolve("workspace"));
        RecoveryExample.crashHalfway(mos4j, dir.resolve("workspace"));

        long before = Files.readAllBytes(
                dir.resolve("sessions").resolve(RecoveryExample.SESSION_ID + ".jsonl")).length;
        RecoveryExample.describe(mos4j);
        RecoveryExample.describe(mos4j);
        long after = Files.readAllBytes(
                dir.resolve("sessions").resolve(RecoveryExample.SESSION_ID + ".jsonl")).length;

        assertEquals(before, after, "派生读不写日志");
    }

    @Test
    @DisplayName("续跑：沿用原轮次、回填真实结果、那一轮正常收尾")
    void resumeBackfillsRealResults() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("清单是：构建 → 冒烟 → 灰度"));
        Mos4j mos4j = RecoveryExample.assemble(dir.resolve("sessions"), model, dir.resolve("workspace"));
        RecoveryExample.crashHalfway(mos4j, dir.resolve("workspace"));

        String reply = RecoveryExample.resume(mos4j);

        assertEquals("清单是：构建 → 冒烟 → 灰度", reply);
        List<Event> events = mos4j.session(RecoveryExample.SESSION_ID).events();
        Event result = events.stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有回填结果: " + events));
        assertEquals("c1", result.toolResult().callId());
        assertFalse(result.toolResult().isError(), "回填的是真实结果，不是合成的失败回执");
        assertTrue(result.toolResult().content().contains("部署清单"), result.toolResult().content());
        assertEquals(1, result.turn(), "沿用原轮次号");

        Event end = events.stream()
                .filter(e -> e.kind() == EventKind.TURN_END)
                .findFirst()
                .orElseThrow(() -> new AssertionError("那一轮没收尾: " + events));
        assertEquals("completed", end.reason());
        assertEquals(1, end.turn());
    }
}
