package net.hegq.mos4j.session;

import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.model.UsageDetails;
import net.hegq.mos4j.model.InputTokenDetails;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话行为测试：append 的 fail-fast 语义、序号、投影、用量折叠。
 */
class SessionTest {

    /** 永远写失败的持久化后端（模拟磁盘故障）。 */
    private static final class FailingPersistence implements Persistence {
        @Override
        public void saveHeader(String id, Header header) {
        }

        @Override
        public void append(String id, Event event) throws IOException {
            throw new IOException("磁盘满了");
        }

        @Override
        public List<Event> load(String id) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    @Test
    void appendAssignsStrictlyIncreasingSeq() throws IOException {
        Session session = new Session("s1", null);

        assertEquals(1L, session.append(Event.builder(EventKind.TURN_START).turn(1).build()).seq());
        assertEquals(2L, session.append(Event.builder(EventKind.USER).turn(1).text("hi").build()).seq());
        assertEquals(3L, session.append(Event.builder(EventKind.TURN_END).turn(1).reason("completed").build()).seq());
    }

    @Test
    void nextTurnFollowsLoggedTurns() throws IOException {
        Session session = new Session("s1", null);

        assertEquals(1, session.nextTurn());
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.TURN_END).turn(1).reason("completed").build());
        assertEquals(2, session.nextTurn());
    }

    @Test
    void persistFailureKeepsEventOutOfMemory() {
        Session session = new Session("s1", new FailingPersistence());

        assertThrows(IOException.class,
                () -> session.append(Event.builder(EventKind.USER).text("hi").build()));

        assertTrue(session.events().isEmpty(),
                "落盘失败 → 事件不进内存（顺序反了就会出现「看起来发生过但恢复后消失」）");
        assertEquals(0L, session.events().size());
    }

    @Test
    void eventsReturnsDefensiveCopy() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("hi").build());

        List<Event> copy = session.events();
        copy.clear();

        assertEquals(1, session.events().size(), "events() 必须返回防御性视图（L2-3）");
    }

    @Test
    void totalUsageFoldsAssistantEvents() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.ASSISTANT)
                .usage(new Usage(10, 10, 3, 13, new UsageDetails(new InputTokenDetails(2, 0, 0), null)))
                .build());
        session.append(Event.builder(EventKind.ASSISTANT)
                .usage(new Usage(20, 20, 5, 25, null))
                .build());

        Usage total = session.totalUsage();

        assertEquals(30, total.promptTokens());
        assertEquals(30, total.freshInputTokens());
        assertEquals(8, total.completionTokens());
        assertEquals(38, total.totalTokens());
        assertEquals(2, total.details().input().cacheRead(), "明细也累计（否则缓存命中掩在输入里）");
    }

    @Test
    void contextTokensUsesLastUsageAsBase() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.ASSISTANT)
                .usage(new Usage(10, 10, 3, 13, null))
                .build());
        session.append(Event.builder(EventKind.USER).text("0123456789").build());

        // 基座 = 最后一次用量的 total（13）+ 新增事件的 chars/4 估算（10/4 → 3）
        assertEquals(16, session.contextTokens());
    }

    @Test
    void messagesProjectUserAssistantAndToolResult() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("问题").build());
        session.append(Event.builder(EventKind.ASSISTANT).text("回答").build());
        session.append(Event.builder(EventKind.TOOL_RESULT)
                .toolResult(new ToolResult("call_1", "结果", false))
                .build());

        List<net.hegq.mos4j.model.Message> messages = session.messages();

        assertEquals(3, messages.size());
        assertEquals(net.hegq.mos4j.model.Role.USER, messages.get(0).role());
        assertEquals(net.hegq.mos4j.model.Role.ASSISTANT, messages.get(1).role());
        assertEquals(net.hegq.mos4j.model.Role.TOOL, messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertFalse(messages.get(2).toolCalls() != null && !messages.get(2).toolCalls().isEmpty());
    }
}
