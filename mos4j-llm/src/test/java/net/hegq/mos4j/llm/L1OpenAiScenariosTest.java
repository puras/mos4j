package net.hegq.mos4j.llm;

import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.llm.protocol.openai.OpenAiChatModel;
import net.hegq.mos4j.llm.protocol.openai.OpenAiConfig;
import net.hegq.mos4j.llm.support.FakeLlmServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI L1 场景（协议报文照抄 Go 测试字面量，期望值由 Java 侧计算）。
 *
 * <p><b>关键守则</b>：O2 的 {@code tool_calls[i].function.arguments} 按 index 分片累加，
 * 乱序到达也按 index 归位；O5b 证明「只有 [DONE] 也完整」（finish_reason 未知≠截断，M5 对称侧）。</p>
 */
class L1OpenAiScenariosTest {

    private FakeLlmServer server;
    private OpenAiChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLlmServer();
        model = new OpenAiChatModel(OpenAiConfig.of("test-key", server.baseUrl(), "gpt-test"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static String corpus(String path) throws IOException {
        try (InputStream in = L1OpenAiScenariosTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "语料缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void o1_textStream_accumulatesDeltas() throws IOException {
        server.respondSse(corpus("/llm/openai/o1-text-stream.sse"));

        List<String> deltas = new ArrayList<>();
        Response response = model.chatStream(Request.ofUser("hi"),
                chunk -> deltas.add(chunk.delta() == null ? "" : chunk.delta()));

        assertEquals("Hello world", response.message().content());
        assertEquals("Hello world", String.join("", deltas));
        assertEquals("stop", response.finishReason());
    }

    @Test
    void o2_toolCallAggregation_byIndex() throws IOException {
        server.respondSse(corpus("/llm/openai/o2-tool-call-aggregation.sse"));

        Response response = model.chatStream(Request.ofUser("calc"), chunk -> {
        });

        List<ToolCall> calls = response.message().toolCalls();
        assertNotNull(calls, "O2 必须聚合出工具调用");
        assertEquals(1, calls.size());
        ToolCall call = calls.get(0);
        assertEquals("call_1", call.id());
        assertEquals("calculator", call.name());
        assertEquals("{\"a\":1,\"b\":2}", call.args(), "参数按 index 分片累加，且保持原始 JSON 文本");
        assertEquals("tool_calls", response.finishReason());
    }

    @Test
    void o4_usageWithReasoningDetails() throws IOException {
        server.respondSse(corpus("/llm/openai/o4-usage-reasoning.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        assertEquals(13, response.usage().totalTokens());
        assertEquals(10, response.usage().promptTokens());
        assertEquals(3, response.usage().completionTokens());
        assertEquals(2, response.usage().details().output().reasoning());
        assertEquals("stop", response.finishReason());
        // OpenAI 口径：prompt 含缓存命中 → 去缓存输入 = prompt - cached
        assertEquals(10, response.usage().freshInput());
        assertEquals(0, response.usage().partsSumDelta(), "分项和必须等于总计");
    }

    @Test
    void o5a_truncated_throwsStreamTruncated() throws IOException {
        server.respondSse(corpus("/llm/openai/o5a-truncated.sse"));

        StreamTruncatedException error = assertThrows(StreamTruncatedException.class,
                () -> model.chatStream(Request.ofUser("hi"), chunk -> {
                }));
        assertEquals("流式响应意外终止（未收到结束信号）", error.getMessage());
    }

    @Test
    void o5b_doneOnly_isComplete() throws IOException {
        server.respondSse(corpus("/llm/openai/o5b-done-only-complete.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        assertEquals("回答", response.message().content());
        assertTrue(response.finishReason() == null || response.finishReason().isEmpty(),
                "只有 [DONE] 时结束原因可以为空——空 ≠ 截断");
    }
}
