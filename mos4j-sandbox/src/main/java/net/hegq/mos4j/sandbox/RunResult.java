package net.hegq.mos4j.sandbox;

import java.time.Duration;

/**
 * 一次运行的结果。
 *
 * <p><b>非零退出码不是错误</b>（机制 M9）：{@code exitCode} 只是事实，
 * 「这次运行算不算失败」由调用方按语义判断。把 exit 1 当成工具失败，
 * 模型就会开始写 {@code ; echo "exit=$?"} 这种 hack 把失败藏进输出——
 * 失败一旦被掩进输出，事实就被污染了。
 *
 * <p>真正算错误的是<b>命令根本没起来</b>（{@code startFailure != null}）或者<b>超时</b>。
 *
 * @param exitCode     退出码（非零也可能是正常结果）
 * @param stdout       标准输出（可能被截断）
 * @param stderr       标准错误（可能被截断）
 * @param timedOut     是否超时
 * @param truncated    输出是否被截断
 * @param duration     耗时
 * @param startFailure 启动失败原因（null 表示成功启动）
 */
public record RunResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated,
                        Duration duration, String startFailure) {

    /**
     * 命令根本没起来。
     *
     * @param failure  原因
     * @param duration 耗时
     * @return 结果
     */
    public static RunResult notStarted(String failure, Duration duration) {
        return new RunResult(-1, "", "", false, false, duration, failure);
    }

    /**
     * 是否成功启动。
     *
     * @return true 表示命令跑起来了
     */
    public boolean started() {
        return startFailure == null;
    }

    /**
     * 是否超时。
     *
     * @return true 表示超时
     */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * 输出是否被截断。
     *
     * @return true 表示有输出被丢弃
     */
    public boolean truncated() {
        return truncated;
    }
}
