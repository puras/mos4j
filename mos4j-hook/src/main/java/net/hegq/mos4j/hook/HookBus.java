package net.hegq.mos4j.hook;

import java.util.ArrayList;
import java.util.List;

/**
 * 钩子总线：waterfall（注册顺序逐层委托 / 短路 / 替换）。
 *
 * <p><b>四条语义</b>：
 * <ul>
 *   <li><b>waterfall</b>：钩子按注册顺序依次执行，每个都能拿到下游处理后的结果；</li>
 *   <li><b>短路</b>：钩子不调用 {@code next} 就返回 → 后面的钩子不执行；</li>
 *   <li><b>替换</b>：钩子返回的事件成为最终载荷（execpath 的载荷冻结会检查它）；</li>
 *   <li><b>不许 null</b>：钩子返回 {@code null} → 显式报错（{@link NilHookEventException}），
 *       链不崩、调用被拒绝。返回 null 的钩子是最常见的插件 bug，
 *       崩在下游 NPE 会让排查从「哪个插件」变成「栈里哪一行」。</li>
 * </ul>
 *
 * <p><b>未表态 = 放行</b>：没有钩子（或钩子都只是观察）→ 事件原样返回。
 *
 * <p><b>零开销降级</b>：没有注册钩子时 {@link #run} 直接返回入参事件，不分配对象
 * （不变式 I6）。
 */
public final class HookBus {

    private final List<Hook> hooks = new ArrayList<>();

    /**
     * 注册一个钩子（按注册顺序进入链）。
     *
     * @param hook 钩子（不返回 null）
     * @return this（链式）
     */
    public HookBus register(Hook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("hook: 钩子不能为 null");
        }
        hooks.add(hook);
        return this;
    }

    /** 已注册的钩子数量。 */
    public int size() {
        return hooks.size();
    }

    /**
     * 执行前的载荷快照（载荷冻结的比对基准）。
     *
     * @param event 原始事件
     * @return 快照（不可变值对象）
     */
    public HookEvent snapshot(HookEvent event) {
        return event == null ? null : new HookEvent(event.toolName(), event.args(), event.callId());
    }

    /**
     * 跑整条链，返回最终载荷。
     *
     * @param event 原始事件
     * @return 最终载荷（未被改动时是原事件）
     * @throws HookRejectedException 钩子拒绝这次调用
     * @throws NilHookEventException  钩子返回了 null（契约破坏）
     */
    public HookEvent run(HookEvent event) {
        if (hooks.isEmpty() || event == null) {
            return event;
        }
        try {
            return invoke(0, event);
        } catch (NilHookEventException e) {
            throw e;
        } catch (HookRejectedException e) {
            throw e;
        } catch (Exception e) {
            // 钩子崩了 = 拒绝（fail-closed）
            throw new HookRejectedException(message(e), e);
        }
    }

    private HookEvent invoke(int index, HookEvent event) throws Exception {
        if (index >= hooks.size()) {
            return event;
        }
        Hook hook = hooks.get(index);
        HookEvent result = hook.onToolCall(event, next -> invoke(index + 1, next));
        if (result == null) {
            throw new NilHookEventException("钩子返回了 null 事件（钩子契约被破坏，调用已拒绝）");
        }
        return result;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** 钩子返回 null 时抛出（Go 侧 {@code ErrNilHookEvent} 的对位）。 */
    public static class NilHookEventException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message 错误说明
         */
        public NilHookEventException(String message) {
            super(message);
        }
    }
}
