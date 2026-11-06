package net.hegq.mos4j.tool.skill;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import net.hegq.mos4j.tool.ToolException;

/**
 * skill 子包的参数解析（与 builtin 包的 BuiltinArgs、searchtools 包的 SearchArgs 同一纪律：
 * 容忍多余字段）。
 *
 * <p>那两处是包私有的，这里是同一套口径的独立副本——共享就得把它升成公开 API，
 * 而参数解析属于各工具包的实现细节。
 */
final class SkillArgs {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SkillArgs() {
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
