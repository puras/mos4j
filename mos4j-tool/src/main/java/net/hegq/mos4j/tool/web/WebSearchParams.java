package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.ToolParam;

import java.util.List;

/**
 * web 参数容器（供 Jackson 反射；searchtools 子包之外的共享词法）。
 */
public record WebSearchParams(
        @ToolParam(desc = "搜索关键词", required = true) String query,
        @ToolParam(desc = "最多返回几条结果") Integer limit) {

    /** 空参（Jackson 反序列化失败时的兜底形状）。 */
    public static WebSearchParams empty() {
        return new WebSearchParams(null, null);
    }

    /** 搜索结果（provider 归一化后返回）。 */
    public record SearchResultShape(String title, String url, String snippet) {

        /** 一条结果。 */
        public static List<SearchResultShape> none() {
            return List.of();
        }
    }
}
