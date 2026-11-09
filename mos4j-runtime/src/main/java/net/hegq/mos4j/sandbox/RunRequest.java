package net.hegq.mos4j.sandbox;

import java.time.Duration;

/**
 * 一次运行请求。
 *
 * <p><b>命令由宿主发起</b>：模型只能提出请求，宿主决定工作目录、环境与上限。
 * 请求里能覆盖的只有「超时」和「输出上限」，而且都会被宿主的<b>上界</b>压住
 * （{@code min(请求值 ?? 缺省值, 上界)}）——模型不能靠要一个超大超时把沙箱的约束架空。
 *
 * @param command        命令行（由宿主选定的 shell 执行）
 * @param timeout        期望超时（null 表示用缺省值）
 * @param maxOutputBytes 期望的输出上限字节数（null 表示用缺省值）
 */
public record RunRequest(String command, Duration timeout, Integer maxOutputBytes) {

    /**
     * @param command 命令行
     */
    public RunRequest {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("sandbox: 命令不能为空");
        }
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("sandbox: 超时必须为正: " + timeout);
        }
        if (maxOutputBytes != null && maxOutputBytes <= 0) {
            throw new IllegalArgumentException("sandbox: 输出上限必须为正: " + maxOutputBytes);
        }
    }

    /**
     * 不带覆盖值的请求。
     *
     * @param command 命令行
     * @return 请求
     */
    public static RunRequest of(String command) {
        return new RunRequest(command, null, null);
    }

    /**
     * 带超时的请求。
     *
     * @param command 命令行
     * @param timeout 超时
     * @return 请求
     */
    public static RunRequest of(String command, Duration timeout) {
        return new RunRequest(command, timeout, null);
    }
}
