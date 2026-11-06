package net.hegq.mos4j.llm;

import net.hegq.mos4j.core.CancellationToken;

import java.util.Set;

/**
 * 统一的聊天模型抽象（协议无关）。
 *
 * <p>只包含对话能力（chat / chatStream）；其他能力（Embedding/Image 等）将来以并列接口扩展。</p>
 *
 * <p><b>取消语义</b>：取消是协作式的（见 {@link CancellationToken}）。
 * 实现必须在发送请求前、流式读取中、以及聚合完成后检查令牌。</p>
 */
public interface ChatModel {

    /**
     * 发起一次非流式对话。
     *
     * @throws StreamTruncatedException 流式响应被掐断（流式调用才可能）
     * @throws StatusError             服务端返回非 2xx（带状态码，供重试策略分类）
     */
    Response chat(Request request);

    /** 发起一次非流式对话（带取消）。 */
    Response chat(Request request, CancellationToken cancellation);

    /**
     * 发起流式对话，增量块通过 {@code emitter} 推送；emitter 抛异常即中断输出。
     *
     * @return 聚合后的完整响应
     * @throws StreamTruncatedException 流在收到结束信号前中断（机制 M5）
     */
    Response chatStream(Request request, StreamEmitter emitter);

    /** 发起流式对话（带取消）。 */
    Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation);

    /** 模型支持的能力位。 */
    Set<Capability> capabilities();

    /** 是否支持指定能力。 */
    default boolean supports(Capability capability) {
        return Capability.has(capabilities(), capability);
    }
}
