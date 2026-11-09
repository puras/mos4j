package net.hegq.mos4j.model;

import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.protocol.gemini.GeminiChatModel;
import net.hegq.mos4j.model.protocol.gemini.GeminiConfig;
import net.hegq.mos4j.model.support.FakeModelServer;
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
 * Gemini L1 场景（协议报文照抄 Go 测试字面量，期望值由 Java 侧计算）。
 *
 * <p><b>关键守则</b>（M5）：finishReason 即结束标记（比 OpenAI/Anthropic 更严一侧）；
 * {@code require_finish=false} 是宿主面对不报 finishReason 的网关时的逃生阀（G5b）。</p>
 */
class L1GeminiScenariosTest {

    private FakeModelServer server;
    private GeminiChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeModelServer();
        model = new GeminiChatModel(GeminiConfig.of("test-key", server.baseUrl(), "gemini-test"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static String corpus(String path) throws IOException {
        try (InputStream in = L1GeminiScenariosTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "语料缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void g1_textStream_accumulatesParts() throws IOException {
        server.respondSse(corpus("/model/gemini/g1-text-stream.sse"));

        List<String> deltas = new ArrayList<>();
        Response response = model.chatStream(Request.ofUser("hi"),
                chunk -> deltas.add(chunk.delta() == null ? "" : chunk.delta()));

        assertEquals("Hello world", response.message().content());
        assertEquals("Hello world", String.join("", deltas), "parts 拼接必须等于聚合 content");
        assertEquals("STOP", response.finishReason());
        // usageMetadata 在 finishReason 之后的 data 行到达——不丢
        assertEquals(5, response.usage().promptTokens());
        assertEquals(3, response.usage().completionTokens());
        assertEquals(8, response.usage().totalTokens());
        assertEquals(0, response.usage().partsSumDelta());
    }

    @Test
    void g2_functionCall_completeJsonObjectArgs() throws IOException {
        server.respondSse(corpus("/model/gemini/g2-function-call.sse"));

        Response response = model.chatStream(Request.ofUser("calc"), chunk -> {
        });

        List<ToolCall> calls = response.message().toolCalls();
        assertNotNull(calls, "G2 必须聚合出工具调用");
        assertEquals(1, calls.size(), "同 id 的 functionCall 跨 chunk 重复，按 id 合并");
        ToolCall call = calls.get(0);
        assertEquals("fc_1", call.id());
        assertEquals("calculator", call.name());
        assertEquals("{\"a\":1,\"b\":2}", call.args(), "args 是完整 JSON 对象序列化");
        assertEquals("STOP", response.finishReason());
    }

    @Test
    void g5a_truncated_throwsWhenRequireFinish() throws IOException {
        server.respondSse(corpus("/model/gemini/g5a-truncated.sse"));

        // 缺省 require_finish=true：流读完仍无 finishReason = 截断
        StreamTruncatedException error = assertThrows(StreamTruncatedException.class,
                () -> model.chatStream(Request.ofUser("hi"), chunk -> {
                }));
        assertEquals("流式响应意外终止（未收到结束信号）", error.getMessage());
    }

    @Test
    void g5b_requireFinishFalse_allowsMissingFinish() throws IOException {
        server.respondSse(corpus("/model/gemini/g5b-require-finish-false.sse"));

        GeminiChatModel lax = new GeminiChatModel(
                GeminiConfig.of("test-key", server.baseUrl(), "gemini-test"), false);

        Response response = lax.chatStream(Request.ofUser("hi"), chunk -> {
        });

        assertEquals("回答", response.message().content(), "逃生阀放行");
        assertTrue(response.finishReason() == null || response.finishReason().isEmpty());
    }

    @Test
    void g_nonStream_parsesPartsAndUsage() throws IOException {
        server.respondJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"你好"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":2,"totalTokenCount":8,
                   "cachedContentTokenCount":2}}
                """);

        Response response = model.chat(Request.ofUser("hi"));

        assertEquals("你好", response.message().content());
        assertEquals("STOP", response.finishReason());
        assertEquals(6, response.usage().promptTokens());
        assertEquals(2, response.usage().completionTokens());
        assertEquals(8, response.usage().totalTokens());
        assertEquals(2, response.usage().details().input().cacheRead());
        // Gemini 口径：prompt 含缓存读 → freshInput = prompt - cached = 4
        assertEquals(4, response.usage().freshInput());
        assertEquals(0, response.usage().partsSumDelta());
    }

    @Test
    void g_requestCarriesModelPathAndApiKey() throws IOException {
        server.respondJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}
                """);

        model.chat(Request.ofUser("hi"));

        String body = server.lastRequestBody();
        assertTrue(body.contains("\"contents\""), "请求体带 contents");
        assertTrue(body.contains("\"user\":\"hi\"") || body.contains("\"text\":\"hi\""),
                "用户消息进 contents");
        // Gemini 路径带模型名：/models/{model}:generateContent
        String path = server.lastPath();
        assertTrue(path.contains("/models/gemini-test:generateContent"),
                "非流式走 generateContent，路径带模型名: " + path);
        assertEquals("test-key", server.lastHeader("x-goog-api-key"), "Gemini 用 x-goog-api-key 鉴权");
    }
}
