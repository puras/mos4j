package net.hegq.mos4j.model.protocol.anthropic.chat;

import net.hegq.mos4j.model.wire.StreamTermination;

/**
 * Anthropic 协议的流终止规则（机制 M5 的 Anthropic 侧）。
 *
 * <p><b>结束事件（事件层）</b>：{@code message_stop}——到即完整，之后不再读。</p>
 *
 * <p><b>结束原因（事件层）</b>：{@code message_delta.delta.stop_reason}。</p>
 *
 * <p><b>完整性</b>：两者<b>任一到即完整</b>；皆无 = 截断。只有 {@code message_stop}
 * 也完整、结束原因为空合法（A5 语料）。</p>
 *
 * <p><b>错误事件</b>：流中 {@code error} 事件是服务端失败（{@code StatusError}），
 * 不是截断——截断是「什么信号都没有」。</p>
 */
public final class AnthropicStreamRules {

    private AnthropicStreamRules() {
    }

    /** 事件层结束事件：{@code message_stop}（M5：到即完整）。 */
    public static boolean isFinalEvent(AnthropicStreamDtos.StreamEvent event) {
        return "message_stop".equals(event.type() == null ? "" : event.type());
    }

    /** 错误事件：{@code error}（流中错误 = 服务端失败，非截断）。 */
    public static boolean isErrorEvent(AnthropicStreamDtos.StreamEvent event) {
        return "error".equals(event.type() == null ? "" : event.type());
    }

    /** 错误事件的详情（取不到用缺省文案——错误信息必须可见，不能吞）。 */
    public static String errorMessage(AnthropicStreamDtos.StreamEvent event) {
        if (event.error() != null && event.error().error() != null
                && event.error().error().message() != null) {
            return event.error().error().message();
        }
        return "流式错误事件";
    }

    /** 终止规则（每条流一个实例）。 */
    public static StreamTermination<AnthropicStreamDtos.StreamEvent> termination() {
        return new StreamTermination<>() {
            private String stopReason;
            private boolean sawStop;

            @Override
            public boolean observeRaw(String data) {
                if (data != null && data.trim().isEmpty()) {
                    // 空 data 行 = 关流信号（与既有判定一致）
                    sawStop = true;
                    return true;
                }
                return false;
            }

            @Override
            public void observe(AnthropicStreamDtos.StreamEvent event) {
                String type = event.type() == null ? "" : event.type();
                if ("message_stop".equals(type)) {
                    sawStop = true;
                    return;
                }
                if ("message_delta".equals(type)) {
                    AnthropicStreamDtos.StreamEvent.DeltaBody delta = event.delta();
                    if (delta != null && delta.stopReason() != null && !delta.stopReason().isEmpty()) {
                        stopReason = delta.stopReason();
                    }
                }
            }

            @Override
            public boolean isFinal() {
                return sawStop;
            }

            @Override
            public boolean streamIncomplete() {
                boolean hasStopReason = stopReason != null && !stopReason.isEmpty();
                return !hasStopReason && !sawStop;
            }
        };
    }
}
