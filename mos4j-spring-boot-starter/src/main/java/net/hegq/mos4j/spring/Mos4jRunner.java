package net.hegq.mos4j.spring;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.session.Session;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性执行器：跑一轮 turn（会话按 id 自动开/恢复）。
 *
 * <p><b>为什么需要一个 runner</b>：Spring 宿主的典型用法是「某个 HTTP 接口 → 跑一轮智能体」。
 * 每次自己 {@code mos4j.session(id)} + {@code agent.run(...)} 容易漏掉「同 id 续聊」和取消，
 * 所以这里收成一个入口。
 *
 * <p><b>取消语义</b>：协作式（见 {@link CancellationToken}）。每轮一个独立令牌；
 * 容器关闭时（{@link #close()}）把在途令牌全部取消——
 * 应用下线时正在跑的请求不该被硬切，但也不该无限挂着。
 *
 * <p><b>为什么实现 {@link AutoCloseable} 而不是 Spring 的 DisposableBean</b>：
 * 这样这个类不依赖任何 Spring 类型，宿主可以在非 Spring 环境里直接用。
 */
public final class Mos4jRunner implements AutoCloseable {

    private final Mos4j mos4j;
    private final Set<CancellationToken> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 构造执行器。
     *
     * @param mos4j 装配好的门面
     */
    public Mos4jRunner(Mos4j mos4j) {
        this.mos4j = mos4j;
    }

    /**
     * 跑一轮（会话按 id 自动开或恢复）。
     *
     * @param sessionId 会话 id
     * @param input 输入文本
     * @return 模型回答
     * @throws IOException 会话读写失败
     */
    public String run(String sessionId, String input) throws IOException {
        return run(sessionId, input, null);
    }

    /**
     * 跑一轮（带取消）。
     *
     * @param sessionId 会话 id
     * @param input 输入文本
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @return 模型回答
     * @throws IOException 会话读写失败
     */
    public String run(String sessionId, String input, CancellationToken cancellation) throws IOException {
        CancellationToken token = cancellation == null ? CancellationToken.create() : cancellation;
        inFlight.add(token);
        try {
            Session session = mos4j.session(sessionId);
            return mos4j.agent().run(session, input, token);
        } finally {
            inFlight.remove(token);
        }
    }

    /** 在途请求数（测试与监控用）。 */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** 容器关闭：取消在途请求（幂等）。 */
    @Override
    public void close() {
        for (CancellationToken token : inFlight) {
            token.cancel();
        }
        inFlight.clear();
    }
}
