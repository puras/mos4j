package net.hegq.mos4j.tool.subagent;

/**
 * 子代理装配 / 解析失败。
 *
 * <p><b>fail-closed</b>：子代理定义有问题就明确报错，不退化成「没子代理也能跑」
 * （那会让宿主以为子代理可用，实际是静默缺功能）。
 */
public class SubAgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 构造器。 */
    public SubAgentException(String message) {
        super(message);
    }

    /** 构造器（带原因）。 */
    public SubAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
