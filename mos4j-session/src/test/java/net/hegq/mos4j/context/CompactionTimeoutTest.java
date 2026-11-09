package net.hegq.mos4j.context;

import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩的摘要超时（对位 Go 参考版 {@code CompactorConfig.SummaryTimeout}）。
 *
 * <p><b>为什么摘要必须自带超时</b>：宿主的轮次上下文通常只有「取消」没有「期限」。
 * 网关半开（上游挂起）时摘要请求会永远等下去，整轮不返回，宿主拿不到 {@code turn/end}——
 * 用户分不清是模型在思考、在限流重试，还是连接已经死了。
 */
class CompactionTimeoutTest {

    @Test
    @DisplayName("缺省超时是 3 分钟（与 Go 参考版同口径）")
    void defaultTimeoutIsThreeMinutes() {
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), new RecordingSummarizer());

        assertEquals(Duration.ofMinutes(3), service.summaryTimeout());
        assertEquals(Duration.ofMinutes(3), CompactionService.DEFAULT_SUMMARY_TIMEOUT);
    }

    @Test
    @DisplayName("withSummaryTimeout 返回新实例：策略与生成器不变（不可变）")
    void withSummaryTimeoutReturnsANewInstance() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), summarizer);

        CompactionService changed = service.withSummaryTimeout(Duration.ofSeconds(7));

        assertNotSame(service, changed);
        assertEquals(Duration.ofSeconds(7), changed.summaryTimeout());
        assertEquals(Duration.ofMinutes(3), service.summaryTimeout(), "原实例不受影响");
        assertEquals(service.policy(), changed.policy(), "策略不变");
    }

    @Test
    @DisplayName("零时长 = 不限（宿主自担挂住的风险）")
    void zeroMeansUnlimited() {
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), new RecordingSummarizer(), Duration.ZERO);

        assertTrue(service.summaryTimeout().isZero());
    }

    @Test
    @DisplayName("摘要超时：如实抛错，历史不被遮蔽")
    void summaryTimeoutFailsClosed() throws IOException {
        Session session = bigSession();
        SummaryGenerator hanging = (transcript, previousSummary) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return new SummaryGenerator.Summary("摘要", new Usage(1, 1, 1, 2, null));
        };
        CompactionService service = new CompactionService(
                CompactionPolicy.of(1000, 100, 0), hanging, Duration.ofMillis(50));

        long start = System.nanoTime();
        IOException error = assertThrows(IOException.class, () -> service.maybeCompact(session));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(error.getMessage().contains("超时"), error.getMessage());
        assertTrue(elapsedMillis < 5_000, "超时必须自己收尾，不能等摘要线程结束（实测 " + elapsedMillis + "ms）");
        assertEquals(0, session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).count(), "超时不能留下压缩事件");
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
