package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;

import java.util.Set;

/**
 * 模型装饰器的基类（对齐 Go 参考实现 {@code llm/wrap.go}）。
 *
 * <p><b>为什么要有装饰器这一层</b>：协议适配器只管「把请求翻译成某家的协议」，
 * 横切能力（超时、降级、观测、缓存）不属于任何一家协议。装饰器让这些能力
 * **可插拔、可叠加**，而不是每家协议各写一遍。
 *
 * <p><b>约定</b>：每个装饰器职责单一，嵌套关系由调用方显式书写
 * （组合顺序即执行顺序：外层先执行）。没有隐式顺序、没有全局注册表。
 */
public abstract class ChatModelDecorator implements ChatModel {

    /** 被装饰的模型。 */
    protected final ChatModel delegate;

    protected ChatModelDecorator(ChatModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("llm: 被装饰的模型不能为空");
        }
        this.delegate = delegate;
    }

    /** 被装饰的模型（宿主可取回原模型）。 */
    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public Response chat(Request request) {
        return delegate.chat(request);
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        return delegate.chat(request, cancellation);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return delegate.chatStream(request, emitter);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        return delegate.chatStream(request, emitter, cancellation);
    }

    @Override
    public Set<Capability> capabilities() {
        return delegate.capabilities();
    }
}
