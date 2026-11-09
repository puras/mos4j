package net.hegq.mos4j.agent.subagent;

import net.hegq.mos4j.session.Session;

/**
 * 子会话的工厂（宿主注入）。
 *
 * <p><b>为什么是接缝</b>：子代理跑在<b>独立会话</b>里（中间推理与工具调用留在子会话日志，
 * 不进父上下文）。会话怎么开（落盘到哪、用哪个后端、要不要带父的元信息）是宿主的事——
 * 框架不替宿主决定。
 *
 * <p>缺省实现：用<b>父会话同一个持久化后端</b>开子会话（子会话日志与父会话同处一个后端，
 * 宿主查得到）；父会话没有持久化后端时，子会话只在内存里。
 */
@FunctionalInterface
public interface SubAgentSessionFactory {

    /**
     * 开一个子会话。
     *
     * @param sessionId 子会话 id（框架派生：{@code <父会话>-sub-<子代理名>-<序号>}）
     * @return 子会话（不能为 null）
     */
    Session open(String sessionId);
}
