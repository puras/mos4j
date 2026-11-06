package net.hegq.mos4j.tool.builtin;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import net.hegq.mos4j.tool.ToolException;

/**
 * 内置工具共用的参数解析。
 *
 * <p><b>容忍多余字段</b>：模型偶尔会带上 schema 里没有的字段，
 * 直接失败等于让整条工具链路因为一个无关字段断掉——但<b>缺字段</b>要报错
 * （缺字段是语义错误，不能猜）。
 */
final class BuiltinArgs {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private BuiltinArgs() {
    }

    /**
     * 解析参数。
     *
     * @param argsJson 参数 JSON 原文
     * @param type     参数类型
     * @param <T>      参数类型
     * @return 参数对象
     */
    static <T> T parse(String argsJson, Class<T> type) {
        if (argsJson == null || argsJson.isBlank()) {
            throw new ToolException("参数为空");
        }
        try {
            T value = MAPPER.readValue(argsJson, type);
            if (value == null) {
                throw new ToolException("参数为空");
            }
            return value;
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("参数解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读成 JSON 树。
     *
     * <p><b>给「参数形状有多种可能」的工具用</b>：同一个参数可能来成数组、来成单个对象、
     * 甚至来成一段 JSON 字符串（有些模型就是这么发的）。先把树拿到手，再按工具自己的规则
     * 归一，比让 Jackson 直接报错让模型重试一轮划算。
     *
     * @param argsJson 参数 JSON 原文
     * @return JSON 树
     */
    static JsonNode readTree(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            throw new ToolException("参数为空");
        }
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            if (node == null || node.isNull()) {
                throw new ToolException("参数为空");
            }
            return node;
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("参数解析失败: " + e.getMessage(), e);
        }
    }
}
