package net.hegq.mos4j.driver;

/**
 * 没有可续的东西：没有被打断的轮次，或那一轮没有悬空调用（都已回填）。
 *
 * <p><b>不是故障</b>：没人续是正常状态（框架不自动拾起挂起的轮次）。宿主可以先查
 * （{@code session.pendingApprovals()} / {@code session.hangingCalls()}）再决定。
 *
 * <p><b>那一轮保持挂起</b>：抛这个异常时**不收尾**那一轮——收尾等于把
 * 「还欠一次调用」的事实抹掉，宿主稍后续就找不到现场了。
 */
public final class NothingToResumeException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 说明（宿主看得到）
     */
    public NothingToResumeException(String message) {
        super(message);
    }
}
