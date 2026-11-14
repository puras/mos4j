package net.hegq.mos4j.tool.web;

/**
 * 一次抓取的最终结果（对齐 Go {@code web.FetchResult} 结构体）。
 *
 * <p><b>非 2xx 也是结果，不是错误</b>：404 是「这个资源的状态」，不是抓取失败。
 * 所以状态码在这里，而不是靠异常表达。只有传输层故障（DNS / 连接 / 超时）
 * 与非文本内容才抛异常。
 *
 * @param url         最终地址（跟随允许的重定向之后）
 * @param statusCode  HTTP 状态码
 * @param title       页面标题（非 HTML 或没有标题时为空串）
 * @param contentType 原始 Content-Type 头（含 charset 参数，原样透传）
 * @param content     正文（HTML 已转纯文本；受字符上限与字节上限约束）
 * @param truncated   是否被响应体字节上限或字符上限截断
 */
public record FetchResult(String url, int statusCode, String title, String contentType,
                          String content, boolean truncated) {
}