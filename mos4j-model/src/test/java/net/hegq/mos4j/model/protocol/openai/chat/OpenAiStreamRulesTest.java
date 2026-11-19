package net.hegq.mos4j.model.protocol.openai.chat;

import net.hegq.mos4j.model.wire.StreamTermination;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 流终止规则（wire 层，机制 M5）的单测。
 *
 * <p><b>口径</b>：[DONE]/空 data 行（原始层）或 finish_reason（事件层）任一到即完整；
 * 两者皆无 = 截断。DTO 是协议内私有，所以测试住在本包内。</p>
 */
class OpenAiStreamRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void doneOnly_isComplete() {
        StreamTermination<OpenAiStreamDtos.ChatCompletionChunk> term = OpenAiStreamRules.termination();
        term.observe(MAPPER.readValue(
                "{\"choices\":[{\"delta\":{\"content\":\"回答\"}}]}",
                OpenAiStreamDtos.ChatCompletionChunk.class));
        assertTrue(term.observeRaw("[DONE]"), "[DONE] 是原始层终止信号");
        assertFalse(term.streamIncomplete(), "只有 [DONE] 也完整（O5b）");
    }

    @Test
    void emptyData_isTerminal() {
        StreamTermination<OpenAiStreamDtos.ChatCompletionChunk> term = OpenAiStreamRules.termination();
        assertTrue(term.observeRaw("   "), "空 data 行 = 关流信号");
        assertFalse(term.streamIncomplete());
    }

    @Test
    void finishReasonOnly_isComplete() {
        StreamTermination<OpenAiStreamDtos.ChatCompletionChunk> term = OpenAiStreamRules.termination();
        assertFalse(term.observeRaw("{\"choices\":[{\"delta\":{}}]}"),
                "普通 data 不是终止信号");
        term.observe(MAPPER.readValue(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                OpenAiStreamDtos.ChatCompletionChunk.class));
        assertFalse(term.isFinal(), "OpenAI 无事件层哨兵（停止发生在原始层）");
        assertFalse(term.streamIncomplete(), "finish_reason 到即完整");
    }

    @Test
    void neitherSignal_isIncomplete() {
        StreamTermination<OpenAiStreamDtos.ChatCompletionChunk> term = OpenAiStreamRules.termination();
        term.observe(MAPPER.readValue(
                "{\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}",
                OpenAiStreamDtos.ChatCompletionChunk.class));
        assertTrue(term.streamIncomplete(), "无 finish_reason、无 [DONE] = 截断（O5a）");
    }

    @Test
    void terminalDataRecognizer() {
        assertTrue(OpenAiStreamRules.isTerminalData("[DONE]"));
        assertTrue(OpenAiStreamRules.isTerminalData("  "));
        assertTrue(OpenAiStreamRules.isTerminalData(null));
        assertFalse(OpenAiStreamRules.isTerminalData("{\"choices\":[]}"));
    }
}
