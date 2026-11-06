package net.hegq.mos4j.tool;

/**
 * 工具规范（T5.10）：跨调用约定进系统提示词。
 *
 * <p><b>与 Description 的分工</b>：单次调用怎么传参归 {@link Tool#description()}
 * （进工具定义，跟着 schema 走）；「什么时候该用 / 用完之后干嘛 / 与其他工具怎么配合」
 * 这类<b>跨调用</b>约定归 Guideline（进系统提示词）。
 * 塞在一起的问题：工具清单每轮都发，约定跟着每轮重复且占用上下文；
 * 分开后 Description 保持精短，约定由宿主按需装配进系统提示词。
 *
 * <p>宿主实现本接口（一个工具可以没有 guideline），驱动 / 宿主把
 * {@code guideline()} 文本拼进系统提示词的 {@code tools} 段。
 */
public interface Guideline {

    /** 跨调用指引进系统提示词的段落文本。 */
    String guideline();

    /**
     * 带工具名的规范（默认用工具名开头，便于模型对上号）。
     *
     * @return 渲染后的段落
     */
    default String render() {
        return "- " + guideline();
    }
}
