package net.hegq.mos4j.tool;

import java.util.Map;

/**
 * 可被 LLM 调用的工具。
 *
 * <p>工具只做「被调用」；安全约束（策略 / 沙箱 / 文件 jail）在驱动层与 fs / sandbox 模块。</p>
 */
public interface Tool {

    /** 工具名（模型调用时用；注册表内唯一）。 */
    String name();

    /** 工具说明（模型据此决定调用与否）。 */
    String description();

    /**
     * 参数类型（record / POJO 类），框架据此反射生成 JSON Schema。
     *
     * <p>返回 {@code null} 表示「不用反射」——此时用 {@link #schema()} 直接给 schema。</p>
     */
    default Class<?> parameterType() {
        return null;
    }

    /**
     * 直接给参数 schema（优先于 {@link #parameterType()} 反射）。
     *
     * <p>返回 {@code null} 表示按 {@link #parameterType()} 反射生成。</p>
     */
    default Map<String, Object> schema() {
        return null;
    }

    /**
     * 执行调用。
     *
     * @param argsJson 模型传来的参数（<b>原始 JSON 文本</b>；容错修复后）
     * @return 结果（会被转成文本回填给模型）
     * @throws Exception 工具自身失败（驱动层转成失败回执）
     */
    Object execute(String argsJson) throws Exception;

    /**
     * 本次调用是否可与同批其他调用并发（可选，默认<b>不可</b>）。
     *
     * <p><b>并发是显式 opt-in</b>：只有显式返回 {@code true} 才并发；
     * 未实现、参数非法、返回 false、甚至抛异常，一律按<b>独占</b>（串行）处理——
     * 并发是 fail-closed 的。返回 true 是工具的承诺：这次调用与同批其他并发调用不冲突。</p>
     *
     * <p>并发只影响<b>完成顺序</b>，不影响回填顺序——结果仍按模型的调用序回填。</p>
     *
     * @param argsJson 本次调用的参数 JSON 原文
     */
    default boolean concurrencySafe(String argsJson) {
        return false;
    }
}
