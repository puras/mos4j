package net.hegq.mos4j.model;

import net.hegq.mos4j.core.CancellationToken;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「当前模型」的转发壳：调用时才决定转发给谁（对齐 Go 参考实现 {@code switchableModel}）。
 *
 * <p><b>为什么需要壳</b>：换模型 = 换壳里的目标，不重建决策器——所以「驱动是构造时
 * 捕获模型还是每轮读取模型」这种实现细节不影响换模型的正确性。
 *
 * <p><b>下一轮生效</b>：正在跑的那一轮不受影响（它已经取过目标）；下一次调用才走新模型。
 *
 * <p><b>能力按当前模型算</b>：换到一个不支持流式的模型之后，下一次决策看到的就是
 * 「不支持流式」——如实反映，不撒谎。
 */
public final class SwitchableChatModel implements ChatModel {

    private final AtomicReference<ChatModel> current;

    /**
     * @param initial 初始目标
     */
    public SwitchableChatModel(ChatModel initial) {
        if (initial == null) {
            throw new IllegalArgumentException("llm: 转发壳的初始模型不能为空");
        }
        this.current = new AtomicReference<>(initial);
    }

    /** 当前目标。 */
    public ChatModel current() {
        return current.get();
    }

    /**
     * 换目标（下一轮生效）。
     *
     * @param next 新目标（不能为空）
     */
    public void set(ChatModel next) {
        if (next == null) {
            throw new IllegalArgumentException("llm: 转发壳的目标不能为空");
        }
        current.set(next);
    }

    @Override
    public Response chat(Request request) {
        return current().chat(request);
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        return current().chat(request, cancellation);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return current().chatStream(request, emitter);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        return current().chatStream(request, emitter, cancellation);
    }

    @Override
    public Set<Capability> capabilities() {
        return current().capabilities();
    }
}
