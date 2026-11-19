package net.hegq.mos4j.examples.model;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.chat.Message;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.chat.Role;
import net.hegq.mos4j.model.chat.StreamEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环对话：<b>宿主自己管历史</b>的那部分代码。
 *
 * <p>纯模型层没有会话：模型永远只看得见这一次请求里带上的消息。所以「循环对话」=
 * 宿主把历次 {@code user} / {@code assistant} 消息留着，每次请求整段带上。这个类就是
 * 那份历史（{@link #history()}）+ 一次问答（{@link #ask}）。它<b>不落盘、不压缩、
 * 不裁剪</b>——那是会话层（{@code mos4j-session}）的活，纯模型示例刻意不碰。
 *
 * <p><b>三条口径</b>：
 * <ul>
 *   <li><b>失败不进历史</b>：这一轮抛异常就什么都不追加，宿主可以原样重试；</li>
 *   <li><b>空回答不算回答</b>：模型既没正文也没工具调用时抛 {@link IllegalStateException}
 *       且不追加——把空白当历史带下去，下一轮请求就带着一条空 assistant 消息；</li>
 *   <li><b>历史不可变出去</b>：{@link #history()} 返回副本，宿主改不动内部状态。</li>
 * </ul>
 *
 * <p>线程不安全（一个实例 = 一段对话；多段对话就多开几个实例）。
 */
public final class ModelChatLoop {

    private final String systemPrompt;
    private final List<Message> history = new ArrayList<>();

    /**
     * 构造：历史从一条系统提示词开始（空提示词 = 不带 system 消息）。
     *
     * @param systemPrompt 系统提示词（可空）
     */
    public ModelChatLoop(String systemPrompt) {
        this.systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? null : systemPrompt;
        if (this.systemPrompt != null) {
            history.add(Message.system(this.systemPrompt));
        }
    }

    /**
     * 问一句、答一句，并把两条消息追加进历史。
     *
     * @param model    模型
     * @param question 用户输入（空白当作没输入，直接拒绝而不是发一条空消息）
     * @return 助手回答正文
     * @throws IllegalStateException 问题为空 / 模型回了空正文（两种都不改历史）
     */
    public String ask(ChatModel model, String question) {
        return ask(model, question, CancellationToken.none());
    }

    /**
     * 问一句、答一句（带取消令牌）。
     *
     * @param model        模型
     * @param question     用户输入
     * @param cancellation 宿主取消令牌（可空 = 不可取消）
     * @return 助手回答正文
     */
    public String ask(ChatModel model, String question, CancellationToken cancellation) {
        if (question == null || question.isBlank()) {
            throw new IllegalStateException("mos4j-example: 问题为空——不发空消息，也不改历史");
        }
        Message userMessage = Message.user(question);
        List<Message> next = new ArrayList<>(history);
        next.add(userMessage);

        Response response = model.chat(Request.of(List.copyOf(next)),
                cancellation == null ? CancellationToken.none() : cancellation);

        return commit(userMessage, response);
    }

    /**
     * 流式问一句、答一句（增量交给 {@code emitter}，历史照样追加）。
     *
     * @param model    模型
     * @param question 用户输入
     * @param emitter  增量去处（可空 = 不要增量）
     * @return 助手回答正文
     */
    public String askStream(ChatModel model, String question, StreamEmitter emitter) {
        if (!model.supports(Capability.STREAMING)) {
            throw new IllegalStateException("mos4j-example: 该模型不支持流式（capabilities="
                    + model.capabilities() + "）——fail-closed，不试运气");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalStateException("mos4j-example: 问题为空——不发空消息，也不改历史");
        }
        Message userMessage = Message.user(question);
        List<Message> next = new ArrayList<>(history);
        next.add(userMessage);

        Response response = model.chatStream(Request.of(List.copyOf(next)), emitter,
                CancellationToken.none());

        return commit(userMessage, response);
    }

    /**
     * 回答合格才提交两条消息；不合格抛错且历史不变（失败不进历史，宿主可原样重试）。
     */
    private String commit(Message userMessage, Response response) {
        String answer = response == null || response.message() == null
                ? null : response.message().content();
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException(
                    "mos4j-example: 模型回了空正文——不当成一轮对话，历史未改动（finishReason="
                            + (response == null ? "null" : response.finishReason()) + "）");
        }
        history.add(userMessage);
        history.add(response.message());
        return answer;
    }

    /** 清空历史（系统提示词留着：它是装配的一部分，不是对话的一部分）。 */
    public void reset() {
        history.clear();
        if (systemPrompt != null) {
            history.add(Message.system(systemPrompt));
        }
    }

    /**
     * 当前历史（副本）。
     *
     * @return 不可变消息列表（第一条通常是 system）
     */
    public List<Message> history() {
        return List.copyOf(history);
    }

    /** 历史里的提问轮数（不算 system 消息）。 */
    public int rounds() {
        return (int) history.stream()
                .filter(message -> message.role() == Role.USER)
                .count();
    }
}
