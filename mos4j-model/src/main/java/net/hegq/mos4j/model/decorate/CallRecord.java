package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;

import java.time.Duration;

/**
 * 一次模型调用的观测记录（供日志 / 指标回调）。
 *
 * @param request  请求
 * @param response 响应（失败时为 null）
 * @param error    失败原因（成功时为 null）
 * @param elapsed  耗时
 * @param stream   是否为流式调用
 */
public record CallRecord(Request request, Response response, Throwable error, Duration elapsed,
                         boolean stream) {

    /** 是否成功。 */
    public boolean succeeded() {
        return error == null;
    }
}
