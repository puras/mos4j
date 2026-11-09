package net.hegq.mos4j.agent;

import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ModelOption;
import net.hegq.mos4j.model.SwitchableChatModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录（对齐 Go 参考实现 {@code mos.modelCatalog}）。
 *
 * <p><b>fail-closed</b>：ID 为空、模型为 null、ID 重复、默认 ID 不在目录里——一律报错。
 * 「半截的目录」比「没有目录」更难查。
 *
 * <p><b>切换语义</b>：{@link #set(String)} 换的是转发壳的目标，<b>下一轮生效</b>；
 * 正在跑的那一轮不受影响（它已经取过目标）。
 */
public final class ModelCatalog {

    private final Map<String, ModelOption> byId = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final SwitchableChatModel shell;
    private String currentId;

    /**
     * 校验并装配目录。
     *
     * @param options 目录项（保序）
     * @param defaultId 默认模型 ID（空 = 第一项）
     * @throws IllegalArgumentException 目录不合法
     */
    public ModelCatalog(List<ModelOption> options, String defaultId) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("mos4j: 模型目录不能为空");
        }
        for (ModelOption option : options) {
            if (option == null) {
                throw new IllegalArgumentException("mos4j: 模型目录里有项是 null");
            }
            if (byId.containsKey(option.id())) {
                throw new IllegalArgumentException("mos4j: 模型目录里 ID 重复：" + option.id());
            }
            byId.put(option.id(), option);
            order.add(option.id());
        }
        String initial = order.get(0);
        if (defaultId != null && !defaultId.isBlank()) {
            if (!byId.containsKey(defaultId)) {
                throw new IllegalArgumentException("mos4j: 默认模型 " + defaultId + " 不在目录里");
            }
            initial = defaultId;
        }
        this.currentId = initial;
        this.shell = new SwitchableChatModel(byId.get(initial).chat());
    }

    /**
     * 换当前模型（下一轮生效）。
     *
     * @param id 模型 ID
     * @throws IllegalArgumentException ID 不在目录里（不静默无效）
     */
    public void set(String id) {
        ModelOption option = byId.get(id);
        if (option == null) {
            throw new IllegalArgumentException("mos4j: 模型 " + id + " 不在目录里");
        }
        currentId = id;
        shell.set(option.chat());
    }

    /** 当前模型 ID。 */
    public String currentId() {
        return currentId;
    }

    /** 目录（保序）。 */
    public List<ModelOption> list() {
        List<ModelOption> out = new ArrayList<>(order.size());
        for (String id : order) {
            out.add(byId.get(id));
        }
        return List.copyOf(out);
    }

    /**
     * 按 ID 取模型（子代理声明模型覆盖时用）。
     *
     * <p>ID 不在目录里直接报错（不静默降级到当前模型：降级等于让模型以为自己在用 A、
     * 实际在用 B）。
     *
     * @param id 模型 ID
     * @return 模型
     * @throws IllegalArgumentException ID 不在目录里
     */
    public ChatModel chat(String id) {
        ModelOption option = byId.get(id);
        if (option == null) {
            throw new IllegalArgumentException("mos4j: 模型 " + id + " 不在目录里");
        }
        return option.chat();
    }

    /**
     * 转发壳（装配时交给驱动；运行期换模型只换壳里的目标）。
     *
     * @return 转发壳
     */
    public ChatModel shell() {
        return shell;
    }
}
