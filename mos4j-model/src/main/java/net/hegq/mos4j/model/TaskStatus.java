package net.hegq.mos4j.model;

/**
 * 异步任务的状态（图像 / 视频生成、文档解析这类「提交 → 轮询 → 取产物」的服务）。
 *
 * <p><b>为什么要有 UNKNOWN</b>：厂商的状态取值各不一样（{@code PENDING}/{@code RUNNING}/
 * {@code SUCCEEDED}/{@code TaskSucceeded}/…）。映射不认识的取值时<b>不猜</b>——猜成「成功」会
 * 让宿主拿一个空产物当结果，猜成「失败」会把仍在跑的任务误杀。所以落到 {@link #UNKNOWN}：
 * 不算终态，宿主可以继续轮询或按超时处理。
 */
public enum TaskStatus {

    /** 已提交，尚未开始。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 成功完成（产物在 {@link TaskHandle#artifacts()}）。 */
    SUCCEEDED,
    /** 失败（原因在 {@link TaskHandle#errorMessage()}）。 */
    FAILED,
    /** 被取消。 */
    CANCELED,
    /** 状态取值不认识（不是终态——不猜）。 */
    UNKNOWN;

    /** 是否终态（不会再变）。 */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    /** 是否成功。 */
    public boolean successful() {
        return this == SUCCEEDED;
    }
}
