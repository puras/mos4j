package net.hegq.mos4j.context;

import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Projection;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.session.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.4 切割点合法性（机制 M8；L2 场景 L2-11）。
 *
 * <p><b>切割点永不落在 tool 结果上</b>：保留段不能以 tool 结果开头，
 * 否则投影出来的历史是「有回执、没有调用」——服务商直接拒收整段对话。
 */
class CompactionCutPointTest {

    @Test
    @DisplayName("L2-11 切割点落在 tool 结果上时，自动前移到声明它的 assistant")
    void cutPointNeverLandsOnAToolResult() throws IOException {
        Session session = historyWithToolResult();
        List<Projection.SeqMessage> view = session.view();

        // 先算出「朴素切割点」：按累计 token 落在 tool 结果上
        int naive = naiveCut(view, keepRecentTokensThatLandsOnToolResult(view));
        assertEquals(2, naive, "构造前提：朴素切割点确实落在 tool 结果上（视图下标 2）");

        CutPointFinder.Cut cut = CutPointFinder.find(view, keepRecentTokensThatLandsOnToolResult(view));

        assertEquals(1, cut.index(), "切割点必须前移到声明它的 assistant");
        assertEquals(2L, cut.firstKeptSeq(), "assistant 事件的序号");
    }

    @Test
    @DisplayName("L2-11 压缩后投影始终合法：每条 tool 结果都有声明它的 assistant")
    void projectionStaysValidAfterCompaction() throws IOException {
        Session session = historyWithToolResult();
        CompactionService service = new CompactionService(
                CompactionPolicy.of(5, 0, keepRecentTokensThatLandsOnToolResult(session.view())),
                new RecordingSummarizer());

        CompactionResult result = service.maybeCompact(session);

        assertTrue(result.compacted(), result.warnings().toString());
        assertProjectionIsLegal(session.messages());
    }

    @Test
    @DisplayName("保留段以 assistant 开头时，切割点不动")
    void cutPointStaysWhenTheKeptSegmentStartsWithAssistant() throws IOException {
        Session session = historyWithToolResult();
        List<Projection.SeqMessage> view = session.view();

        CutPointFinder.Cut cut = CutPointFinder.find(view, 1);

        assertEquals(4, cut.index(), "保留段从最后一条 assistant 开始");
        assertEquals(Role.ASSISTANT, view.get(cut.index()).message().role());
    }

    /**
     * 断言投影合法：每条 tool 消息都能找到声明它的 assistant tool_call。
     */
    private static void assertProjectionIsLegal(List<Message> messages) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (Message message : messages) {
            if (message.role() == Role.ASSISTANT && message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    declared.add(call.id());
                }
            }
            if (message.role() == Role.TOOL) {
                assertTrue(declared.contains(message.toolCallId()),
                        "tool 结果没有声明它的 assistant: " + message.toolCallId());
            }
        }
    }

    /**
     * 会话：用户问题 → assistant 调工具 → tool 结果 → 用户追问 → assistant 回答。
     */
    private static Session historyWithToolResult() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("问题").build());
        session.append(Event.builder(EventKind.ASSISTANT)
                .toolCalls(List.of(new ToolCall("call_1", "read", "{\"path\":\"a.txt\"}")))
                .build());
        session.append(Event.builder(EventKind.TOOL_RESULT)
                .toolResult(new ToolResult("call_1", "文件内容", false))
                .build());
        session.append(Event.builder(EventKind.USER).text("追问").build());
        session.append(Event.builder(EventKind.ASSISTANT).text("回答").build());
        return session;
    }

    /**
     * 找一个让朴素切割点落在 tool 结果上的保留 token 数。
     *
     * <p>视图的 token 估算（chars/4）：user 问题=1、assistant 工具调用=6、tool 结果=1、
     * user 追问=1、assistant 回答=1。从尾部累加：1 → 2 → 3（此时下标 2 = tool 结果）。
     * 所以保留 3 个 token 时，朴素切割点正好落在 tool 结果上。
     */
    private static int keepRecentTokensThatLandsOnToolResult(List<Projection.SeqMessage> view) {
        int acc = 0;
        for (int i = view.size() - 1; i >= 0; i--) {
            acc += CutPointFinder.estimate(view.get(i).message());
            if (i == 2) {
                return acc;
            }
        }
        return acc;
    }

    /**
     * 朴素切割点（不做合法性修正）：按累计 token 找切割下标。
     */
    private static int naiveCut(List<Projection.SeqMessage> view, int keepRecentTokens) {
        int acc = 0;
        for (int i = view.size() - 1; i >= 0; i--) {
            acc += CutPointFinder.estimate(view.get(i).message());
            if (acc >= keepRecentTokens) {
                return i;
            }
        }
        return view.size();
    }
}
