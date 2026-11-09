package net.hegq.mos4j.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型提供方注册表：<b>名字 → 工厂</b>的映射（对齐 Go 参考实现 {@code llm/registry.go}）。
 *
 * <p><b>它只做映射</b>：注册表不认识协议差异，也不装横切能力——
 * 产出的是<b>裸模型</b>；日志 / 超时 / 降级 / 缓存这些由调用方用装饰器显式叠加
 * （见 {@code net.hegq.mos4j.model.decorate}）。
 *
 * <p><b>为什么需要它</b>：宿主往往要在「运行期按名字选一套模型装配」
 * （配置文件里写 {@code provider=openai}，代码里不写死）。没有注册表就得自己维护
 * 一张 {@code if/else}，每加一家协议改一处。
 *
 * <p><b>失败取向</b>：名字未注册 → 直接报错并列出已注册的名字（fail-closed，
 * 不静默挑一个默认协议——挑错了就是拿密钥打错的服务端）。
 *
 * <p><b>线程安全</b>：注册与创建可并发（用 {@link ConcurrentHashMap}）。
 */
public final class ModelRegistry {

    /** 按配置创建模型。 */
    @FunctionalInterface
    public interface Factory {

        /**
         * @param config 配置（键名约定：{@code api_key} / {@code base_url} / {@code model} 等）
         * @return 裸模型（不带装饰器）
         * @throws Exception 配置不合法 / 装配失败
         */
        ChatModel create(Map<String, String> config) throws Exception;
    }

    private final Map<String, Factory> factories = new ConcurrentHashMap<>();

    /**
     * 注册一个提供方工厂（同名覆盖）。
     *
     * @param name    提供方名字（如 {@code openai}）
     * @param factory 工厂
     */
    public void register(String name, Factory factory) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("llm: 提供方名字不能为空");
        }
        if (factory == null) {
            throw new IllegalArgumentException("llm: 提供方工厂不能为 null");
        }
        factories.put(name, factory);
    }

    /**
     * 按名字创建模型。
     *
     * @param name   提供方名字
     * @param config 配置（可空 = 空配置）
     * @return 裸模型
     * @throws IllegalArgumentException 名字未注册（fail-closed）
     * @throws Exception                 工厂装配失败
     */
    public ChatModel create(String name, Map<String, String> config) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("llm: 提供方名字不能为空");
        }
        Factory factory = factories.get(name);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "llm: 未注册的提供方 \"" + name + "\"，已注册: " + names());
        }
        return factory.create(config == null ? Map.of() : config);
    }

    /**
     * 已注册的提供方名字（按字典序）。
     *
     * @return 名字列表
     */
    public List<String> names() {
        List<String> names = new ArrayList<>(factories.keySet());
        java.util.Collections.sort(names);
        return List.copyOf(names);
    }

    /** 是否注册了某个提供方。 */
    public boolean contains(String name) {
        return name != null && factories.containsKey(name);
    }

    /** 已注册的提供方数量。 */
    public int size() {
        return factories.size();
    }
}
