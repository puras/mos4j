package net.hegq.mos4j.tool.web;

import java.time.Duration;

/**
 * 抓取的资源上限（对齐 Go {@code web.FetchLimits}）。
 *
 * <p>这些上限属于<b>抓取后端</b>而不是工具：传输层才是唯一能强制它们的地方
 * （字节上限要在读流时逐块判断，超时要在 socket 上设，重定向跳数要在跟随时数）。
 * 工具层只保留一个「回执正文字符上限」，因为它管的是「给模型看多少」。
 *
 * <p>零值/负值一律取缺省（对齐 Go 的 {@code limits()} 归一化），不做静默的
 * 「0 就是不许」——那会让宿主写出意外失效的限制。
 *
 * @param maxResponseBytes 响应体字节上限（{@code <=0} → 5MB）
 * @param maxContentChars  转成纯文本后的字符上限（{@code <=0} → 100000）
 * @param timeout          单次抓取超时（{@code null} 或非正 → 30s）
 * @param maxRedirects     允许跟随的同源重定向跳数（{@code <=0} → 5）
 * @param userAgent        请求头（空 → 显式产品标识，不伪装浏览器）
 */
public record FetchLimits(long maxResponseBytes, int maxContentChars, Duration timeout,
                         int maxRedirects, String userAgent) {

    /** 缺省响应体字节上限（对齐 Go {@code defaultMaxResponseBytes}：5MB）。 */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 5L << 20;

    /** 缺省纯文本字符上限（对齐 Go {@code defaultMaxContentChars}）。 */
    public static final int DEFAULT_MAX_CONTENT_CHARS = 100_000;

    /** 缺省超时（对齐 Go {@code defaultFetchTimeout}）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** 缺省重定向跳数（对齐 Go {@code defaultMaxRedirects}）。 */
    public static final int DEFAULT_MAX_REDIRECTS = 5;

    /**
     * 缺省 User-Agent（对齐 Go {@code defaultFetchUserAgent} 的格式：显式产品标识 + 仓库地址，
     * 不伪装浏览器）。仓库是 mos4j，所以 URL 也写 mos4j —— 产品名差异不是缺陷。
     */
    public static final String DEFAULT_USER_AGENT = "mos-web-fetch/1.0 (+https://github.com/puras/mos4j)";

    /** 全缺省上限。 */
    public static FetchLimits defaults() {
        return new FetchLimits(DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_CONTENT_CHARS,
                DEFAULT_TIMEOUT, DEFAULT_MAX_REDIRECTS, DEFAULT_USER_AGENT);
    }

    /** 归一化：非正值/缺省值换成缺省（对齐 Go {@code HTTPFetcher.limits()}）。 */
    public FetchLimits normalized() {
        return new FetchLimits(
                maxResponseBytes <= 0 ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes,
                maxContentChars <= 0 ? DEFAULT_MAX_CONTENT_CHARS : maxContentChars,
                timeout == null || timeout.isZero() || timeout.isNegative()
                        ? DEFAULT_TIMEOUT : timeout,
                maxRedirects <= 0 ? DEFAULT_MAX_REDIRECTS : maxRedirects,
                userAgent == null || userAgent.isEmpty() ? DEFAULT_USER_AGENT : userAgent);
    }
}