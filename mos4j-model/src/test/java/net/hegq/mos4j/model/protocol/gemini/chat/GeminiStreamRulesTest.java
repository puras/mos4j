package net.hegq.mos4j.model.protocol.gemini.chat;

import net.hegq.mos4j.model.wire.StreamTermination;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gemini 流终止规则（wire 层，机制 M5）的单测。
 *
 * <p><b>口径</b>：finishReason 即结束标记（比 OpenAI/Anthropic 严）；require_finish 逃生阀；
 * 无终止哨兵（读到 EOF，usageMetadata 在 finishReason 之后到达不丢）。DTO 是协议内私有，
 * 所以测试住在本包内。</p>
 */
class GeminiStreamRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void finishReason_isComplete() {
        StreamTermination<GeminiDtos.GenerateContentResponse> term = GeminiStreamRules.termination(true);
        assertFalse(term.observeRaw(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"半截\"}]}}]}"),
                "普通 data 不是终止信号");
        term.observe(MAPPER.readValue(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]}",
                GeminiDtos.GenerateContentResponse.class));
        assertFalse(term.isFinal(), "无哨兵：finishReason 后可能还有 usage chunk，读到 EOF");
        assertFalse(term.streamIncomplete());
    }

    @Test
    void noFinish_withRequireFinish_isIncomplete() {
        StreamTermination<GeminiDtos.GenerateContentResponse> term = GeminiStreamRules.termination(true);
        term.observe(MAPPER.readValue(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"半截回答\"}]}}]}",
                GeminiDtos.GenerateContentResponse.class));
        assertTrue(term.streamIncomplete(), "require_finish=true（缺省）：无 finishReason = 截断（G5a）");
    }

    @Test
    void noFinish_escapeValveAllows() {
        StreamTermination<GeminiDtos.GenerateContentResponse> term = GeminiStreamRules.termination(false);
        term.observe(MAPPER.readValue(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"回答\"}]}}]}",
                GeminiDtos.GenerateContentResponse.class));
        assertFalse(term.streamIncomplete(), "require_finish=false：流读完即完整（G5b）");
    }
}
