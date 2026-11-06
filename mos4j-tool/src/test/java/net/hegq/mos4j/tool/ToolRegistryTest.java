package net.hegq.mos4j.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具注册表与 Schema 生成测试（T1.8 / T1.9）。
 */
class ToolRegistryTest {

    /** 参数示例（record + 注解）。 */
    record ReadArgs(
            @ToolParam(desc = "文件路径", required = true) @JsonProperty("path") String path,
            @ToolParam(desc = "最大行数") Integer limit) {
    }

    @Test
    void schemaIsGeneratedFromAnnotation() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读文件", ReadArgs.class, args -> "内容"));

        Map<String, Object> schema = registry.list().get(0).parameters();

        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> pathProp = (Map<String, Object>) properties.get("path");
        @SuppressWarnings("unchecked")
        Map<String, Object> limitProp = (Map<String, Object>) properties.get("limit");
        assertEquals("string", pathProp.get("type"));
        assertEquals("文件路径", pathProp.get("description"));
        assertEquals("integer", limitProp.get("type"));
        assertEquals(List.of("path"), schema.get("required"), "必填只由显式声明决定");
    }

    @Test
    void schemaHasNoTitleAndNoDefs() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读文件", ReadArgs.class, args -> "内容"));

        String json = new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(registry.list().get(0).parameters());

        assertFalse(json.contains("\"title\""), json);
        assertFalse(json.contains("$defs"), json);
    }

    @Test
    void schemaIsCachedAtRegistration() {
        AtomicInteger reflections = new AtomicInteger();
        net.hegq.mos4j.tool.Tool counting = new net.hegq.mos4j.tool.Tool() {
            @Override
            public String name() {
                return "counting";
            }

            @Override
            public String description() {
                return "计数";
            }

            @Override
            public Class<?> parameterType() {
                reflections.incrementAndGet();
                return ReadArgs.class;
            }

            @Override
            public Object execute(String argsJson) {
                return "ok";
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(counting);
        assertEquals(1, reflections.get(), "注册时生成一次");

        registry.list();
        registry.list();

        assertEquals(1, reflections.get(), "连续两轮 List() 不再反射");
    }

    @Test
    void reflectionFailureFailsRegistration() {
        net.hegq.mos4j.tool.Tool broken = new net.hegq.mos4j.tool.Tool() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public String description() {
                return "坏工具";
            }

            @Override
            public Class<?> parameterType() {
                throw new IllegalStateException("反射炸了");
            }

            @Override
            public Object execute(String argsJson) {
                return "ok";
            }
        };

        ToolRegistry registry = new ToolRegistry();

        ToolException error = assertThrows(ToolException.class, () -> registry.register(broken));
        assertTrue(error.getMessage().contains("broken"), error.getMessage());
    }

    @Test
    void duplicateRegistrationFails() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("a", "A", null, args -> "x"));

        assertThrows(ToolException.class,
                () -> registry.register(FunctionTool.of("a", "A2", null, args -> "y")));
    }

    @Test
    void unknownToolCallFailsClosed() {
        ToolRegistry registry = new ToolRegistry();

        assertThrows(ToolException.class, () -> registry.call("nope", "{}"));
    }

    @Test
    void argsAreNormalizedBeforeExecution() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("t", "T", null, args -> args));

        Object result = registry.call("t", "{\"a\":1}{\"b\":2}");

        assertEquals("{\"a\":1,\"b\":2}", result, "拼接的 JSON 对象应被合并成一个");
    }
}
