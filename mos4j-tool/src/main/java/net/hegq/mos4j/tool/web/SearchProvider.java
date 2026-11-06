package net.hegq.mos4j.tool.web;

import java.util.List;

/**
 * 搜索服务商适配（web_search 的 provider 接口，T5.8）。
 *
 * <p>SDK 不绑死任何搜索服务商（运行时依赖纪律）；宿主接自家的搜索网关。
 * 归一化词汇表：{@link SearchResult} 是两侧的通用形状。
 */
public interface SearchProvider {

    /**
     * 搜索。
     *
     * @param query 关键词
     * @param limit 最多几条
     * @return 归一化结果
     * @throws RuntimeException 服务返回非 2xx 或网络异常（工具层转成「搜索失败」结果，不中断回合）
     */
    List<SearchResult> search(String query, int limit) throws Exception;

    /** 归一化搜索结果。 */
    record SearchResult(String title, String url, String snippet) {
    }
}
