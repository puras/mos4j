package net.hegq.mos4j.tool;

import java.util.Map;

/**
 * 由 lambda 构造的工具（最常见的装配方式）。
 *
 * <p>执行器拿到的是<b>原始 JSON 文本</b>——参数解析留给工具自己（每个工具的参数类型不同，
 * 框架不该替它猜）。</p>
 */
public class FunctionTool implements Tool {

    /** 工具执行体。 */
    @FunctionalInterface
    public interface Executor {
        /**
         * @param argsJson 参数 JSON 原文（已做容错修复）
         * @return 结果
         */
        Object apply(String argsJson) throws Exception;
    }

    private final String name;
    private final String description;
    private final Class<?> parameterType;
    private final Map<String, Object> schema;
    private final Executor executor;

    private FunctionTool(String name, String description, Class<?> parameterType,
                         Map<String, Object> schema, Executor executor) {
        this.name = name;
        this.description = description;
        this.parameterType = parameterType;
        this.schema = schema;
        this.executor = executor;
    }

    /** 由参数类型反射生成 schema 的工具。 */
    public static FunctionTool of(String name, String description, Class<?> parameterType, Executor executor) {
        return new FunctionTool(name, description, parameterType, null, executor);
    }

    /** 直接给 schema 的工具（不走反射）。 */
    public static FunctionTool withSchema(String name, String description, Map<String, Object> schema,
                                          Executor executor) {
        return new FunctionTool(name, description, null, schema, executor);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Class<?> parameterType() {
        return parameterType;
    }

    @Override
    public Map<String, Object> schema() {
        return schema;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        return executor.apply(argsJson);
    }
}
