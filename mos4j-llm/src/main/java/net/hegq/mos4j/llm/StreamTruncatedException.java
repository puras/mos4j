package net.hegq.mos4j.llm;

/**
 * 流式响应在收到结束信号之前就中断了（连接被断开 / 服务端异常终止）。
 *
 * <p><b>为什么必须抛错而不是把半截聚合当正常响应返回</b>：半截消息 + 残缺工具调用
 * 会误导下游——2026-10 事故：空参数的 write 进了执行通道。抛错让驱动层
 * fail-closed 拒执行未完成的工具调用（见 {@code agent/driver/react}）。</p>
 *
 * <p>判定口径（机制 M5）：<b>结束标记或结束原因任一到即完整</b>；两者皆无才是截断。
 * 「结束原因为空」是合法的未知状态，<b>不等于</b>截断。</p>
 */
public class StreamTruncatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 与 Go 侧 {@code llm.ErrStreamTruncated} 文本一致（日志与测试口径一致）。 */
    public static final String MESSAGE = "流式响应意外终止（未收到结束信号）";

    public StreamTruncatedException() {
        super(MESSAGE);
    }

    public StreamTruncatedException(String message) {
        super(message);
    }

    public StreamTruncatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
