package net.hegq.mos4j.policy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.hook.Guard;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数级策略：按「工具 + 参数」裁决 allow / deny / ask。
 *
 * <p><b>三条语义</b>：
 * <ul>
 *   <li><b>解析失败 = 拒绝</b>：参数不是合法 JSON → 直接拒绝。
 *       理由很直白：策略判的是「这个参数允不允许」，参数读不出来就没法判，
 *       读不出来还放行等于策略失效；</li>
 *   <li><b>只读载荷快照</b>：交给策略的是不可变快照。策略不该能改参数
 *       ——改了就等于「审一遍的载荷」和「执行的载荷」不是同一个
 *       （和机制 M1 的载荷冻结同一个病）；</li>
 *   <li><b>没写规则的参数 = 允许</b>：策略是白名单式的「需要特别裁决的点」，
 *       不是黑名单。裁决取最严的一档：deny &gt; ask &gt; allow。</li>
 * </ul>
 */
public final class ParamPolicy implements Guard {

    /** 参数裁决档位。 */
    public enum Verdict {
        /** 允许。 */
        ALLOW,
        /** 需要人工确认。 */
        ASK,
        /** 拒绝。 */
        DENY
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, Verdict>> rules;

    private ParamPolicy(Map<String, Map<String, Verdict>> rules) {
        this.rules = rules;
    }

    /** 新建构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 空策略（不裁决任何参数）。 */
    public static ParamPolicy empty() {
        return new ParamPolicy(Map.of());
    }

    /**
     * 守卫判定：解析参数 → 逐个参数取最严裁决。
     *
     * @param event 守卫事件
     * @return 判定结果
     */
    @Override
    public GuardDecision check(GuardEvent event) {
        if (event == null) {
            return GuardDecision.deny("守卫事件为空");
        }
        Map<String, Verdict> toolRules = rules.get(event.toolName());
        if (toolRules == null || toolRules.isEmpty()) {
            return GuardDecision.allow();
        }
        Map<String, Object> payload;
        try {
            payload = snapshot(event.args());
        } catch (Exception e) {
            return GuardDecision.deny("参数解析失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        Verdict worst = null;
        String worstParam = null;
        Verdict defaultVerdict = toolRules.get("*");
        for (Map.Entry<String, Object> param : payload.entrySet()) {
            Verdict verdict = toolRules.get(param.getKey());
            if (verdict == null) {
                verdict = defaultVerdict;
            }
            if (verdict == null) {
                continue;
            }
            if (worst == null || severity(verdict) > severity(worst)) {
                worst = verdict;
                worstParam = param.getKey();
            }
        }
        if (worst == null) {
            return GuardDecision.allow();
        }
        return switch (worst) {
            case DENY -> GuardDecision.deny("参数 " + worstParam + " 被策略拒绝");
            case ASK -> GuardDecision.ask("参数 " + worstParam + " 需要人工确认");
            case ALLOW -> GuardDecision.allow();
        };
    }

    /**
     * 参数载荷的只读快照。
     *
     * @param args 参数 JSON 原文
     * @return 不可变映射（顶层对象）
     * @throws Exception 参数不是合法 JSON 对象
     */
    public Map<String, Object> snapshot(String args) throws Exception {
        JsonNode node = MAPPER.readTree(args == null ? "" : args);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("参数不是 JSON 对象");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        node.properties().forEach(entry -> payload.put(entry.getKey(), toPlain(entry.getValue())));
        return Collections.unmodifiableMap(payload);
    }

    private static Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            node.properties().forEach(entry -> nested.put(entry.getKey(), toPlain(entry.getValue())));
            return Collections.unmodifiableMap(nested);
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            node.forEach(child -> list.add(toPlain(child)));
            return Collections.unmodifiableList(list);
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        return node.asText();
    }

    private static int severity(Verdict verdict) {
        return switch (verdict) {
            case ALLOW -> 0;
            case ASK -> 1;
            case DENY -> 2;
        };
    }

    /** 构造器。 */
    public static final class Builder {

        private final Map<String, Map<String, Verdict>> rules = new LinkedHashMap<>();

        /** 构造器。 */
        Builder() {
        }

        /**
         * 给某个工具的某个参数定裁决。
         *
         * @param toolName 工具名
         * @param param    参数名（顶层字段）
         * @param verdict  裁决
         * @return this
         */
        public Builder rule(String toolName, String param, Verdict verdict) {
            rules.computeIfAbsent(toolName, key -> new LinkedHashMap<>()).put(param, verdict);
            return this;
        }

        /**
         * 整个工具的默认裁决（该工具的每个参数都适用）。
         *
         * @param toolName 工具名
         * @param verdict  裁决
         * @return this
         */
        public Builder tool(String toolName, Verdict verdict) {
            rules.computeIfAbsent(toolName, key -> new LinkedHashMap<>()).put("*", verdict);
            return this;
        }

        /** 构造完成。 */
        public ParamPolicy build() {
            Map<String, Map<String, Verdict>> copy = new LinkedHashMap<>();
            rules.forEach((tool, params) -> copy.put(tool, Collections.unmodifiableMap(new LinkedHashMap<>(params))));
            return new ParamPolicy(Collections.unmodifiableMap(copy));
        }
    }
}
