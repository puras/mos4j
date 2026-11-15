package net.hegq.mos4j.driver;

/**
 * 模型这一轮**没有给出可交付的答复**：既无正文，也无工具调用。
 *
 * <p><b>为什么不是「完成」</b>：推理模型（DeepSeek-R1 一类）常把「下一步要做什么」
 * 写进思维链就收手——正文空、工具调用也空。若照旧记 {@code completed}，
 * 宿主与用户都以为这一轮正常结束，日志里却查不到任何错误：这种失败没有错误、
 * 界面上一切正常，是最难排查的一种（Go 侧实测 83 个会话里 12 轮如此）。
 * 输出被截断（{@code finish_reason=length}）把正文切光时同样落在这里，一并拦下。
 *
 * <p><b>事实照旧留痕</b>：那一轮的 assistant 事件（思维链 + 用量 + 停止原因）已经落库，
 * 宿主能看到模型确实说过什么；只是这一轮以 {@code turn/end{reason:"error"}} 收尾，
 * 不冒充完成。空回复也不进模型历史（部分厂商拒收「既无 content 也无 tool_calls」
 * 的 assistant 消息）——日志是事实，投影是派生。
 *
 * <p><b>宿主怎么处理</b>：这是 {@link RuntimeException}（驱动接口只抛非受检异常），
 * 用 {@code catch (NoAnswerException e)} 精确捕获；{@link #getMessage()} 里带
 * 「有没有思维链 / finish_reason / 有没有工具调用」三样线索，够判断该提示用户
 * 「发继续」还是「换模型」。
 */
public final class NoAnswerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 分类用的固定前缀（与 Go 的 {@code ErrNoAnswer} 文案同义）。 */
    public static final String MESSAGE = "react: 模型没有给出最终答复";

    /**
     * @param detail 可读详情（是否只有思维链、停止原因等）
     */
    public NoAnswerException(String detail) {
        super(MESSAGE + ": " + detail);
    }
}