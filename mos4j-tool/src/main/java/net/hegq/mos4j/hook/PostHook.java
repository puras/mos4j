package net.hegq.mos4j.hook;

/**
 * 执行后钩子：只能把结果<b>加重</b>，不能减轻（机制 M3 / 不变式 I3）。
 *
 * <p><b>单向性是刻意的</b>：post 钩子可以把成功改失败（发现工具返回了不该返回的东西
 * ——数据被污染了，就得报出来），但不能把失败洗成成功
 * （「洗白」= 把工具的失败伪装成成功，模型于是以为事情办成了）。
 *
 * <p>所以接口<b>只给「加错误」的能力</b>：返回非空字符串 = 追加一条错误；
 * 没有「清除错误」的方法可调——不是靠约定，是接口里根本没有。
 */
@FunctionalInterface
public interface PostHook {

    /**
     * 工具执行完、结果已生成时的复核。
     *
     * @param event   这次调用的载荷（工具名 / 参数 / 调用 id）
     * @param content 工具返回的内容
     * @param isError 当前结果是否已是失败
     * @return 追加的错误说明（null / 空 = 不改变结果）；<b>已有错误时返回值会被忽略</b>
     */
    String afterToolResult(HookEvent event, String content, boolean isError);
}
