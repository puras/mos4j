package net.hegq.mos4j.hook;

/**
 * 守卫：安全边界的纯判定者。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>只判定，不做副作用（不写文件、不发请求、不改状态）——它对同一次调用
 *       只跑一次，副作用放守卫里等于把「判一次」变成「判多次」，配额与审批会错位；</li>
 *   <li>抛异常 = 判定失败 = 拒绝（fail-closed，不变式 I1），
 *       由 {@link GuardBus} 兜住，守卫实现方不必自己 try/catch；</li>
 *   <li>不接收也不返回 {@link HookEvent}——结构性保证「钩子无法绕过守卫」（不变式 I5）。</li>
 * </ul>
 */
@FunctionalInterface
public interface Guard {

    /**
     * 判定一次调用。
     *
     * @param event 守卫事件（工具名 + 参数原文 + 调用 id）
     * @return 判定结果（不返回 null；返回 null 视为拒绝）
     */
    GuardDecision check(GuardEvent event);
}
