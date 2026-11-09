package net.hegq.mos4j.context;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 审计门：决定哪些工具的输出可以进 {@link TaskState}。
 *
 * <p><b>白名单是封闭集合</b>：只有明确列出的工具名算数，**不允许通配符**。
 * 通配符（{@code *}、{@code read*}）等于「基本上什么都能进」——
 * 那就没有门了。要放开就显式列出工具名。
 *
 * <p><b>默认只读工具</b>：读文件、列目录、看时间这类工具的输出是环境的直接反映，
 * 模型改不了；写文件、跑命令的输出是「动作的结果」，要进状态得由宿主显式授权。
 */
public final class Auditor {

    /**
     * 默认可信工具（只读、输出由环境直接给出）。
     *
     * <p>与 Go 参考实现的默认可信名单逐字一致（{@code agent/auditor.go}）：
     * {@code current_time} / {@code read} / {@code grep} / {@code glob} / {@code ls}。
     * {@code web_search} / {@code web_fetch} <b>故意不在</b>：它们的内容是外部声明，
     * 不是环境自证（与 Go 同款理由）。
     */
    public static final Set<String> DEFAULT_TRUSTED_TOOLS =
            Set.of("read", "ls", "glob", "grep", "current_time");

    private final Set<String> trustedTools;

    private Auditor(Set<String> trustedTools) {
        this.trustedTools = Set.copyOf(trustedTools);
    }

    /** 用默认可信工具构造。 */
    public static Auditor defaults() {
        return new Auditor(DEFAULT_TRUSTED_TOOLS);
    }

    /**
     * 用显式白名单构造。
     *
     * @param trustedTools 可信工具名（不允许通配符）
     * @return 审计门
     */
    public static Auditor of(Collection<String> trustedTools) {
        if (trustedTools == null) {
            throw new IllegalArgumentException("context: 审计门白名单不能为空");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String name : trustedTools) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("context: 审计门白名单里有空工具名");
            }
            if (name.contains("*") || name.contains("?") || name.contains("[")) {
                throw new IllegalArgumentException(
                        "context: 审计门白名单不允许通配符（要放开就显式列出工具名）: " + name);
            }
            copy.add(name);
        }
        return new Auditor(copy);
    }

    /** 可信工具名集合。 */
    public Set<String> trustedTools() {
        return trustedTools;
    }

    /**
     * 某个工具的输出是否算数。
     *
     * @param toolName 工具名
     * @return true 表示算数
     */
    public boolean admits(String toolName) {
        return toolName != null && trustedTools.contains(toolName);
    }

    /**
     * 裁决一次事实申报。
     *
     * @param state    当前状态
     * @param key      事实键
     * @param value    事实值
     * @param toolName 产生它的工具
     * @param callId   调用 id
     * @return 裁决结果（不通过时状态原样返回，并给出理由）
     */
    public Decision admit(TaskState state, String key, String value, String toolName, String callId) {
        if (state == null) {
            throw new IllegalArgumentException("context: 审计门需要当前状态");
        }
        if (!admits(toolName)) {
            return new Decision(false, state,
                    "工具输出不进任务状态（不在审计门白名单里）: " + toolName);
        }
        return new Decision(true, state.with(key, value, toolName, callId), null);
    }

    /**
     * 裁决结果。
     *
     * @param accepted 是否通过
     * @param state    裁决后的状态（未通过时是原状态）
     * @param reason   不通过的理由（通过时为空）
     */
    public record Decision(boolean accepted, TaskState state, String reason) {
    }
}
