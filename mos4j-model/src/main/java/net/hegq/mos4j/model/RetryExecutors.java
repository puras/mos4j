package net.hegq.mos4j.model;

import java.time.Duration;

/**
 * 缺省睡眠与工具（包内实现细节）。
 */
final class RetryExecutors {

    private RetryExecutors() {
    }

    /** 缺省睡眠：Thread.sleep。 */
    static void defaultSleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
