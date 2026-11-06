package net.hegq.mos4j.core;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌（Java 侧自创机制，见 {@code docs/contract/SELF-INVENTED.md} SI-1）。
 *
 * <p><b>为什么需要</b>：Go 用 {@code context.Context} 的结构化取消——取消沿着调用树
 * 自动传播，不需要每个函数都记得检查。Java 没有等价物：JDK 的
 * {@code Future.cancel} 只作用于单个任务，虚拟线程的中断（{@code Thread.interrupt}）
 * 语义弱且易被吞掉。结果就是「取消」在 Java 里必须被<b>显式携带</b>。</p>
 *
 * <p><b>不这么做会出什么事</b>：模型请求正在流式返回时宿主决定放弃——没有令牌，
 * 只能靠线程中断，底层 socket 可能继续占用；或者调用方各自发明取消标志，
 * 传播链条断在半路，任务继续跑并写日志（审计里出现「已取消」的事件流）。</p>
 *
 * <p><b>代价（诚实声明）</b>：取消是<b>协作式</b>的——令牌只发出信号，
 * 每个可阻塞点必须显式检查（{@link #throwIfCancelled()}）或注册回调。
 * 漏检的地方不会报错，只会在取消后继续跑。清单见
 * {@code docs/contract/INVARIANT-TEST-MAP.md} 的 I1-1 与 T6.4。</p>
 */
public final class CancellationToken {

    private static final CancellationToken NONE = new CancellationToken();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private CancellationToken() {
    }

    /** 返回一个永不取消的令牌（用于「不关心取消」的调用路径）。 */
    public static CancellationToken none() {
        return NONE;
    }

    /** 新建一个可取消的令牌。 */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /** 是否已取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 请求取消（幂等）：置位并执行已注册的回调。
     *
     * <p>回调在调用线程内同步执行——所以回调里不要做慢操作。</p>
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable action : callbacks) {
                action.run();
            }
            callbacks.clear();
        }
    }

    /** 若已取消则抛 {@link CancellationException}；否则正常返回。 */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("操作已取消");
        }
    }

    /**
     * 注册取消回调：令牌被取消时执行 {@code action}。
     *
     * <p>若令牌已取消，则<b>立即</b>执行回调并返回空注册（调用方无需先查状态）。</p>
     *
     * @return 注册句柄；{@link Registration#close()} 可解除注册（避免长期持有回调）
     */
    public Registration onCancel(Runnable action) {
        if (cancelled.get()) {
            action.run();
            return Registration.NOOP;
        }
        callbacks.add(action);
        return () -> callbacks.remove(action);
    }

    /** 取消回调的注册句柄。 */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NOOP = () -> {
        };

        /** 解除注册（幂等）。 */
        @Override
        void close();
    }
}
