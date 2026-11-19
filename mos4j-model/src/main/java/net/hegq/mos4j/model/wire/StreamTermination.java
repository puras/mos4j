package net.hegq.mos4j.model.wire;

/**
 * 流终止规则（wire 层）：一条流「什么时候算完、断了算谁的」的显式判据（机制 M5）。
 *
 * <p><b>为什么独立成层</b>：三个协议适配器里的终止判定原来散在各自的私有循环里
 * （OpenAI 的 {@code [DONE]}、Anthropic 的 {@code message_stop}、Gemini 的
 * {@code finishReason} + {@code requireFinish} 逃生阀）。网关/透传场景必须显式拿到
 * <b>同一份</b>判据——上游流何时终结、断流算截断还是正常，不能每个消费方各抄一份
 * （抄错一份 = 对截断的判定分家，半截响应冒充正常响应就是事故）。</p>
 *
 * <p><b>判定口径（机制 M5）</b>：结束标记或结束原因<b>任一到即完整</b>；两者皆无才是截断。
 * 「结束原因为空」是合法的未知状态，不等于截断。</p>
 *
 * <p><b>用法</b>（每条流一个实例，非线程安全）：
 * <pre>{@code
 * var term = XxxStreamRules.termination();
 * SseEventReader.read(in, event -> {
 *     if (term.observeRaw(event.data())) return false;   // 原始层哨兵（如 [DONE]）→ 停止读取
 *     var dto = mapper.readValue(event.data(), XxxEvent.class);
 *     term.observe(dto);
 *     // ... 业务处理 ...
 *     return !term.isFinal();                            // 事件层哨兵（如 message_stop）→ 停止读取
 * });
 * if (term.streamIncomplete()) throw new StreamTruncatedException();
 * }</pre></p>
 *
 * @param <T> 已解析的事件类型（协议 DTO）
 */
public interface StreamTermination<T> {

    /**
     * 喂入一条原始 data（未解析的文本）。
     *
     * <p>哨兵可能不是合法 JSON（如 OpenAI 的 {@code [DONE]}），这类信号只能在
     * 解析之前识别。默认实现：无原始层哨兵（返回 {@code false}）。</p>
     *
     * @param data 原始 data 文本（可空）
     * @return {@code true} = 原始层终止信号，调用方应停止读取
     */
    default boolean observeRaw(String data) {
        return false;
    }

    /** 喂入一条已解析事件（终止判定从事件字段里取证据）。 */
    void observe(T event);

    /** 事件层终止事件已到（应停止读取）。 */
    boolean isFinal();

    /** 流读完后判定：{@code true} = 流被截断（未收到任何结束信号，机制 M5）。 */
    boolean streamIncomplete();
}
