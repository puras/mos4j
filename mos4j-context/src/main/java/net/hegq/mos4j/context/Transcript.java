package net.hegq.mos4j.context;

import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.session.Projection;

import java.util.List;

/**
 * 摘要输入的序列化格式。
 *
 * <p><b>格式是冻结的</b>：{@code [User]:} / {@code [Assistant]:} /
 * {@code [Assistant tool calls]:} / {@code [Tool result]:}，
 * 整体用 {@code <conversation>} 包裹，上一轮摘要用 {@code <previous-summary>} 包裹。
 *
 * <p><b>为什么用固定标签而不是直接给消息列表</b>：摘要器看到的必须是**事实**，
 * 不是「看起来像对话的东西」。标签形式让「这是历史，不是现在」一目了然，
 * 也让摘要器不会误以为自己正在参与对话（它最容易犯的错就是接着写）。
 */
public final class Transcript {

    /** 对话包裹标签。 */
    public static final String CONVERSATION_OPEN = "<conversation>";
    /** 对话包裹结束标签。 */
    public static final String CONVERSATION_CLOSE = "</conversation>";
    /** 上一轮摘要包裹标签。 */
    public static final String PREVIOUS_SUMMARY_OPEN = "<previous-summary>";
    /** 上一轮摘要包裹结束标签。 */
    public static final String PREVIOUS_SUMMARY_CLOSE = "</previous-summary>";

    private Transcript() {
    }

    /**
     * 渲染待摘要的对话。
     *
     * @param masked         被遮蔽区的消息（按时间序）
     * @param previousSummary 上一轮摘要（可空 = 没有）
     * @return 摘要输入文本
     */
    public static String render(List<Projection.SeqMessage> masked, String previousSummary) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append(PREVIOUS_SUMMARY_OPEN).append('\n')
                    .append(previousSummary.trim()).append('\n')
                    .append(PREVIOUS_SUMMARY_CLOSE).append('\n');
        }
        sb.append(CONVERSATION_OPEN).append('\n');
        for (Projection.SeqMessage sm : masked) {
            String line = renderMessage(sm.message());
            if (line != null) {
                sb.append(line).append('\n');
            }
        }
        sb.append(CONVERSATION_CLOSE);
        return sb.toString();
    }

    /**
     * 渲染单条消息。
     *
     * @param message 消息
     * @return 文本行；不可投影的消息返回 null
     */
    static String renderMessage(Message message) {
        if (message == null) {
            return null;
        }
        Role role = message.role();
        if (role == Role.USER) {
            return "[User]: " + text(message.content());
        }
        if (role == Role.ASSISTANT) {
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                StringBuilder sb = new StringBuilder("[Assistant tool calls]: ");
                boolean first = true;
                for (ToolCall call : message.toolCalls()) {
                    if (!first) {
                        sb.append("; ");
                    }
                    first = false;
                    sb.append(call.name()).append('(').append(call.args()).append(')');
                }
                if (message.content() != null && !message.content().isEmpty()) {
                    sb.append(" — ").append(message.content());
                }
                return sb.toString();
            }
            return "[Assistant]: " + text(message.content());
        }
        if (role == Role.TOOL) {
            return "[Tool result]: " + text(message.content());
        }
        if (role == Role.SYSTEM) {
            return "[System]: " + text(message.content());
        }
        return null;
    }

    private static String text(String content) {
        return content == null ? "" : content;
    }
}
