package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ModelTimeoutException;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 超时装饰器：给每次调用套一个超时（对齐 Go 参考实现的 {@code llm.WithTimeout}）。
 *
 * <p>超时**不是**从外部硬断线程：它通过取消令牌协作式地中止（与全局取消口径一致）。
 * 模型若不检查令牌，就不会真的停下来——这是协作式取消的固有代价，写在这里不藏着。
 *
 * <p>外部取消优先于超时：外部已经取消时报的是取消，不是超时（两件事不能混为一谈）。
 */
public final class TimeoutChatModel extends ChatModelDecorator {

    /** 定时器（单线程 daemon；只在设闹钟时用一下）。 */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mos4j-model-timeout");
                thread.setDaemon(true);
                return thread;
            });

    private final long timeoutMillis;

    /**
     * @param delegate 被装饰的模型
     * @param timeout  超时时长（必须为正）
     */
    public TimeoutChatModel(ChatModel delegate, Duration timeout) {
        super(delegate);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("llm: 超时时长必须为正");
        }
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        return guard(cancellation, token -> delegate.chat(request, token));
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return chatStream(request, emitter, CancellationToken.none());
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        return guard(cancellation, token -> delegate.chatStream(request, emitter, token));
    }

    /** 套上「外部取消 + 超时」两路信号，跑一次调用。 */
    private Response guard(CancellationToken cancellation,
                           java.util.function.Function<CancellationToken, Response> call) {
        // 已经取消就不该再调模型（发起一次注定被丢弃的请求是白花钱）。
        if (externallyCancelled(cancellation)) {
            throw new CancellationException("操作已取消");
        }
        CancellationToken token = CancellationToken.create();
        CancellationToken.Registration registration = null;
        if (cancellation != null) {
            registration = cancellation.onCancel(() -> token.cancel(cancellation.cause()));
        }
        ScheduledFuture<?> alarm = SCHEDULER.schedule(() -> token.cancel(), timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            Response response = call.apply(token);
            if (token.isCancelled() && !externallyCancelled(cancellation)) {
                throw new ModelTimeoutException(timeoutMillis);
            }
            return response;
        } catch (CancellationException e) {
            // 外部取消优先于超时：报取消，不报超时（两件事不能混为一谈）。
            if (externallyCancelled(cancellation)) {
                throw e;
            }
            throw new ModelTimeoutException(timeoutMillis);
        } finally {
            alarm.cancel(false);
            if (registration != null) {
                registration.close();
            }
        }
    }

    private static boolean externallyCancelled(CancellationToken cancellation) {
        return cancellation != null && cancellation.isCancelled();
    }
}
