package net.hegq.mos4j.agent;

import net.hegq.mos4j.hook.Guard;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;
import net.hegq.mos4j.policy.ToolNamePolicy;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 名字级策略的**同一个槽**：可见性与运行时判定读同一份名单。
 *
 * <p>为什么要一个槽而不是两处各拿一份：策略变化时（热替换），两份名单一旦不同步，
 * 就会出现「模型看得见但调不动」或「看不见却能调」——前者是误导，后者是审计缺口。
 * 这里让两处都读 {@link #current}，替换一次即可同时生效。
 *
 * <p>对齐 Go 参考实现的 {@code policySlot}（{@code mos.go}）：用具名槽而不是整栈替换，
 * 策略热替换只动自己那层，MCP 的 hidden 层不受影响。
 */
public final class PolicySlot implements Guard {

    /** 策略在注册表可见性层里的槽名。 */
    public static final String SLOT = "policy";

    private final AtomicReference<ToolNamePolicy> current =
            new AtomicReference<>(ToolNamePolicy.unrestricted());

    /**
     * 装一份策略（热替换：可见性与执行期同时跟随）。
     *
     * @param policy 策略（可空 = 不限制）
     */
    public void install(ToolNamePolicy policy) {
        current.set(policy == null ? ToolNamePolicy.unrestricted() : policy);
    }

    /** 当前策略。 */
    public ToolNamePolicy policy() {
        return current.get();
    }

    /**
     * 可见性判定（注册表可见性槽用）。
     *
     * @param name 工具名
     * @return true = 对模型可见
     */
    public boolean allows(String name) {
        return current.get().allows(name);
    }

    @Override
    public GuardDecision check(GuardEvent event) {
        if (event == null) {
            return GuardDecision.deny("守卫事件为空");
        }
        ToolNamePolicy policy = current.get();
        if (policy.allows(event.toolName())) {
            return GuardDecision.allow();
        }
        return GuardDecision.deny("工具 " + event.toolName() + " 不在策略放行范围内");
    }
}
