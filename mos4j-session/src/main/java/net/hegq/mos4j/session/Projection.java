package net.hegq.mos4j.session;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 事件日志 → 对话消息的投影（派生而非存储，可随时重算）。
 *
 * <p><b>日志即事实（不变式 I2）</b>：消息历史是投影出来的，日志才是唯一事实来源。
 * 投影可以自愈，日志不动——留洞就是发生过的事实（append-only）。</p>
 *
 * <p><b>投影自愈（机制 M4）</b>：assistant 声明的工具调用若在日志里找不到配对的
 * {@code tool/result}（进程在执行期间被硬杀的残留），投影时补一条合成失败回执，
 * 保证「assistant 的 ToolCalls 与随后的 tool/result 按 CallID 成对」对外永远成立，
 * 否则服务商直接拒收历史。合成回执按<b>声明序</b>补在首个非 tool/result 事件之前
 * （协议要求 tool 消息紧跟在带调用的 assistant 消息之后），序号继承声明它的
 * assistant 事件——压缩遮蔽时它与调用方同生共死。</p>
 */
public final class Projection {

    /** 投影消息与其来源事件序号（压缩切割需要知道“从哪条事件起保留”）。 */
    public record SeqMessage(long seq, Message message) {
    }

    /** 合成回执的固定文案（协议侧可见，冻结）。 */
    public static final String INTERRUPTED_RECEIPT_TEMPLATE =
            "（%s 调用在执行期间被中断，没有结果；如仍需要请重新调用）";

    /** 投影注入摘要消息时的标记前缀。 */
    public static final String COMPACTION_SUMMARY_PREFIX = "[历史摘要]";

    private Projection() {
    }

    /** 投影为消息列表（无序号版）。 */
    public static List<Message> project(List<Event> events) {
        List<Message> out = new ArrayList<>();
        for (SeqMessage sm : projectSeq(events)) {
            out.add(sm.message());
        }
        return out;
    }

    /** 投影为带序号的消息列表。 */
    public static List<SeqMessage> projectSeq(List<Event> events) {
        State state = new State();
        for (Event event : events) {
            switch (event.kind()) {
                case USER -> {
                    state.flush();
                    state.out.add(new SeqMessage(event.seq(),
                            new Message(Role.USER, event.text(), null, null, null, null, null)));
                }
                case ASSISTANT -> {
                    state.flush();
                    state.lastAssistantSeq = event.seq();
                    state.out.add(new SeqMessage(event.seq(), new Message(Role.ASSISTANT, event.text(), null,
                            event.toolCalls(), null, null, event.reasoning())));
                    if (event.toolCalls() != null) {
                        state.pending.addAll(event.toolCalls());
                    }
                }
                case TOOL_RESULT -> {
                    ToolResult result = event.toolResult();
                    if (result != null) {
                        state.out.add(new SeqMessage(event.seq(), new Message(Role.TOOL, result.content(), null,
                                null, result.callId(), null, null)));
                        state.answered.add(result.callId());
                    }
                }
                case COMPACTION -> {
                    Compaction compaction = event.compaction();
                    if (compaction == null || compaction.summary() == null || compaction.summary().isEmpty()) {
                        continue;
                    }
                    state.flush();
                    // 遮蔽：丢掉 firstKeptSeq 之前的消息，注入摘要（摘要在前）
                    List<SeqMessage> kept = new ArrayList<>();
                    for (SeqMessage sm : state.out) {
                        if (sm.seq() >= compaction.firstKeptSeq()) {
                            kept.add(sm);
                        }
                    }
                    SeqMessage summary = new SeqMessage(event.seq(), new Message(Role.USER,
                            COMPACTION_SUMMARY_PREFIX + "\n" + compaction.summary(), null, null, null, null, null));
                    state.out.clear();
                    state.out.add(summary);
                    state.out.addAll(kept);
                }
                default -> {
                    // turn/* 与 tool/call 不投影（审计专用）
                }
            }
        }
        // 日志尾部的孤儿调用（进程中断残留）
        state.flush();
        return state.out;
    }

    /** 投影过程中的可变状态。 */
    private static final class State {
        private final List<SeqMessage> out = new ArrayList<>();
        /** 已投影、尚未见到回执的调用（按声明序）。 */
        private final List<ToolCall> pending = new ArrayList<>();
        /** 已回填的 CallID（去重）。 */
        private final Set<String> answered = new HashSet<>();
        /** 最近一条 assistant 事件的序号（合成回执继承它）。 */
        private long lastAssistantSeq;

        /**
         * 补上仍无回执的调用（合成失败结果），清空本批配对状态。
         *
         * <p>触发点：遇到任何非 tool/result 事件、以及日志结尾。</p>
         */
        private void flush() {
            for (ToolCall call : pending) {
                if (answered.contains(call.id())) {
                    continue;
                }
                out.add(new SeqMessage(lastAssistantSeq, new Message(Role.TOOL,
                        String.format(INTERRUPTED_RECEIPT_TEMPLATE, call.name()), null, null,
                        call.id(), null, null)));
            }
            pending.clear();
            answered.clear();
        }
    }
}
