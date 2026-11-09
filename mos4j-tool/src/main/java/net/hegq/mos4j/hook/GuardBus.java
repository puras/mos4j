package net.hegq.mos4j.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * 守卫总线：串行、单调、首个拒绝即整体拒绝并短路。
 *
 * <p><b>三条语义</b>：
 * <ul>
 *   <li><b>串行</b>：按注册顺序逐个判定，不并发——判定顺序影响结果
 *       （先 deny 的理由会被短路掉），并发就没有「首个拒绝」可言；</li>
 *   <li><b>单调</b>：判定只会越来越严。{@code ASK} 不会被后面的 {@code ALLOW} 洗掉
 *       （一次「要问人」不能被另一个守卫的「随便」盖掉）；
 *       {@code DENY}/{@code CANCEL} 直接短路返回，后面的守卫不再执行——</li>
 *   <li><b>fail-closed</b>：守卫抛异常 = 判定失败 = 拒绝（不变式 I1）。
 *       守卫出错时放行，等于把「安全组件坏了」翻译成「随便跑」。</li>
 * </ul>
 *
 * <p><b>零开销降级</b>：没有注册任何守卫时，{@link #check} 直接返回放行，
 * 不分配对象——未装配安全组件的部署不该为此付钱（不变式 I6）。
 */
public final class GuardBus {

    private final List<Guard> guards = new ArrayList<>();

    /**
     * 注册一个守卫（按注册顺序判定）。
     *
     * @param guard 守卫（不返回 null）
     * @return this（链式）
     */
    public GuardBus register(Guard guard) {
        if (guard == null) {
            throw new IllegalArgumentException("hook: 守卫不能为 null");
        }
        guards.add(guard);
        return this;
    }

    /** 已注册的守卫数量。 */
    public int size() {
        return guards.size();
    }

    /**
     * 判定一次调用。
     *
     * @param event 守卫事件
     * @return 判定结果；没有守卫时返回放行
     */
    public GuardDecision check(GuardEvent event) {
        if (guards.isEmpty()) {
            return GuardDecision.allow();
        }
        GuardDecision pending = null;
        for (Guard guard : guards) {
            GuardDecision decision;
            try {
                decision = guard.check(event);
            } catch (RuntimeException e) {
                // 守卫判定异常 → 拒绝（fail-closed）
                return GuardDecision.deny("守卫判定异常: " + message(e));
            }
            if (decision == null) {
                // 守卫返回 null = 没有表态 = 不放行也不算数 → 拒绝（fail-closed）
                return GuardDecision.deny("守卫未给出判定");
            }
            switch (decision.verdict()) {
                case DENY, CANCEL -> {
                    // 首个拒绝即整体拒绝并短路
                    return decision;
                }
                case ASK -> {
                    if (pending == null) {
                        pending = decision;
                    }
                }
                case ALLOW -> {
                    // 继续看下一个守卫
                }
            }
        }
        return pending == null ? GuardDecision.allow() : pending;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
