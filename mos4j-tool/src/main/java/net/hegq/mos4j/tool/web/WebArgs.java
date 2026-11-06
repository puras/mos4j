package net.hegq.mos4j.tool.web;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import net.hegq.mos4j.tool.ToolException;

/**
 * web 子包的参数解析（与 builtin 的 BuiltinArgs 同一纪律：容忍多余字段）。
 */
final class WebArgs {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private WebArgs() {
    }

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
}
