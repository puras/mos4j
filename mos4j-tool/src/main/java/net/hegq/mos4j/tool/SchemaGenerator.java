package net.hegq.mos4j.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由参数类型反射生成 JSON Schema（对应 Go 侧 {@code generateSchema}）。
 *
 * <p><b>三条硬规则</b>：</p>
 * <ol>
 *   <li><b>无 title / 无 $defs</b>：schema 是给模型看的，title 是噪声，
 *       {@code $defs} 会让部分服务端拒绝。</li>
 *   <li><b>必填只由 {@link ToolParam#required()} 显式声明</b>（不按「非装箱类型即必填」推断）。</li>
 *   <li><b>反射失败 fail-closed</b>：抛异常让注册失败，
 *       <b>绝不</b>静默生成空 schema——空 schema 等于告诉模型「随便传」，
 *       工具内部再按错误类型解析，错误会漂移到执行期。</li>
 * </ol>
 */
public final class SchemaGenerator {

    private SchemaGenerator() {
    }

    /**
     * 生成 schema。
     *
     * @param type 参数类型（record / POJO）
     * @return JSON Schema 对象
     * @throws ToolException 反射失败（fail-closed）
     */
    public static Map<String, Object> generate(Class<?> type) {
        if (type == null) {
            throw new ToolException("tool: 参数类型为空，无法生成 schema");
        }
        if (type == Object.class || type == Void.class) {
            return Map.of("type", "object");
        }
        if (type.isRecord()) {
            return recordSchema(type);
        }
        if (type.getDeclaredFields().length == 0) {
            return Map.of("type", "object");
        }
        // 非 record 的 POJO：按字段反射（只认非静态字段）
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (var field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            ToolParam param = field.getAnnotation(ToolParam.class);
            String name = jsonName(field.getAnnotation(JsonProperty.class), field.getName());
            properties.put(name, fieldSchema(field.getGenericType(), param));
            if (param != null && param.required()) {
                required.add(name);
            }
        }
        return assemble(properties, required);
    }

    private static Map<String, Object> recordSchema(Class<?> type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            ToolParam param = component.getAnnotation(ToolParam.class);
            JsonProperty jsonProperty = component.getAnnotation(JsonProperty.class);
            String name = jsonProperty != null && !jsonProperty.value().isEmpty()
                    ? jsonProperty.value() : component.getName();
            properties.put(name, fieldSchema(component.getGenericType(), param));
            if (param != null && param.required()) {
                required.add(name);
            }
        }
        return assemble(properties, required);
    }

    private static Map<String, Object> assemble(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static String jsonName(JsonProperty jsonProperty, String fallback) {
        if (jsonProperty != null && !jsonProperty.value().isEmpty()) {
            return jsonProperty.value();
        }
        return fallback;
    }

    /** 单个字段的 schema 片段。 */
    private static Map<String, Object> fieldSchema(Type type, ToolParam param) {
        Map<String, Object> schema = typeSchema(type);
        if (param != null && !param.desc().isEmpty()) {
            schema.put("description", param.desc());
        }
        return schema;
    }

    private static Map<String, Object> schema(String key, Object value) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(key, value);
        return schema;
    }

    /** 类型 → schema 片段（未知类型降级为宽松 schema）。 */
    private static Map<String, Object> typeSchema(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz == String.class || clazz == CharSequence.class) {
                return schema("type", "string");
            }
            if (clazz == boolean.class || clazz == Boolean.class) {
                return schema("type", "boolean");
            }
            if (clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class
                    || clazz == short.class || clazz == Short.class || clazz == byte.class || clazz == Byte.class) {
                return schema("type", "integer");
            }
            if (clazz == double.class || clazz == Double.class || clazz == float.class || clazz == Float.class) {
                return schema("type", "number");
            }
            if (clazz.isArray()) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                schema.put("items", typeSchema(clazz.getComponentType()));
                return schema;
            }
            if (Map.class.isAssignableFrom(clazz)) {
                return new LinkedHashMap<>(); // 宽松：map 的键值类型不固定
            }
            if (clazz.isEnum()) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "string");
                List<String> values = new ArrayList<>();
                for (Object constant : clazz.getEnumConstants()) {
                    values.add(String.valueOf(constant));
                }
                schema.put("enum", values);
                return schema;
            }
            if (clazz.isRecord()) {
                return generate(clazz);
            }
            return new LinkedHashMap<>(); // 未知类型 → 宽松
        }
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (Collection.class.isAssignableFrom(raw)) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                Type item = parameterized.getActualTypeArguments().length > 0
                        ? parameterized.getActualTypeArguments()[0] : null;
                schema.put("items", item == null ? Map.of() : typeSchema(item));
                return schema;
            }
            return typeSchema(raw);
        }
        return new LinkedHashMap<>(); // 泛型变量 / 通配符 → 宽松
    }
}
