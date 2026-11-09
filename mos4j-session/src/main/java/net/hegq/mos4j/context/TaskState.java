package net.hegq.mos4j.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 任务状态：只装**环境自证**的事实。
 *
 * <p><b>为什么模型的自述不算事实</b>：「我已完成 X」是模型的自我评价，不是事实。
 * 如果自述能进状态，状态机就变成模型在给自己打分——审计门形同虚设。
 * 能进这里的只有机器可回放的信号：工具输出、退出码、路径命中、事件日志。
 * 哪些工具的输出算数，由 {@link Auditor} 的白名单说了算。
 */
public final class TaskState {

    /**
     * 一条事实。
     *
     * @param key      事实键
     * @param value    事实值（工具输出）
     * @param toolName 产生它的工具（白名单判定依据）
     * @param callId   产生它的调用 id（可回溯到日志）
     */
    public record Fact(String key, String value, String toolName, String callId) {
    }

    private final Map<String, Fact> facts;

    private TaskState(Map<String, Fact> facts) {
        this.facts = Map.copyOf(facts);
    }

    /** 空状态。 */
    public static TaskState empty() {
        return new TaskState(new LinkedHashMap<>());
    }

    /**
     * 追加一条事实（同键覆盖）。
     *
     * @param key      事实键
     * @param value    事实值
     * @param toolName 产生它的工具
     * @param callId   调用 id
     * @return 新状态（不可变）
     */
    public TaskState with(String key, String value, String toolName, String callId) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("context: 事实键不能为空");
        }
        Map<String, Fact> copy = new LinkedHashMap<>(facts);
        copy.put(key, new Fact(key, value, toolName, callId));
        return new TaskState(copy);
    }

    /** 取一条事实。 */
    public Optional<Fact> fact(String key) {
        return Optional.ofNullable(facts.get(key));
    }

    /** 全部事实（只读）。 */
    public Map<String, Fact> facts() {
        return facts;
    }

    /** 事实条数。 */
    public int size() {
        return facts.size();
    }
}
