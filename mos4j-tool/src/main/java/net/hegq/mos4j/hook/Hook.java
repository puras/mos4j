package net.hegq.mos4j.hook;

/**
 * 扩展点：可链式、可观察的加工者。
 *
 * <p><b>三条语义</b>：
 * <ul>
 *   <li><b>waterfall</b>：{@code next.next(event)} 交给下一个钩子；</li>
 *   <li><b>短路</b>：不调用 {@code next} 就返回 = 后面的钩子不再执行；</li>
 *   <li><b>替换</b>：返回的事件取代输入事件（<b>但是</b>：execpath 会做载荷冻结检查，
 *       改写 name/args/id 一律拒绝——钩子能改的是「非冻结部分」，
 *       目前载荷里没有非冻结部分，所以实务上钩子只应观察与拒绝）。</li>
 * </ul>
 *
 * <p><b>不许返回 null</b>：null 会让链崩在下游，报错点离原因很远。
 * 返回 null 视为契约破坏，{@link HookBus} 显式报错并拒绝这次调用。
 */
@FunctionalInterface
public interface Hook {

    /**
     * 处理一次工具调用。
     *
     * @param event 当前事件
     * @param next  交给下一个钩子
     * @return 处理后的事件（不返回 null）
     * @throws Exception 出错 = 拒绝这次调用（fail-closed）
     */
    HookEvent onToolCall(HookEvent event, Next next) throws Exception;

    /** 链的下一环。 */
    @FunctionalInterface
    interface Next {

        /**
         * 交给下一个钩子。
         *
         * @param event 当前事件
         * @return 下一个钩子返回的事件（链尾 = 原事件）
         * @throws Exception 出错 = 拒绝这次调用
         */
        HookEvent next(HookEvent event) throws Exception;
    }
}
