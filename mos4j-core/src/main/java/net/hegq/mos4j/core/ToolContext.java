package net.hegq.mos4j.core;

/**
 * 工具执行上下文（轮次 / 会话 id / 取消令牌）。
 *
 * <p><b>为什么需要</b>：有些工具需要知道「自己是在哪一轮、哪个会话里被调用的」——
 * 子代理要派生子会话 id、要继承父轮次的取消；审批审计要知道当前轮次。
 * 工具接口原本只有 {@code execute(argsJson)}，这些信息拿不到。
 *
 * <p><b>为什么放在 core</b>：core 是共享词汇表层，工具层（{@code mos4j-tool}）
 * 与执行通道（{@code net.hegq.mos4j.execpath} 包）都能看见它，方向不反过来。
 *
 * <p><b>为什么只放会话 id 而不是会话对象</b>：会话类型属于日志层
 * （{@code mos4j-session}），而工具层在会话层之下——工具层一旦引用会话类型，
 * 依赖方向就反了（能力层依赖日志层）。会话 id 是纯字符串，够派生子会话 id 用；
 * 真正要落盘的子会话由宿主提供工厂创建。
 *
 * @param turn        本次执行所属轮次（0 = 不知道）
 * @param sessionId   本次执行所属会话 id（空 = 不知道）
 * @param cancellation 取消令牌（可空 = 不可取消）
 */
public record ToolContext(int turn, String sessionId, CancellationToken cancellation) {

    /** 空上下文（不知道轮次、不知道会话、不可取消）。 */
    public static ToolContext none() {
        return new ToolContext(0, "", null);
    }

    /**
     * 带上轮次与会话 id。
     *
     * @param turn      轮次
     * @param sessionId 会话 id
     * @return 新上下文
     */
    public static ToolContext of(int turn, String sessionId) {
        return new ToolContext(turn, sessionId == null ? "" : sessionId, null);
    }

    /**
     * 带上取消令牌（返回新对象）。
     *
     * @param token 取消令牌（可空 = 不可取消）
     * @return 新上下文
     */
    public ToolContext withCancellation(CancellationToken token) {
        return new ToolContext(turn, sessionId, token);
    }

    /** 是否已取消（没有令牌 = 没取消）。 */
    public boolean isCancelled() {
        return cancellation != null && cancellation.isCancelled();
    }
}
