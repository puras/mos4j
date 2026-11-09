package net.hegq.mos4j.session;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投影自愈测试（机制 M4）：孤儿调用补合成中断回执、序位与压缩遮蔽。
 */
class ProjectionTest {

    private static Event event(long seq, EventKind kind, String text, List<ToolCall> calls) {
        return new Event(seq, kind, 0, "2026-11-03T00:00:00Z", text, null, calls, null,
                null, null, null, null, null, null, null);
    }

    private static Event assistant(long seq, List<ToolCall> calls) {
        return event(seq, EventKind.ASSISTANT, "思考", calls);
    }

    @Test
    void orphanToolCallGetsSyntheticReceipt() {
        List<Event> events = List.of(
                event(1L, EventKind.USER, "问题", null),
                assistant(2L, List.of(new ToolCall("call_1", "read", "{\"path\":\"a\"}"))));

        List<Message> messages = Projection.project(events);

        assertEquals(3, messages.size(), "user + assistant + 合成回执");
        Message receipt = messages.get(2);
        assertEquals(Role.TOOL, receipt.role());
        assertEquals("call_1", receipt.toolCallId());
        assertEquals("（read 调用在执行期间被中断，没有结果；如仍需要请重新调用）", receipt.content());
    }

    @Test
    void receiptFollowsAssistantSequence() {
        List<Event> events = List.of(
                event(1L, EventKind.USER, "问题", null),
                assistant(2L, List.of(new ToolCall("call_1", "read", "{}"))));

        List<Projection.SeqMessage> view = Projection.projectSeq(events);

        Projection.SeqMessage receipt = view.get(2);
        assertEquals(2L, receipt.seq(), "合成回执继承声明它的 assistant 事件序号（L2-7）");
    }

    @Test
    void multipleOrphansGetOneReceiptEachInDeclarationOrder() {
        List<Event> events = List.of(
                assistant(1L, List.of(new ToolCall("call_1", "a", "{}"), new ToolCall("call_2", "b", "{}"))));

        List<Message> messages = Projection.project(events);

        assertEquals(3, messages.size());
        assertEquals("call_1", messages.get(1).toolCallId());
        assertEquals("call_2", messages.get(2).toolCallId());
    }

    @Test
    void answeredCallDoesNotGetReceipt() {
        List<Event> events = List.of(
                assistant(1L, List.of(new ToolCall("call_1", "read", "{}"))),
                new Event(2L, EventKind.TOOL_RESULT, 0, "2026-11-03T00:00:00Z", null, null, null, null,
                        new ToolResult("call_1", "文件内容", false), null, null, null, null, null, null));

        List<Message> messages = Projection.project(events);

        assertEquals(2, messages.size(), "有回执就不补合成回执");
        assertEquals("文件内容", messages.get(1).content());
    }

    @Test
    void compactionMasksOldHistoryAndInjectsSummary() {
        List<Event> events = List.of(
                event(1L, EventKind.USER, "老问题", null),
                assistant(2L, List.of(new ToolCall("call_1", "read", "{}"))),
                new Event(3L, EventKind.COMPACTION, 0, "2026-11-03T00:00:00Z", null, null, null, null,
                        null, null, null, null, null, new Compaction("摘要内容", 2L, 100), null));

        List<Message> messages = Projection.project(events);

        assertEquals(3, messages.size(), "遮蔽线之前的消息不再投影；调用方与它的回执同生共死");
        assertEquals(Role.USER, messages.get(0).role());
        assertTrue(messages.get(0).content().startsWith("[历史摘要]"), messages.get(0).content());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals(Role.TOOL, messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
    }

    @Test
    void compactionBeforeCallerMasksReceiptToo() {
        // 遮蔽线落在孤儿调用方之前 → 回执与调用方一起被遮蔽（同生共死）
        List<Event> events = List.of(
                assistant(1L, List.of(new ToolCall("call_1", "read", "{}"))),
                new Event(2L, EventKind.COMPACTION, 0, "2026-11-03T00:00:00Z", null, null, null, null,
                        null, null, null, null, null, new Compaction("摘要", 5L, 0), null));

        List<Message> messages = Projection.project(events);

        assertEquals(1, messages.size(), "回执与调用方一起被遮蔽");
        assertTrue(messages.get(0).content().startsWith("[历史摘要]"));
    }
}
