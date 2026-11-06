package net.hegq.mos4j.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 语料完整性门（J0）：所有 .sse 资源必须可解析（data 行 JSON 合法）。
 * 这是语料自身的守护——报文文件坏了，J1 的场景测试还没跑就该红。
 *
 * <p>四元组断言（content / toolCalls / finishReason / usage）依赖适配器实现，
 * 在 J1 点亮（见 {@link L1AnthropicScenariosTest} 等骨架）。</p>
 */
class L1CorpusIntegrityTest {

    private static final List<String> CORPUS = List.of(
            // Anthropic（10 条，出处见 docs/contract/L1-SCENARIOS.md）
            "/llm/anthropic/a1-text-stream.sse",
            "/llm/anthropic/a2-tool-use-stream.sse",
            "/llm/anthropic/a3-thinking-stream.sse",
            "/llm/anthropic/a4-truncated.sse",
            "/llm/anthropic/a5-message-stop-only-complete.sse",
            "/llm/anthropic/a6-cut-without-end-signal.sse",
            "/llm/anthropic/a7-usage-from-message-start.sse",
            "/llm/anthropic/a8-usage-merges-both.sse",
            "/llm/anthropic/a9-usage-parts-sum.sse",
            "/llm/anthropic/a10-usage-delta-wins.sse",
            // OpenAI（5 份）
            "/llm/openai/o1-text-stream.sse",
            "/llm/openai/o2-tool-call-aggregation.sse",
            "/llm/openai/o4-usage-reasoning.sse",
            "/llm/openai/o5a-truncated.sse",
            "/llm/openai/o5b-done-only-complete.sse",
            // Gemini（4 份）
            "/llm/gemini/g1-text-stream.sse",
            "/llm/gemini/g2-function-call.sse",
            "/llm/gemini/g5a-truncated.sse",
            "/llm/gemini/g5b-require-finish-false.sse");

    @Test
    void everyCorpusFileParses() throws IOException {
        assertFalse(CORPUS.isEmpty());
        for (String path : CORPUS) {
            try (InputStream in = getClass().getResourceAsStream(path)) {
                assertNotNull(in, "语料缺失: " + path);
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(content.contains("# ") && content.contains("Go "),
                        path + " 头部必须注明 Go 出处（照抄口径的可追溯性）");
                int dataLines = 0;
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (!line.startsWith("data:") || line.equals("data: [DONE]")) {
                        continue;
                    }
                    dataLines++;
                    String payload = line.substring(5).trim();
                    assertDoesNotThrow(() ->
                            new tools.jackson.databind.ObjectMapper().readTree(payload),
                            path + " 含非法 JSON data 行");
                }
                assertTrue(dataLines > 0, path + " 没有 data 行");
            }
        }
    }

    @Test
    void corpusCoversEndMarkingMatrix() {
        // M5 判定矩阵：三协议各有「完整（结束标记）」与「截断（无任何信号）」两侧用例
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("a5-message-stop-only-complete")));
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("a6-cut-without-end-signal")));
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("o5b-done-only-complete")));
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("o5a-truncated")));
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("g5a-truncated")));
        assertTrue(CORPUS.stream().anyMatch(p -> p.contains("g5b-require-finish-false")));
    }
}
