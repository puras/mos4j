package net.hegq.mos4j.llm;

import java.io.UncheckedIOException;

/**
 * 缺省的「瞬时错误」分类器（T6.5）。
 *
 * <p><b>口径</b>：429（限流）、5xx（服务端）、408（请求超时）、网络层 IOException、
 * 空响应是瞬时的；其余 4xx（参数/鉴权/协议错误）、{@link StreamTruncatedException}
 * （流完整性被破坏——M5，重试掩盖不了）不重试。
 * <b>取消（{@code CancellationException}）与 {@link StreamAbortedException}
 * （宿主主动中断输出）是终止性的</b>：重试它们等于违抗调用方。</p>
 */
final class RetryPolicies {

    private RetryPolicies() {
    }

    /** 缺省瞬时判断。 */
    static boolean defaultTransient(Throwable error) {
        if (error == null) {
            return false;
        }
        // 终止性优先：取消/宿主中断/截断一律不重试
        if (error instanceof java.util.concurrent.CancellationException
                || error instanceof InterruptedException
                || error instanceof StreamAbortedException
                || error instanceof StreamTruncatedException) {
            return false;
        }
        if (error instanceof EmptyResponseException) {
            return true;
        }
        if (error instanceof UncheckedIOException) {
            return true;
        }
        if (error instanceof StatusError statusError) {
            int code = statusError.statusCode();
            return code == 408 || code == 429 || (code >= 500 && code <= 599);
        }
        return false;
    }
}
