package net.hegq.mos4j.model.catalog;

import net.hegq.mos4j.model.ModelKind;
import net.hegq.mos4j.model.chat.ChatModel;

/**
 * 模型目录里的一项（对齐 Go 参考实现 {@code mos.ModelOption}）。
 *
 * <p><b>目录是「会话里可切换的候选」</b>：宿主给一份，模型可以在运行期换（下一轮生效），
 * 不用重建智能体。
 *
 * <p><b>kind 与句柄一起报</b>：{@link #kind()} 说明这一项是什么种类的模型，句柄类型由
 * {@code T} 承担——两者由工厂方法（{@link #chat}）保证一致，不给「kind 写 CHAT 却塞了
 * 别的句柄」留缝。将来加 embedding / rerank 等 kind 时，各自加一个工厂方法即可，
 * 「{@code ModelKind} + 泛型句柄」不用再动。
 *
 * <p><b>凭据只留在模型对象里</b>：{@link #model()} 里可能有 API key，但目录对外只报
 * kind / ID / 展示名——快照与 Describe 里不会出现凭据。
 *
 * @param kind  模型种类（不能为空）
 * @param id    稳定标识（同一目录内唯一；换模型时用它指认）
 * @param label 展示名（宿主 UI 用；空 = 用 ID）
 * @param model 模型对象（不能为空）
 */
public record ModelOption<T>(ModelKind kind, String id, String label, T model) {

    /** 构造（校验 + 标签缺省）。 */
    public ModelOption {
        if (kind == null) {
            throw new IllegalArgumentException("llm: 模型目录里有项没有 kind");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("llm: 模型目录里有项没有 ID");
        }
        if (model == null) {
            throw new IllegalArgumentException("llm: 模型 " + id + " 没有模型对象");
        }
        if (label == null || label.isBlank()) {
            label = id;
        }
    }

    /**
     * chat 目录项（标签缺省为 ID）。
     *
     * @param id    模型标识
     * @param model 模型对象
     * @return 目录项
     */
    public static ModelOption<ChatModel> chat(String id, ChatModel model) {
        return chat(id, id, model);
    }

    /**
     * chat 目录项。
     *
     * @param id    模型标识
     * @param label 展示名
     * @param model 模型对象
     * @return 目录项
     */
    public static ModelOption<ChatModel> chat(String id, String label, ChatModel model) {
        return new ModelOption<>(ModelKind.CHAT, id, label, model);
    }
}
