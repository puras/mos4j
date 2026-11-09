package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;

import java.util.EnumSet;
import java.util.Set;

/**
 * 降级装饰器：主模型失败时自动改用备用模型（对齐 Go 参考实现的 {@code llm.WithFallback}）。
 *
 * <p>能力集是**并集**：主模型挂了以后备用模型得能顶上，所以对外宣称的能力不能比备用模型少。
 *
 * <p>失败口径：任何运行时异常都算失败并降级。**取消不算失败**——
 * 用户主动取消时切备用模型，等于无视用户意图，所以取消照常抛出。
 */
public final class FallbackChatModel extends ChatModelDecorator {

    private final ChatModel backup;

    /**
     * @param primary 主模型
     * @param backup  备用模型（可空 = 等价于直接用主模型）
     */
    public FallbackChatModel(ChatModel primary, ChatModel backup) {
        super(primary);
        this.backup = backup;
    }

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        try {
            return delegate.chat(request, cancellation);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (backup == null) {
                throw e;
            }
            return backup.chat(request, cancellation);
        }
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return chatStream(request, emitter, CancellationToken.none());
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        try {
            return delegate.chatStream(request, emitter, cancellation);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            if (backup == null) {
                throw e;
            }
            return backup.chatStream(request, emitter, cancellation);
        }
    }

    @Override
    public Set<Capability> capabilities() {
        Set<Capability> merged = EnumSet.noneOf(Capability.class);
        merged.addAll(delegate.capabilities());
        if (backup != null) {
            merged.addAll(backup.capabilities());
        }
        return merged;
    }
}
