package net.hegq.mos4j.model;

/**
 * 带 HTTP 状态码的模型调用错误。
 *
 * <p><b>为什么要有它</b>：错误文本是拍平的字符串——「openai: Rate limited」与
 * 「openai: bad request」分不出该重试还是该放弃。分类信息一旦在适配器层丢了，
 * 上层只能瞎重试（浪费配额、放大故障）或瞎放弃（一次瞬时抖动直接变成整轮失败）。
 * 结构化字段让重试策略、指标、审计都拿到<b>事实</b>，而不是去 parse 字符串。</p>
 */
public class StatusError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String provider;
    private final int statusCode;

    /**
     * @param provider   协议提供方（openai / anthropic / gemini…）
     * @param statusCode HTTP 状态码（0 = 无状态码）
     * @param message    服务端返回的错误信息（未解析时是响应体摘要）
     */
    public StatusError(String provider, int statusCode, String message) {
        super(provider + ": " + message);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public StatusError(String provider, int statusCode, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    /** 协议提供方。 */
    public String provider() {
        return provider;
    }

    /** HTTP 状态码（0 = 无状态码）。 */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 取出错误链里的 HTTP 状态码；不是状态错误时返回 0。
     *
     * <p>用法：{@code if (statusCodeOf(err) == 429) { … }}。</p>
     */
    public static int statusCodeOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof StatusError se) {
                return se.statusCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return 0;
    }
}
