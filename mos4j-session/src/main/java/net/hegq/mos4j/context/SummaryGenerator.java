package net.hegq.mos4j.context;

import net.hegq.mos4j.model.Usage;

/**
 * 摘要生成器（接口）。
 *
 * <p><b>为什么是接口</b>：摘要这一步要花钱、要调模型、要能被测试替身。
 * 把它做成接口，压缩流程（触发、切割、落库）就能在不调模型的情况下测，
 * 也能让宿主换成自己的摘要实现（比如本地小模型）。
 */
public interface SummaryGenerator {

    /**
     * 生成摘要。
     *
     * @param transcript      待摘要的对话文本（{@link Transcript} 渲染结果）
     * @param previousSummary 上一轮摘要（可空 = 没有）
     * @return 摘要结果（文本 + 本次调用用量）
     * @throws Exception 生成失败（压缩流程会把失败如实抛给宿主，不假装压过）
     */
    Summary generate(String transcript, String previousSummary) throws Exception;

    /**
     * 摘要结果。
     *
     * @param text  摘要文本
     * @param usage 本次调用的用量（可空 = 服务商没返回）
     */
    record Summary(String text, Usage usage) {
    }
}
