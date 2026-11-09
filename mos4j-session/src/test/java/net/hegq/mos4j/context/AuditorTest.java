package net.hegq.mos4j.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.9 任务状态与审计门：只有环境自证的事实才进状态，白名单是封闭集合。
 */
class AuditorTest {

    @Test
    @DisplayName("白名单里的工具：输出进任务状态")
    void trustedToolOutputBecomesFact() {
        Auditor auditor = Auditor.of(List.of("read"));
        TaskState state = TaskState.empty();

        Auditor.Decision decision = auditor.admit(state, "file", "内容", "read", "call_1");

        assertTrue(decision.accepted(), decision.reason());
        assertEquals("内容", decision.state().fact("file").orElseThrow().value());
        assertEquals("read", decision.state().fact("file").orElseThrow().toolName());
    }

    @Test
    @DisplayName("白名单外的工具：输出不进状态，理由写明")
    void untrustedToolOutputIsRejected() {
        Auditor auditor = Auditor.of(List.of("read"));
        TaskState state = TaskState.empty();

        Auditor.Decision decision = auditor.admit(state, "x", "模型自己说的", "ask_model", null);

        assertFalse(decision.accepted());
        assertEquals(0, decision.state().size(), "状态保持原样");
        assertTrue(decision.reason().contains("白名单"), decision.reason());
    }

    @Test
    @DisplayName("白名单不允许通配符（通配符等于没有门）")
    void whitelistRejectsWildcards() {
        assertThrows(IllegalArgumentException.class, () -> Auditor.of(List.of("*")));
        assertThrows(IllegalArgumentException.class, () -> Auditor.of(List.of("read*")));
    }

    @Test
    @DisplayName("默认白名单只含只读工具")
    void defaultsAreReadOnlyTools() {
        Auditor auditor = Auditor.defaults();

        assertTrue(auditor.admits("read"));
        assertFalse(auditor.admits("write"));
        assertFalse(auditor.admits("shell"));
    }

    @Test
    @DisplayName("任务状态不可变：追加事实不改动原状态")
    void taskStateIsImmutable() {
        TaskState state = TaskState.empty();

        TaskState next = state.with("k", "v", "read", "call_1");

        assertEquals(0, state.size());
        assertEquals(1, next.size());
    }
}
