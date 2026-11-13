package net.hegq.mos4j.tool.web;

import java.util.List;

/**
 * 搜索服务商适配（web_search 的 provider 接口，T5.8）。
 *
 * <p><b>SDK 不绑死任何搜索服务商</b>（运行时依赖纪律）；宿主接自家的搜索网关。
 * 归一化词汇表：{@link SearchResult} 是两侧的通用形状。
 *
 * <p><b>为什么入参是查询列表</b>（对齐 Go 的 {@code Searcher.Search(ctx, queries, limit)}）：
 * 模型一次调用可以给多条查询（{@code queries[]}），工具层把多条一起交给 provider，
 * 由 provider 决定串行还是并行。厂商 API 基本都是「一次一个 query」，所以：
 * <ul>
 *   <li>只想接单条厂商 API → 实现 {@link Single}，交给
 *       {@link WebSearchTool#of(SearchProvider.Single)} 装配（框架把多条查询并行发出、
 *       按 URL 去重合并）；</li>
 *   <li>自家网关本来就支持批量 → 直接实现 {@link SearchProvider}。</li>
 * </ul>
 * 这条形状的代价是 provider 要自己处理列表；收益是「多查询并行」这一层语义不会被
 * 工具层的形状挡在门外（Go 侧同样是这个取舍）。
 */
public interface SearchProvider {

    /**
     * 搜索。
     *
     * @param queries 查询词（已去空白、去重、按首次出现顺序，非空；条数已按上限校验）
     * @param limit   最多返回几条来源（已按上限钳制）
     * @return 归一化结果（不可为 null）
     * @throws Exception 后端整体失败（工具层转成 {@code web_search 失败}）
     */
    Outcome search(List<String> queries, int limit) throws Exception;

    /**
     * 一次搜索的归一化结果（对齐 Go 的 {@code SearchResult}）。
     *
     * @param summary   服务商生成的答案摘要（可选：有些厂商只给来源列表）
     * @param note      给我们自己的附注（可选：比如「以下查询失败：[...]」）——
     *                  与 {@code summary} 分开：summary 是<b>别人的答案</b>，
     *                  note 是<b>我们的说明</b>，别混
     * @param sources   来源列表
     * @param truncated 来源是否被截断（到上限）
     */
    record Outcome(String summary, String note, List<SearchResult> sources, boolean truncated) {

        /** 空结果。 */
        public static Outcome empty() {
            return new Outcome("", "", List.of(), false);
        }

        /** 只要来源列表的便捷构造。 */
        public static Outcome of(List<SearchResult> sources) {
            return new Outcome("", "", sources == null ? List.of() : sources, false);
        }
    }

    /**
     * 一条可引用的来源（对齐 Go 的 {@code Source}）。
     *
     * <p><b>可选字段缺失就缺，别编</b>：provider 没给标题就留空，
     * 让模型看见「只有 URL」，比编一个假标题诚实得多。
     *
     * @param title       标题（可选）
     * @param url         来源地址（必填——没有 URL 的来源不可引用）
     * @param snippet     摘要（可选）
     * @param publishedAt 发布时间（可选，provider 原样给的字符串）
     */
    record SearchResult(String title, String url, String snippet, String publishedAt) {

        /** 只有标题 / 地址 / 摘要的便捷构造（无发布时间）。 */
        public SearchResult(String title, String url, String snippet) {
            this(title, url, snippet, null);
        }
    }

    /**
     * 单条查询的搜索后端（大多数厂商 API 的形状，对齐 Go 的 {@code Provider}）。
     *
     * <p>把它交给 {@link WebSearchTool#of(SearchProvider.Single)} 即可获得多查询能力：
     * 多条查询<b>并行</b>发出（各自独立），结果按 URL 去重合并、最后截到 {@code limit}。
     * 并行是刻意的——4 条查询串行要等 4 个 RTT，并行后只等最慢的那条。
     */
    interface Single {

        /**
         * 搜一条。
         *
         * @param query 单个关键词
         * @param limit 最多几条
         * @return 归一化结果（空列表 = 没搜到）
         * @throws Exception 服务返回非 2xx 或网络异常
         */
        List<SearchResult> search(String query, int limit) throws Exception;
    }
}