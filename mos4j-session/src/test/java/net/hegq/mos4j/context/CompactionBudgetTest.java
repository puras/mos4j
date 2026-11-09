package net.hegq.mos4j.context;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.session.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.3 压缩触发（机制 M8 / 不变式 I4）。
 *
 * <p>对应 L2 场景：L2-9（预算触发）、L2-10（遮蔽不改写日志）。
 */
class CompactionBudgetTest {

    @Test
    @DisplayName("L2-9 超预算触发压缩，tokensBefore 记录压缩前估算")
    void overBudgetTriggersCompaction() throws IOException {
        Session session = bigSession();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), new RecordingSummarizer());

        CompactionResult result = service.maybeCompact(session);

        assertTrue(result.compacted(), result.warnings().toString());
        assertTrue(result.tokensBefore() > 900, "tokensBefore=" + result.tokensBefore());
        assertTrue(result.firstKeptSeq() > 0, "保留段首条事件序号必须大于 0");
    }

    @Test
    @DisplayName("未超预算不压缩")
    void withinBudgetDoesNotCompact() throws IOException {
        Session session = bigSession();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(100_000, 100, 0), new RecordingSummarizer());

        CompactionResult result = service.maybeCompact(session);

        assertFalse(result.compacted());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未超预算")), result.warnings().toString());
    }

    @Test
    @DisplayName("未配置上下文窗口：永不触发（不拿猜的窗口做决定）")
    void withoutContextWindowNeverCompacts() throws IOException {
        Session session = bigSession();
        CompactionService service = new CompactionService(
                new CompactionPolicy(null, 100, 0), new RecordingSummarizer());

        CompactionResult result = service.maybeCompact(session);

        assertFalse(result.compacted());
        assertEquals(0, session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).count());
    }

    @Test
    @DisplayName("L2-10 遮蔽不改写日志：被遮蔽的事件仍在日志里，只是不投影")
    void maskingDoesNotRewriteTheLog() throws IOException {
        Session session = bigSession();
        int eventsBefore = session.events().size();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), new RecordingSummarizer());

        service.maybeCompact(session);

        assertEquals(eventsBefore + 1, session.events().size(), "只追加了一条压缩事件");
        assertEquals(1, session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).count());

        List<Message> messages = session.messages();
        assertTrue(messages.get(0).content().startsWith("[历史摘要]"), "摘要在最前");
        assertEquals(2, messages.size(), "只剩摘要 + 保留段");
        assertEquals("新问题", messages.get(1).content());
        assertEquals("老问题", session.events().get(0).text(), "但日志里还在");
    }

    @Test
    @DisplayName("压缩调用自身的用量计入会话总量")
    void compactionUsageIsCounted() throws IOException {
        Session session = bigSession();
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.usage = new Usage(100, 100, 20, 120, null);
        int before = session.totalUsage().totalTokens();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), summarizer);

        service.maybeCompact(session);

        assertEquals(before + 120, session.totalUsage().totalTokens());
    }

    @Test
    @DisplayName("摘要为空：不遮蔽历史（压了却没摘要 = 旧历史彻底消失）")
    void emptySummaryDoesNotMaskTheHistory() throws IOException {
        Session session = bigSession();
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.text = "   ";
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), summarizer);

        CompactionResult result = service.maybeCompact(session);

        assertFalse(result.compacted());
        assertEquals(0, session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).count());
    }

    @Test
    @DisplayName("摘要生成异常：如实抛出，历史不被遮蔽")
    void summaryFailurePropagates() throws IOException {
        Session session = bigSession();
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.failure = new IllegalStateException("模型挂了");
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), summarizer);

        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> service.maybeCompact(session));

        assertTrue(error.getMessage().contains("摘要生成异常"), error.getMessage());
        assertEquals(0, session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).count());
    }

    /**
     * 一个「上下文已经很大」的会话：最后一条 assistant 带真实用量。
     */
    private static Session bigSession() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("老问题").build());
        session.append(Event.builder(EventKind.ASSISTANT)
                .text("老回答")
                .usage(new Usage(800, 800, 100, 900, null))
                .build());
        session.append(Event.builder(EventKind.USER).text("新问题").build());
        return session;
    }
}
