package net.hegq.mos4j.agent;

import net.hegq.mos4j.hook.Guard;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;
import net.hegq.mos4j.policy.ParamPolicy;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 参数级规则的**同一个槽**：运行期热替换只换这一份。
 *
 * <p>对齐 Go 参考实现的 {@code Agent.SetRules}（{@code mos.go}）：参数级规则
 * （deny 档进守卫、ask 档转人工确认）可以**运行期替换**——宿主要临时收紧某个
 * 工具的某个参数时，不必重建智能体。
 *
 * <p>与 {@link PolicySlot} 的分工：{@code PolicySlot} 管<b>名字级</b>名单
 * （模型可见性 + 执行放行），本槽管<b>参数级</b>规则（逐参数裁决）。两层独立，
 * 互不覆盖。
 *
 * <p>未装策略时槽里是「空策略」（不裁决任何参数）——不裁决 ≠ 放行失败，
 * 守卫总线在没有其他拒绝时照常放行（不变式 I6：没装的组件不花钱）。
 */
public final class ParamPolicySlot implements Guard {

    private final AtomicReference<ParamPolicy> current =
            new AtomicReference<>(ParamPolicy.empty());

    /**
     * 装一份参数级规则（热替换：下一次调用起生效）。
     *
     * @param policy 规则（可空 = 空策略，不裁决任何参数）
     */
    public void install(ParamPolicy policy) {
        current.set(policy == null ? ParamPolicy.empty() : policy);
    }

    /** 当前规则。 */
    public ParamPolicy policy() {
        return current.get();
    }

    @Override
    public GuardDecision check(GuardEvent event) {
        ParamPolicy policy = current.get();
        if (policy == null) {
            return GuardDecision.allow();
        }
        return policy.check(event);
    }
}
