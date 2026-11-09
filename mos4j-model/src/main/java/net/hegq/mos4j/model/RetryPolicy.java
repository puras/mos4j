package net.hegq.mos4j.model;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * 重试策略（T6.5，独立装配——<b>默认不装配就没有重试</b>）。
 *
 * <p><b>为什么独立装配</b>：重试改变调用次数语义（审计「日志即事实」只记录真实发生的调用）。
 * 默认零重试 = 默认行为可预测；要重试是宿主的显式决定。</p>
 *
 * <p><b>只重试瞬时错误</b>：429/5xx 与网络类 {@code UncheckedIOException}、
 * 空响应 {@link EmptyResponseException} 是瞬时的；4xx（除 408/429）、协议 bug、
 * 参数错误重试没有意义。判断器可由宿主覆盖（{@link #retryIf(Predicate)}）。</p>
 *
 * <p><b>退避</b>：有界指数 + 抖动（{@code min(maxDelay, base * 2^(attempt-1)) ± 20%}），
 * 避免重试风暴同步放大故障。</p>
 */
public final class RetryPolicy {

    /** 缺省值：5 次、base 500ms、上限 20s。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(20);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Predicate<Throwable> classifier;

    private RetryPolicy(int maxAttempts, Duration initialBackoff,
                        Duration maxBackoff, Predicate<Throwable> classifier) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.classifier = classifier;
    }

    /** 缺省策略（5 次、500ms 起、20s 封顶、缺省瞬时分类器）。 */
    public static RetryPolicy ofDefaults() {
        return builder().build();
    }

    /** 自定义构建。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 最大尝试次数（含首次；1 = 不重试）。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 判断错误是否瞬时（瞬时才值得重试）。 */
    public boolean isTransient(Throwable error) {
        return classifier.test(error);
    }

    /**
     * 第 {@code attempt} 次失败后的等待时长（attempt 从 1 起）：
     * 有界指数 + ±20% 抖动。
     */
    public Duration backoff(int attempt, RandomGenerator random) {
        long base = Math.min(
                initialBackoff.toMillis(),
                maxBackoff.toMillis());
        long exp = Math.min(attempt - 1, 20); // 防 2^40 溢出，实际被 maxBackoff 封住
        long delay = base * (1L << exp);
        if (delay > maxBackoff.toMillis()) {
            delay = maxBackoff.toMillis();
        }
        if (delay <= 0) {
            return Duration.ZERO;
        }
        // ±20% 抖动：[(80%)*delay, (120%)*delay]
        long jitter = (long) (delay * 0.2d);
        long low = delay - jitter;
        long span = Math.max(1, 2 * jitter + 1);
        return Duration.ofMillis(low + random.nextLong(span));
    }

    /** 构建器。 */
    public static final class Builder {
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private Duration initialBackoff = DEFAULT_INITIAL_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private Predicate<Throwable> classifier = RetryPolicies::defaultTransient;

        /** 最大尝试次数（含首次；&lt;1 视为 1）。 */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
            return this;
        }

        /** 首次退避时长（&lt;=0 视为 0）。 */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
            return this;
        }

        /** 退避封顶。 */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
            return this;
        }

        /** 覆盖瞬时错误分类器。 */
        public Builder retryIf(Predicate<Throwable> classifier) {
            this.classifier = classifier == null ? RetryPolicies::defaultTransient : classifier;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, classifier);
        }
    }
}
