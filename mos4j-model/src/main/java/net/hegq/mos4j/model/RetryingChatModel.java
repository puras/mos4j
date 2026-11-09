package net.hegq.mos4j.model;

import net.hegq.mos4j.core.CancellationToken;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.concurrent.CancellationException;

/**
 * 模型调用的重试执行器（T6.5）。
 *
 * <p><b>语义（不得擅改）</b>：</p>
 * <ul>
 *   <li><b>默认不装配就没有重试</b>：{@code RetryPolicy == null} 时直接透传调用。</li>
 *   <li><b>只重试瞬时错误</b>：分类器说了算（缺省 429/5xx/408/网络/空响应）。</li>
 *   <li><b>取消是终止性的</b>：每次尝试前与每次等待中检查令牌；等待中被取消立刻抛
 *       {@link CancellationException}，绝不发起下一次尝试。</li>
 *   <li><b>退避</b>：有界指数 + 抖动（{@link RetryPolicy#backoff}）。</li>
 *   <li><b>流式只在未吐出内容时重试</b>：emitter 已收到任何块后再重试 = 用户看到的内容
 *       重复一遍；所以第一次吐块之后错误原样抛出，由上层决定收尾。</li>
 * </ul>
 *
 * <p><b>为什么默认不重试（不这么做会出什么事）</b>：重试会改变「一次输入对应几次调用」这件事，
 * 而审计（不变式 I2「日志即事实」）记的是真实发生过的调用。默认开启重试，
 * 日志里就会出现同一个请求的多次记录，事后无法区分「模型真的调了三次」与「框架重试了三次」；
 * 对瞬时错误盲重试还会把一次明确的失败拖成多次失败（每次都写一条错误回执）。
 * 所以重试做成<b>装饰器</b>：不装就是一次一调用，装了才重试，且只重试分类器认定的瞬时错误。
 */
public final class RetryingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RetryPolicy policy;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    /** 测试注入睡眠用的窄接口。 */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public RetryingChatModel(ChatModel delegate, RetryPolicy policy) {
        this(delegate, policy, java.util.random.RandomGenerator.getDefault(),
                RetryExecutors::defaultSleep);
    }
    /** 全注入构造（测试用）。 */
    public RetryingChatModel(ChatModel delegate, RetryPolicy policy,
                             RandomGenerator random, Sleeper sleeper) {
        this.delegate = delegate;
        this.policy = policy;
        this.random = random;
        this.sleeper = sleeper;
    }

    private static void defaultSleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    // ---------------------------------------------------------------- 非流式

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        if (policy == null) {
            return delegate.chat(request, cancellation);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellation.throwIfCancelled();
            try {
                return delegate.chat(request, cancellation);
            } catch (RuntimeException error) {
                if (!isRetryable(error, cancellation)) {
                    throw error;
                }
                last = error;
                if (attempt == policy.maxAttempts()) {
                    break;
                }
                waitBackoff(attempt, cancellation);
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ 流式

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return chatStream(request, emitter, CancellationToken.none());
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        if (policy == null) {
            return delegate.chatStream(request, emitter, cancellation);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellation.throwIfCancelled();
            // 「只在未吐出内容时重试」：先包一层发射器记录是否已吐块
            java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean(false);
            StreamEmitter guarded = chunk -> {
                emitted.set(true);
                emitter.emit(chunk);
            };
            try {
                return delegate.chatStream(request, guarded, cancellation);
            } catch (RuntimeException error) {
                if (emitted.get()) {
                    // 已经吐过内容：重复整个流会让宿主看到重复文本——错误原样上抛
                    throw error;
                }
                if (!isRetryable(error, cancellation)) {
                    throw error;
                }
                last = error;
                if (attempt == policy.maxAttempts()) {
                    break;
                }
                waitBackoff(attempt, cancellation);
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------ 能力

    @Override
    public java.util.Set<Capability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public boolean supports(Capability capability) {
        return delegate.supports(capability);
    }

    // ------------------------------------------------------------------ 内部

    /** 可重试判定：瞬时错误 + 未取消。取消与宿主中断永远不重试。 */
    private boolean isRetryable(RuntimeException error, CancellationToken cancellation) {
        if (cancellation.isCancelled() || error instanceof CancellationException) {
            return false;
        }
        return policy.isTransient(error);
    }

    /** 指数退避等待；等待中取消立即抛 {@link CancellationException}。 */
    private void waitBackoff(int attempt, CancellationToken cancellation) {
        Duration delay = policy.backoff(attempt, random);
        while (!delay.isZero() && !delay.isNegative()) {
            cancellation.throwIfCancelled();
            Duration step = delay.compareTo(Duration.ofMillis(100)) > 0
                    ? Duration.ofMillis(100) : delay;
            try {
                sleeper.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("重试等待被中断");
            }
            delay = delay.minus(step);
        }
        cancellation.throwIfCancelled();
    }
}
