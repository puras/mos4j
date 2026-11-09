package net.hegq.mos4j.driver;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Tool;

import java.util.List;
import java.util.function.Consumer;

/**
 * 默认决策器：模型直接作答；请求工具时把调用交循环层执行。
 *
 * <p><b>为什么决策器与驱动分开</b>：循环语义（事件序列、落盘顺序、终止判定）
 * 与「下一步问模型什么」是两件事。分开后，Plan&amp;Execute / Reflection / Scripted
 * 可以复用同一套循环语义，只换决策器。</p>
 */
public final class ReActPlanner implements Planner {

    private final ChatModel model;
    private final Integer maxTokens;

    public ReActPlanner(ChatModel model) {
        this(model, null);
    }

    /**
     * @param model     模型
     * @param maxTokens 输出的 token 上限（可空 = 用协议自己的缺省；模型级配置，对齐 Go 的 {@code Config["max_tokens"]}）
     */
    public ReActPlanner(ChatModel model, Integer maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public Action nextAction(List<Message> history, List<Tool> tools) {
        return nextAction(history, tools, CancellationToken.none());
    }

    /**
     * 决定下一步（带取消）。
     *
     * <p>令牌直传到模型调用：这是取消链路上唯一能<b>真中止</b>的一环
     * （HTTP 请求已发出时，模型适配层会真的断开，而不是等它回来）。
     */
    @Override
    public Action nextAction(List<Message> history, List<Tool> tools, CancellationToken cancellation) {
        Response response = model.chat(request(history, tools), cancellation);
        Message message = response.message();
        return new Action(
                message.content(),
                message.reasoningContent(),
                message.toolCalls() == null ? List.of() : message.toolCalls(),
                response.finishReason(),
                response.usage());
    }

    /**
     * 构造请求：<b>必须带上工具定义</b>。
     *
     * <p>漏这一步的后果不是「少一个字段」，而是整条链路静默失效：
     * 模型看不到 {@code read}，只会直接编一个答案（或反复空转），
     * 而框架这边什么异常都没有——最难排查的一类故障。</p>
     */
    private Request request(List<Message> history, List<Tool> tools) {
        return new Request(history, tools, null, maxTokens, null, null, null);
    }

    /**
     * 流式决策：增量经 {@code onChunk} 推送，返回聚合后的完整动作。
     *
     * <p>流式与非流式的语义完全一致（同一个适配器聚合），只是多一次推送。</p>
     */
    public Action nextActionStream(List<Message> history, List<Tool> tools, Consumer<StreamChunk> onChunk) {
        return nextActionStream(history, tools, onChunk, CancellationToken.none());
    }

    /**
     * 流式决策（带取消）。
     *
     * <p>令牌直传到模型调用：流式请求通常耗时最长，取消不生效等于宿主
     * 「叫停不了正在吐字的那一次调用」。
     */
    public Action nextActionStream(List<Message> history, List<Tool> tools, Consumer<StreamChunk> onChunk,
                                   CancellationToken cancellation) {
        Response response = model.chatStream(request(history, tools), onChunk::accept, cancellation);
        Message message = response.message();
        return new Action(
                message.content(),
                message.reasoningContent(),
                message.toolCalls() == null ? List.of() : message.toolCalls(),
                response.finishReason(),
                response.usage());
    }
}
