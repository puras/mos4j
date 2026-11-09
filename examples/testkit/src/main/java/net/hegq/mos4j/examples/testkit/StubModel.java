package net.hegq.mos4j.examples.testkit;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本化模型替身：让示例的用例**不碰网络**也能真跑一遍装配、工具链与循环。
 *
 * <p><b>为什么示例也需要它</b>：示例的真正内容是「怎么装配」，不是「模型会答什么」。
 * 用替身跑一遍，示例里的装配代码才不会退化成「看起来对、其实没跑过」。
 *
 * <p>用法：
 * <pre>{@code
 * StubModel model = StubModel.of(
 *         StubModel.toolCall("c1", "read", "{\"path\":\"a.txt\"}"),
 *         StubModel.text("读完了"));
 * }</pre>
 */
public final class StubModel implements ChatModel {

    /** 空用量（示例不看 token，但事件里要有 usage 字段）。 */
    public static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    private final List<Object> steps;
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger round = new AtomicInteger();

    private StubModel(List<Object> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * 按轮次脚本化。
     *
     * @param steps 每一步（{@link Response} 或 {@link RuntimeException}——抛错那一步直接失败）
     * @return 替身
     */
    public static StubModel of(Object... steps) {
        return new StubModel(List.of(steps));
    }

    /**
     * 一段纯文本回答。
     *
     * @param content 回答正文
     * @return 响应
     */
    public static Response text(String content) {
        return new Response(Message.assistant(content), NO_USAGE, "stop");
    }

    /**
     * 一次工具调用。
     *
     * @param id    调用 id
     * @param name  工具名
     * @param args  参数（原始 JSON 字符串）
     * @return 响应
     */
    public static Response toolCall(String id, String name, String args) {
        return new Response(Message.assistant("", List.of(new ToolCall(id, name, args))), NO_USAGE, "tool_calls");
    }

    /** 收到的每次请求（断言模型看见了什么用）。 */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** 第 n 次请求的系统提示词（没有消息时是空串）。 */
    public String systemPromptOf(int index) {
        Request request = requests.get(index);
        if (request.messages().isEmpty()) {
            return "";
        }
        return String.valueOf(request.messages().get(0).content());
    }

    /** 第 n 次请求里模型看得见的工具名。 */
    public List<String> toolNamesOf(int index) {
        Request request = requests.get(index);
        if (request.tools() == null) {
            return List.of();
        }
        return request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList();
    }

    @Override
    public Response chat(Request request) {
        return chat(request, (CancellationToken) null);
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        requests.add(request);
        int index = round.getAndIncrement();
        if (index >= steps.size()) {
            // 脚本用完就说清楚：IndexOutOfBoundsException 让人以为是替身坏了。
            throw new IllegalStateException("脚本化模型：脚本已用完（第 " + (index + 1)
                    + " 次调用没有对应步骤；本次共 " + steps.size() + " 步）");
        }
        Object step = steps.get(index);
        if (step instanceof RuntimeException failure) {
            throw failure;
        }
        return (Response) step;
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return chat(request, (CancellationToken) null);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        return chat(request, cancellation);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of();
    }
}
