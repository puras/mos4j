package net.hegq.mos4j.model.protocol.anthropic.chat;

import net.hegq.mos4j.model.wire.StreamTermination;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic 流终止规则（wire 层，机制 M5）的单测。
 *
 * <p><b>口径</b>：message_stop（事件层）或 stop_reason 任一到即完整；两者皆无 = 截断；
 * 空 data 行 = 关流信号；流中 error 事件 = 服务端失败（非截断）。DTO 是协议内私有，
 * 所以测试住在本包内。</p>
 */
class AnthropicStreamRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messageStopOnly_isComplete() {
        StreamTermination<AnthropicStreamDtos.StreamEvent> term = AnthropicStreamRules.termination();
        term.observe(MAPPER.readValue(
                "{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\",\"usage\":{\"input_tokens\":10}}}",
                AnthropicStreamDtos.StreamEvent.class));
        assertTrue(term.observeRaw("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}") == false,
                "普通 data 不是原始层终止信号");
        term.observe(MAPPER.readValue("{\"type\":\"message_stop\"}",
                AnthropicStreamDtos.StreamEvent.class));
        assertTrue(term.isFinal(), "message_stop 到即停止读取（M5）");
        assertFalse(term.streamIncomplete(), "只有 message_stop 也完整；结束原因为空合法（A5）");
    }

    @Test
    void stopReasonOnly_isComplete() {
        StreamTermination<AnthropicStreamDtos.StreamEvent> term = AnthropicStreamRules.termination();
        term.observe(MAPPER.readValue(
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                AnthropicStreamDtos.StreamEvent.class));
        assertFalse(term.isFinal(), "stop_reason 不是哨兵，继续读到 message_stop/EOF");
        assertFalse(term.streamIncomplete(), "结束原因即完整（网关形态）");
    }

    @Test
    void emptyData_isTerminal() {
        StreamTermination<AnthropicStreamDtos.StreamEvent> term = AnthropicStreamRules.termination();
        assertTrue(term.observeRaw("   "), "空 data 行 = 关流信号（与既有判定一致）");
        assertFalse(term.streamIncomplete());
    }

    @Test
    void neitherSignal_isIncomplete() {
        StreamTermination<AnthropicStreamDtos.StreamEvent> term = AnthropicStreamRules.termination();
        term.observe(MAPPER.readValue(
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"半截\"}}",
                AnthropicStreamDtos.StreamEvent.class));
        assertTrue(term.streamIncomplete(), "无 message_stop、无 stop_reason = 截断（A4/A6）");
    }

    @Test
    void errorEventHelpers() {
        AnthropicStreamDtos.StreamEvent event = MAPPER.readValue(
                "{\"type\":\"error\",\"error\":{\"error\":{\"message\":\"overloaded\"}}}",
                AnthropicStreamDtos.StreamEvent.class);
        assertTrue(AnthropicStreamRules.isErrorEvent(event));
        assertFalse(AnthropicStreamRules.isFinalEvent(event));
        assertEquals("overloaded", AnthropicStreamRules.errorMessage(event));
        // 缺详情时的缺省文案（错误信息必须可见，不能吞）
        AnthropicStreamDtos.StreamEvent bare = MAPPER.readValue(
                "{\"type\":\"error\"}", AnthropicStreamDtos.StreamEvent.class);
        assertEquals("流式错误事件", AnthropicStreamRules.errorMessage(bare));
    }
}
