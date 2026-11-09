package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 观测装饰器：每次调用结束后回调一次（成功、失败都上报）。
 *
 * <p>对齐 Go 参考实现的 {@code llm.WithLogging}：发起 / 结果 / 失败**统一在结束时上报**，
 * 不搞「发起了」「成功了」两条日志——那样失败时只有一半信息。
 *
 * <p>回调里要记什么（模型名、地址、用量）由宿主决定：装饰器只负责**时机与上下文**。
 * 回调抛异常会**吞掉本次调用的结果**吗？不会——异常从回调抛出后仍会继续向上传播，
 * 但记录本身已经发出。
 */
public final class LoggingChatModel extends ChatModelDecorator {

    private final Consumer<CallRecord> log;

    /**
     * @param delegate 被装饰的模型
     * @param log      观测回调（每次调用一次；异常不吞）
     */
    public LoggingChatModel(ChatModel delegate, Consumer<CallRecord> log) {
        super(delegate);
        if (log == null) {
            throw new IllegalArgumentException("llm: 观测回调不能为空");
        }
        this.log = log;
    }

    @Override
    public Response chat(Request request) {
        long start = System.nanoTime();
        Response response = null;
        Throwable error = null;
        try {
            response = delegate.chat(request);
            return response;
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } finally {
            report(request, response, error, start, false);
        }
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        long start = System.nanoTime();
        Response response = null;
        Throwable error = null;
        try {
            response = delegate.chat(request, cancellation);
            return response;
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } finally {
            report(request, response, error, start, false);
        }
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        long start = System.nanoTime();
        Response response = null;
        Throwable error = null;
        try {
            response = delegate.chatStream(request, emitter);
            return response;
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } finally {
            report(request, response, error, start, true);
        }
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        long start = System.nanoTime();
        Response response = null;
        Throwable error = null;
        try {
            response = delegate.chatStream(request, emitter, cancellation);
            return response;
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } finally {
            report(request, response, error, start, true);
        }
    }

    private void report(Request request, Response response, Throwable error, long startNano, boolean stream) {
        log.accept(new CallRecord(request, response, error,
                Duration.ofNanos(System.nanoTime() - startNano), stream));
    }
}
