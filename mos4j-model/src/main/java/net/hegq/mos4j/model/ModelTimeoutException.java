package net.hegq.mos4j.model;

/**
 * 模型调用超时（对齐 Go 参考实现 {@code llm.WithTimeout}）。
 *
 * <p>超时是**协作式**的：装饰器给模型一个带超时的取消令牌，模型在阻塞点检查它。
 * 模型不检查令牌就不会真的断（这是协作式取消的固有代价，不隐藏）。
 */
public class ModelTimeoutException extends RuntimeException {

    /** 超时时长（毫秒）。 */
    private final long timeoutMillis;

    /**
     * @param timeoutMillis 超时时长（毫秒）
     */
    public ModelTimeoutException(long timeoutMillis) {
        super("模型调用超时（" + timeoutMillis + " ms）");
        this.timeoutMillis = timeoutMillis;
    }

    /** 超时时长（毫秒）。 */
    public long timeoutMillis() {
        return timeoutMillis;
    }
}
