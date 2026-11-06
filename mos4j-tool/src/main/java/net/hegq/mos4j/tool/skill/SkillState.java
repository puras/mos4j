package net.hegq.mos4j.tool.skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一轮里的技能激活状态（本次任务里加载过哪些技能）。
 *
 * <p><b>每轮一份</b>：驱动在每轮开始时装一份新的（工具侧的可换槽），
 * 轮次结束就换掉——上一轮加载过什么，不该影响这一轮的「首次激活」判断。
 *
 * <p><b>并发安全</b>：工具可能并发执行（同一批多个 skill 调用）。
 */
public final class SkillState {

    private final List<String> activated = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    /**
     * 记一次激活。
     *
     * @param name 技能名
     * @return 是否<b>首次</b>激活（重复加载不算新激活）
     */
    public synchronized boolean activate(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!seen.add(name)) {
            return false;
        }
        activated.add(name);
        return true;
    }

    /**
     * 已激活的技能名（按激活顺序）。
     *
     * @return 技能名列表
     */
    public synchronized List<String> activated() {
        return List.copyOf(activated);
    }
}
