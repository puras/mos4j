package net.hegq.mos4j.model;

/**
 * 模型目录里的一项（对齐 Go 参考实现 {@code mos.ModelOption}）。
 *
 * <p><b>目录是「会话里可切换的候选」</b>：宿主给一份，模型可以在运行期换（下一轮生效），
 * 不用重建智能体。
 *
 * <p><b>凭据只留在模型对象里</b>：{@link #chat()} 里可能有 API key，但目录对外只报
 * ID 与展示名——快照与 Describe 里不会出现凭据。
 *
 * @param id 稳定标识（同一目录内唯一；换模型时用它指认）
 * @param label 展示名（宿主 UI 用；空 = 用 ID）
 * @param chat 模型对象
 */
public record ModelOption(String id, String label, ChatModel chat) {

    /** 构造（校验 + 标签缺省）。 */
    public ModelOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("llm: 模型目录里有项没有 ID");
        }
        if (chat == null) {
            throw new IllegalArgumentException("llm: 模型 " + id + " 没有 Chat 模型");
        }
        if (label == null || label.isBlank()) {
            label = id;
        }
    }

    /**
     * 构造（标签缺省为 ID）。
     *
     * @param id 模型标识
     * @param chat 模型对象
     */
    public ModelOption(String id, ChatModel chat) {
        this(id, id, chat);
    }
}
