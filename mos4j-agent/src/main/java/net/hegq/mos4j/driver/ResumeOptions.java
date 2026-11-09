package net.hegq.mos4j.driver;

import java.util.List;

/**
 * 续跑参数：把被中断的那一轮里的悬空调用接着走完。
 *
 * <p><b>前提：宿主显式指示</b>。框架不自动拾起挂起的轮次（fail-closed 取向）——
 * 没人指示就没人续；宿主先用 {@code session.pendingApprovals()} /
 * {@code session.hangingCalls()} 看清现场，再决定要不要续。
 *
 * <p><b>续跑语义</b>（对齐 Go 侧 {@code driver.ResumeOptions}）：
 * <ul>
 *   <li><b>沿用原轮次号</b>：审批决定的复用键是 {@code (turn, callId)}，轮次号一变就匹配不上；</li>
 *   <li><b>载荷原样重放</b>：调用 id / 工具名 / 参数都来自日志，不重写（载荷冻结契约）；</li>
 *   <li><b>走普通安全链</b>：守卫裁决 / 审批 / 调度语义与正常轮一致——续跑不是特权路径；</li>
 *   <li><b>回填真实结果</b>：写回真的 {@code tool/result}（不是投影里那种合成失败回执）。</li>
 * </ul>
 *
 * @param turn  要续的轮次号（0 = 自动选最后一个被打断的轮次）
 * @param calls 要续的调用 id（空 = 该轮全部悬空调用）
 */
public record ResumeOptions(int turn, List<String> calls) {

    public ResumeOptions {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** 自动选轮次 + 续该轮全部悬空调用。 */
    public static ResumeOptions latest() {
        return new ResumeOptions(0, List.of());
    }

    /**
     * 指定轮次 + 续该轮全部悬空调用。
     *
     * @param turn 轮次号
     */
    public static ResumeOptions ofTurn(int turn) {
        return new ResumeOptions(turn, List.of());
    }

    /**
     * 指定要续的调用 id。
     *
     * @param callIds 调用 id（可空 = 全部）
     * @return 新参数
     */
    public ResumeOptions withCalls(String... callIds) {
        if (callIds == null) {
            return new ResumeOptions(turn, List.of());
        }
        return new ResumeOptions(turn, List.of(callIds));
    }

    /** 是否指定了要续的调用 id（false = 该轮全部悬空调用）。 */
    public boolean hasCalls() {
        return !calls.isEmpty();
    }
}
