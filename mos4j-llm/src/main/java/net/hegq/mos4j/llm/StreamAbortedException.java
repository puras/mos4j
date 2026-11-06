package net.hegq.mos4j.llm;

/**
 * 流式输出被下游中断（emitter 抛异常）。
 *
 * <p><b>为什么要包一层</b>：emitter 的回调可能抛任意受检异常，适配器不能让它
 * 直接冒泡成「协议层异常」——否则上层分不清「模型出问题了」和「消费方主动放弃了」。
 * 包一层后，上层看到的是明确的「输出被中断」，原始异常作为 cause 保留。</p>
 */
public class StreamAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StreamAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
