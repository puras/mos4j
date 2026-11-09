package net.hegq.mos4j.driver;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Tool;

import java.util.List;

/**
 * 决策器：循环中决定下一步动作。
 *
 * @param history 当前完整对话（含系统引导）
 * @param tools   可调用的工具定义（可为空）
 */
@FunctionalInterface
public interface Planner {

    /**
     * 决定下一步。
     *
     * @throws RuntimeException 决策失败（循环层以 turn/end{error} 收尾）
     */
    Action nextAction(List<Message> history, List<Tool> tools);

    /**
     * 决定下一步（带取消）。
     *
     * <p><b>为什么有这层缺省实现</b>：取消在 Java 里必须显式携带（见 {@link CancellationToken}），
     * 决策器是宿主可替换的扩展点，不能假定所有实现都改过一遍。缺省实现只在调用前检查一次，
     * <b>不响应中途取消</b>——想真正支持取消的决策器应覆写本方法并把令牌传到模型调用。
     *
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @throws java.util.concurrent.CancellationException 已取消
     */
    default Action nextAction(List<Message> history, List<Tool> tools, CancellationToken cancellation) {
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }
        return nextAction(history, tools);
    }
}
