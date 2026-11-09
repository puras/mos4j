package net.hegq.mos4j.context;

/**
 * 压缩策略（什么时候压、压多少、留多少）。
 *
 * <p><b>为什么未配置 contextWindow 就永不触发</b>：窗口大小只有服务商知道。
 * 拿一个猜的窗口去压，等于用错误的事实做决定——轻则白压（把还能放下的历史压掉，
 * 模型丢信息），重则压晚了（请求直接被服务商拒绝）。
 * 没配置 = 不压，把决定权留给宿主；这是 fail-closed：**能力位的缺省值是「关」**。
 *
 * @param contextWindow    模型上下文窗口（可空 = 未配置 = 永不触发）
 * @param reserveTokens    预留给本轮输出与系统提示的 token（窗口 - 预留 = 可用预算）
 * @param keepRecentTokens 压缩时保留的最近历史 token 数（切割点从尾部往回数到这里）
 */
public record CompactionPolicy(Long contextWindow, int reserveTokens, int keepRecentTokens) {

    /**
     * 构造策略。
     *
     * @param contextWindow    上下文窗口（必须为正）
     * @param reserveTokens    预留 token（不能为负）
     * @param keepRecentTokens 保留的最近历史 token（不能为负）
     * @return 策略
     */
    public static CompactionPolicy of(long contextWindow, int reserveTokens, int keepRecentTokens) {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("context: contextWindow 必须为正");
        }
        if (reserveTokens < 0) {
            throw new IllegalArgumentException("context: reserveTokens 不能为负");
        }
        if (keepRecentTokens < 0) {
            throw new IllegalArgumentException("context: keepRecentTokens 不能为负");
        }
        return new CompactionPolicy(contextWindow, reserveTokens, keepRecentTokens);
    }

    /** 是否启用（配置了窗口才启用）。 */
    public boolean enabled() {
        return contextWindow != null;
    }

    /**
     * 可用预算 = 窗口 - 预留。
     *
     * @return 预算 token 数（未配置窗口时为 0）
     */
    public int budget() {
        if (contextWindow == null) {
            return 0;
        }
        return (int) Math.max(0, contextWindow - reserveTokens);
    }

    /**
     * 是否该压缩。
     *
     * @param contextTokens 当前上下文的 token 估算
     * @return true 表示超预算，应当压缩
     */
    public boolean shouldCompact(int contextTokens) {
        if (!enabled()) {
            return false;
        }
        return contextTokens > budget();
    }
}
