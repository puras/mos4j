package net.hegq.mos4j.llm;

/**
 * 模型返回了空响应（没有可用内容）。
 *
 * <p><b>为什么单列一个类型</b>：空响应通常是<b>瞬时</b>的（网关抖动、上游回了空体），
 * 与「参数写错了」不同——重试有意义。所以重试策略要能单独认出它。</p>
 */
public class EmptyResponseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 与 Go 侧 {@code llm.ErrEmptyResponse} 文本一致。 */
    public static final String MESSAGE = "模型返回空响应";

    public EmptyResponseException() {
        super(MESSAGE);
    }

    public EmptyResponseException(String message) {
        super(message);
    }
}
