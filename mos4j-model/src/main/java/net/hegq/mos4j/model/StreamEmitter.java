package net.hegq.mos4j.model;

/**
 * 流式增量回调。
 *
 * <p>返回异常即中断输出（异常原样抛给调用方）。</p>
 */
@FunctionalInterface
public interface StreamEmitter {

    /**
     * 接收一个增量块。
     *
     * @return 抛异常以中断输出
     */
    void emit(StreamChunk chunk) throws Exception;
}
