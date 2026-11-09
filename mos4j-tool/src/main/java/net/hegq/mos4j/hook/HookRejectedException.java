package net.hegq.mos4j.hook;

/**
 * 钩子抛出的「拒绝」：扩展点主动拒绝这次调用。
 *
 * <p>与「钩子自己崩了」区分开：前者是<b>有意</b>的拒绝（文案用
 * 「工具调用被扩展点拒绝」），后者是<b>意外</b>（文案用同一条，但原因里带异常信息）。
 */
public class HookRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason 拒绝理由（进文案）
     */
    public HookRejectedException(String reason) {
        super(reason == null ? "" : reason);
    }

    /**
     * @param cause  原因
     * @param reason 拒绝理由
     */
    public HookRejectedException(String reason, Throwable cause) {
        super(reason == null ? "" : reason, cause);
    }
}
