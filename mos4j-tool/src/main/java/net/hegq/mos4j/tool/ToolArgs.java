package net.hegq.mos4j.tool;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数的容错解析（对应 Go 侧 {@code normalizeArgs}，机制 M9）。
 *
 * <p><b>为什么需要容错</b>：部分模型偶发输出 <code>{…}{…}</code> 这类多个 JSON 值
 * 拼接的参数（常为「一次想调多个工具」）。整体解析会失败——整条工具链路中断；
 * 只取第一个对象又会丢字段——写操作少参数比报错更危险。</p>
 *
 * <p><b>容错边界</b>：只合并<b>顶层对象</b>；合并后仍不是合法 JSON 就原样返回，
 * 让解析失败在工具侧显式报错（不静默丢字段、不猜测意图）。</p>
 */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgs() {
    }

    /**
     * 修复模型输出的非法参数 JSON。
     *
     * @param args 原始参数文本（可能为空）
     * @return 修复后的参数文本（合法时原样返回）
     */
    public static String normalize(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        String trimmed = args.trim();
        Map<String, Object> merged = new LinkedHashMap<>();
        int roots = 0;
        try (tools.jackson.databind.MappingIterator<JsonNode> nodes =
                     MAPPER.readerFor(JsonNode.class).readValues(trimmed)) {
            while (nodes.hasNext()) {
                JsonNode node = nodes.next();
                roots++;
                if (node == null || !node.isObject()) {
                    continue;
                }
                for (var field : node.properties()) {
                    merged.put(field.getKey(), MAPPER.convertValue(field.getValue(), Object.class));
                }
            }
        } catch (RuntimeException e) {
            // 根本不是 JSON → 原样返回，让工具侧显式报错。
            // Jackson 3 的解析失败全是 unchecked（JacksonException 继承 RuntimeException，
            // Jackson 2 里的 JsonProcessingException 已不复存在），所以这里只接 RuntimeException；
            // 只接 IOException 会让非法转义、截断 JSON 直接穿透到调用方（T6.7 fuzz 抓到的洞）。
            return trimmed;
        }
        if (roots <= 1) {
            // 合法且只有一个 JSON 值 → 原样返回（不重排、不丢字段）
            return trimmed;
        }
        if (merged.isEmpty()) {
            return trimmed;
        }
        try {
            return MAPPER.writeValueAsString(merged);
        } catch (JacksonException e) {
            return trimmed;
        }
    }
}
