package net.hegq.mos4j.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公共底座 {@link TaskHandle} / {@link TaskStatus} 的守护测试。
 *
 * <p>守护三条：终态判定（UNKNOWN 不算终态——不猜）、失败必带原因、成功不带原因、
 * 产物列表不可变。
 */
class TaskHandleTest {

    @Test
    void pendingAndRunningAreNotTerminal() {
        assertFalse(TaskHandle.pending("t-1").terminal());
        assertFalse(TaskHandle.running("t-1").terminal());
    }

    @Test
    void succeededIsTerminalAndKeepsArtifacts() {
        Artifact artifact = new Artifact.InMemory(new byte[]{1}, "image/png", "x.png");

        TaskHandle handle = TaskHandle.succeeded("t-1", List.of(artifact));

        assertTrue(handle.terminal());
        assertTrue(handle.status().successful());
        assertEquals(1, handle.artifacts().size());
        assertEquals(artifact, handle.primaryArtifact().orElseThrow());
    }

    @Test
    void failedKeepsReasonAndIsTerminal() {
        TaskHandle handle = TaskHandle.failed("t-1", "配额不足");

        assertTrue(handle.terminal());
        assertEquals(TaskStatus.FAILED, handle.status());
        assertEquals("配额不足", handle.errorMessage());
    }

    @Test
    void failedWithoutReasonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TaskHandle.failed("t-1", "  "));
        assertThrows(IllegalArgumentException.class, () -> TaskHandle.failed("t-1", null));
    }

    @Test
    void succeededWithReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskHandle("t-1", TaskStatus.SUCCEEDED, List.of(), "不该有的原因"));
    }

    @Test
    void blankTaskIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TaskHandle.pending(" "));
        assertThrows(IllegalArgumentException.class, () -> TaskHandle.pending(null));
    }

    @Test
    void unknownStatusIsNotTerminal() {
        TaskHandle handle = new TaskHandle("t-1", TaskStatus.UNKNOWN, List.of(), null);

        assertFalse(handle.terminal(), "不认识的状态不猜成终态");
        assertFalse(handle.status().successful());
    }

    @Test
    void artifactsAreCopied() {
        List<Artifact> source = new ArrayList<>();
        source.add(new Artifact.InMemory(new byte[]{1}, "image/png", "x.png"));

        TaskHandle handle = new TaskHandle("t-1", TaskStatus.RUNNING, source, null);
        source.clear();

        assertEquals(1, handle.artifacts().size(), "外部改原列表不应影响句柄");
    }

    @Test
    void primaryArtifactIsEmptyWhenThereIsNone() {
        assertTrue(TaskHandle.running("t-1").primaryArtifact().isEmpty());
    }
}
