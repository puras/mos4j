package net.hegq.mos4j.session;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 会话场景（{@code docs/contract/L2-SCENARIOS.md}）逐条落地。
 *
 * <p>每条用例的名字就是场景号——场景清单是 J0 冻结的契约，测试是它的可执行副本。
 * 压缩器本身属 J4（T4.3–T4.6），此处只守「已有 compaction 事件的投影遮蔽」，
 * 以及压缩调用自身的用量计入。</p>
 */
class L2ScenariosTest {

    @Test
    @DisplayName("L2-3 日志不可改写：调用方改不动已入日志的事件")
    void logIsNotRewritable() throws IOException {
        Session session = new Session("s1", null);
        List<ToolCall> calls = new ArrayList<>();
        calls.add(new ToolCall("call_1", "read", "{}"));

        session.append(Event.builder(EventKind.ASSISTANT).toolCalls(calls).build());
        calls.add(new ToolCall("call_2", "read", "{}"));

        Event logged = session.events().get(0);
        assertEquals(1, logged.toolCalls().size(), "调用方事后追加不能改写日志里的事件");
        assertThrows(UnsupportedOperationException.class,
                () -> logged.toolCalls().add(new ToolCall("call_3", "x", "{}")));
    }

    @Test
    @DisplayName("L2-10 遮蔽不改写日志：被遮蔽的事件仍在日志里")
    void maskingDoesNotRewriteLog() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("老问题").build());
        session.append(Event.builder(EventKind.COMPACTION)
                .compaction(new Compaction("摘要", 5, 100)).build());

        assertEquals(2, session.events().size(), "被遮蔽的事件仍在日志里（append-only）");
        assertEquals("老问题", session.events().get(0).text());
        assertEquals(1, session.messages().size(), "但不再投影");
        assertTrue(session.messages().get(0).content().startsWith("[历史摘要]"));
    }

    @Test
    @DisplayName("L2-13 审批事件不进投影")
    void approvalEventsAreAuditOnly() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("问题").build());
        session.append(Event.builder(EventKind.APPROVAL_ASKED)
                .approval(new Approval("ap1", "write", "call_1", "要写文件", null)).build());
        session.append(Event.builder(EventKind.APPROVAL_DECIDED)
                .approval(new Approval("ap1", "write", "call_1", null, "approved")).build());
        session.append(Event.builder(EventKind.ASSISTANT).text("已批准并完成").build());

        List<Message> messages = session.messages();

        assertEquals(2, messages.size(), "审批事件只进日志，不占投影");
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals(4, session.events().size(), "但必须全量落日志");
    }

    @Test
    @DisplayName("L2-14 用量折叠：压缩调用自身的用量也计入")
    void compactionUsageCountsInTotals() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.ASSISTANT).usage(new Usage(10, 10, 2, 12, null)).build());
        session.append(Event.builder(EventKind.COMPACTION)
                .compaction(new Compaction("摘要", 1, 40))
                .usage(new Usage(100, 100, 30, 130, null))
                .build());

        Usage total = session.totalUsage();

        assertEquals(110, total.promptTokens(), "压缩调用的输入 token 也要计入");
        assertEquals(32, total.completionTokens());
        assertEquals(142, total.totalTokens());
    }

    @Test
    @DisplayName("L2-16 长会话：1000+ 事件投影结果正确")
    void longSessionProjectsCorrectly() throws IOException {
        Session session = new Session("s1", null);
        for (int i = 0; i < 600; i++) {
            session.append(Event.builder(EventKind.USER).turn(1).text("问题" + i).build());
            session.append(Event.builder(EventKind.ASSISTANT).turn(1).text("回答" + i).build());
        }

        List<Message> messages = session.messages();

        assertEquals(1200, messages.size());
        assertEquals("问题0", messages.get(0).content());
        assertEquals("回答599", messages.get(1199).content());
        assertEquals(1200, session.events().size());
    }

    @Test
    @DisplayName("L2-2 投影基本形态：tool 消息带 CallID，system 不进日志")
    void toolMessageCarriesCallId() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.ASSISTANT)
                .text("我读一下")
                .toolCalls(List.of(new ToolCall("call_1", "read", "{\"path\":\"a\"}")))
                .build());
        session.append(Event.builder(EventKind.TOOL_RESULT)
                .toolResult(new ToolResult("call_1", "内容", false)).build());

        List<Message> messages = session.messages();

        assertEquals(2, messages.size());
        assertEquals("call_1", messages.get(0).toolCalls().get(0).id());
        assertEquals("call_1", messages.get(1).toolCallId());
        assertEquals("内容", messages.get(1).content());
        assertFalse(session.events().stream().anyMatch(e -> e.kind() == EventKind.ASSISTANT
                && e.text() != null && e.text().startsWith("[system]")), "system 不进事件日志");
    }
}
