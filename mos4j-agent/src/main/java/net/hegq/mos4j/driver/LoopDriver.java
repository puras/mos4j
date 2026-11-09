package net.hegq.mos4j.driver;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.session.Session;

import java.io.IOException;

/**
 * 循环驱动接口：一种循环类型 = 一个实现。
 *
 * <p>驱动负责「一轮怎么转」：写会话事件、调决策器、执行工具、回填结果、判定终止。</p>
 */
@FunctionalInterface
public interface LoopDriver {

    /**
     * 在给定会话上执行一次任务。
     *
     * @param rt     运行上下文
     * @param session 会话（由调用方持有，可挂持久化后端）
     * @param input  用户输入
     * @return 最终回复文本
     * @throws IOException 落盘失败（事件日志写不进去 = 本轮不可信）
     */
    String run(RunContext rt, Session session, String input) throws IOException;

    /**
     * 在给定会话上执行一次任务（带取消）。
     *
     * <p><b>为什么有这层缺省实现</b>：Java 的取消必须显式携带（见 {@link CancellationToken}），
     * 而本接口是宿主的扩展点——第三方驱动不可能都改一遍。所以给一个「只在开始前检查一次」
     * 的缺省实现，<b>并明说它不响应中途取消</b>：静默忽略取消比不提供取消更危险
     * （宿主以为停了，其实还在烧 token）。
     *
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @throws java.util.concurrent.CancellationException 已取消
     */
    default String run(RunContext rt, Session session, String input, CancellationToken cancellation)
            throws IOException {
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }
        return run(rt, session, input);
    }

    /**
     * 续跑：把被中断的那一轮里的悬空调用接着走完。
     *
     * <p><b>默认实现直接报错</b>：不支持续跑的驱动必须**明确说不**，不能默默开一轮新的
     * ——宿主以为在续跑，其实在另起一轮（审批决定的复用键对不上、旧调用永远悬着）。
     *
     * <p>续跑的语义：沿用原轮次号、载荷原样重放、走普通安全链（守卫 / 审批 / 调度）、
     * 回填真实 {@code tool/result}，然后继续决策并正常收尾那一轮。
     *
     * @param rt          运行上下文
     * @param session     会话（由调用方持有）
     * @param options     续跑参数（轮次 / 要续的调用 id）
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @return 本轮最终回复文本
     * @throws NothingToResumeException 没有可续的悬空调用（那一轮保持挂起）
     * @throws IOException 落盘失败
     */
    default String resume(RunContext rt, Session session, ResumeOptions options,
                          CancellationToken cancellation) throws IOException, NothingToResumeException {
        throw new UnsupportedOperationException(
                "driver: 该驱动不支持续跑（LoopDriver.resume 未实现）");
    }
}
