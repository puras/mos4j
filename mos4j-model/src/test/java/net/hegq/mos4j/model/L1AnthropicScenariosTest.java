package net.hegq.mos4j.model;

import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.protocol.anthropic.AnthropicChatModel;
import net.hegq.mos4j.model.protocol.anthropic.AnthropicConfig;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anthropic L1 场景（协议报文照抄 Go 测试字面量，期望值由 Java 侧计算）。
 *
 * <p><b>关键守则</b>（机制 M5/M6）：A5 证明「只有 message_stop 也完整、finishReason 为空合法」；
 * A10 证明「usage 两侧都有时 delta 赢、cache_read 以 delta 为权威」（内网网关真实事故回归）。</p>
 */
class L1AnthropicScenariosTest {

    private FakeModelServer server;
    private AnthropicChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeModelServer();
        model = new AnthropicChatModel(AnthropicConfig.of("test-key", server.baseUrl(), "claude-test"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static String corpus(String path) throws IOException {
        try (InputStream in = L1AnthropicScenariosTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "语料缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void a1_textStream_accumulatesDeltas() throws IOException {
        server.respondSse(corpus("/model/anthropic/a1-text-stream.sse"));

        List<String> deltas = new ArrayList<>();
        Response response = model.chatStream(Request.ofUser("hi"),
                chunk -> deltas.add(chunk.delta() == null ? "" : chunk.delta()));

        assertEquals("Hi there", response.message().content());
        assertEquals("Hi there", String.join("", deltas), "deltas 拼接必须等于聚合 content");
        assertEquals("end_turn", response.finishReason());
    }

    @Test
    void a2_toolUse_accumulatesInputJsonByIndex() throws IOException {
        server.respondSse(corpus("/model/anthropic/a2-tool-use-stream.sse"));

        Response response = model.chatStream(Request.ofUser("calc"), chunk -> {
        });

        List<ToolCall> calls = response.message().toolCalls();
        assertNotNull(calls, "A2 必须聚合出工具调用");
        assertEquals(1, calls.size());
        ToolCall call = calls.get(0);
        assertEquals("toolu_1", call.id());
        assertEquals("calculator", call.name());
        assertEquals("{\"a\":1,\"b\":2}", call.args(), "input_json_delta 按 index 累加且保持原始 JSON 文本");
        assertEquals("tool_use", response.finishReason());
    }

    @Test
    void a3_thinking_separateFromText() throws IOException {
        server.respondSse(corpus("/model/anthropic/a3-thinking-stream.sse"));

        List<String> reasoning = new ArrayList<>();
        List<String> text = new ArrayList<>();
        Response response = model.chatStream(Request.ofUser("think"), chunk -> {
            if (chunk.reasoning() != null) {
                reasoning.add(chunk.reasoning());
            }
            if (chunk.delta() != null) {
                text.add(chunk.delta());
            }
        });

        assertEquals("答案是 42", response.message().content());
        assertEquals("先想想…", response.message().reasoningContent(), "thinking_delta 单独通道，与文本分离");
        assertEquals("先想想…", String.join("", reasoning));
        assertEquals("答案是 42", String.join("", text));
    }

    @Test
    void a4_truncated_throwsStreamTruncated() throws IOException {
        server.respondSse(corpus("/model/anthropic/a4-truncated.sse"));

        StreamTruncatedException error = assertThrows(StreamTruncatedException.class,
                () -> model.chatStream(Request.ofUser("hi"), chunk -> {
                }));
        assertEquals("流式响应意外终止（未收到结束信号）", error.getMessage());
    }

    @Test
    void a5_messageStopOnly_isCompleteWithUnknownFinishReason() throws IOException {
        server.respondSse(corpus("/model/anthropic/a5-message-stop-only-complete.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        // M5：结束标记（message_stop）到即完整——没有 message_delta 也不算截断
        assertEquals("回答", response.message().content());
        assertTrue(response.finishReason() == null || response.finishReason().isEmpty(),
                "只有 message_stop 时结束原因为空——未知 ≠ 截断");
    }

    @Test
    void a6_noEndSignal_throwsStreamTruncated() throws IOException {
        server.respondSse(corpus("/model/anthropic/a6-cut-without-end-signal.sse"));

        // M5：无 message_stop 也无 stop_reason → 两者皆无才是截断
        assertThrows(StreamTruncatedException.class,
                () -> model.chatStream(Request.ofUser("hi"), chunk -> {
                }));
    }

    @Test
    void a7_usageFromMessageStart_notLost() throws IOException {
        server.respondSse(corpus("/model/anthropic/a7-usage-from-message-start.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        // 只发 message_start 的网关：用量不能丢（tokens 恒 0 是事故）
        assertEquals(10, response.usage().promptTokens());
        assertEquals(3, response.usage().details().input().cacheRead());
        assertEquals(2, response.usage().details().input().cacheWrite());
        assertEquals(15, response.usage().totalTokens(), "Anthropic 口径 total 含缓存");
        assertEquals(0, response.usage().partsSumDelta(), "分项和必须等于总计");
    }

    @Test
    void a8_usageMergesBothEvents() throws IOException {
        server.respondSse(corpus("/model/anthropic/a8-usage-merges-both.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        assertEquals(10, response.usage().promptTokens(), "输入侧来自 message_start");
        assertEquals(7, response.usage().completionTokens(), "输出侧来自 message_delta");
        assertEquals(17, response.usage().totalTokens());
        assertEquals(0, response.usage().partsSumDelta());
        assertEquals("end_turn", response.finishReason());
    }

    @Test
    void a9_usagePartsSumToTotal() throws IOException {
        server.respondSse(corpus("/model/anthropic/a9-usage-parts-sum.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        // Anthropic 口径：freshInput(900) + cacheRead(186) + cacheWrite(3) + completion(84) == total
        assertEquals(900, response.usage().freshInput());
        assertEquals(186, response.usage().details().input().cacheRead());
        assertEquals(3, response.usage().details().input().cacheWrite());
        assertEquals(84, response.usage().completionTokens());
        assertEquals(1173, response.usage().totalTokens());
        assertEquals(900 + 186 + 3 + 84, response.usage().totalTokens());
        assertEquals(0, response.usage().partsSumDelta(), "分项和必须等于总计");
    }

    @Test
    void a10_deltaWinsOverStart() throws IOException {
        server.respondSse(corpus("/model/anthropic/a10-usage-delta-wins.sse"));

        Response response = model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        // 真实事故回归：message_start 报 cache_read=1113（实为总输入 925+188），
        // message_delta 报 188（命中子集）——delta 必须赢
        assertEquals(188, response.usage().details().input().cacheRead(), "cache_read 以 delta 为权威");
        assertEquals(925, response.usage().freshInput());
        assertEquals(194, response.usage().completionTokens());
        assertEquals(1307, response.usage().totalTokens(), "925 + 188 + 194 = 1307；读反会得 2232");
        assertEquals(0, response.usage().partsSumDelta());
        assertEquals("end_turn", response.finishReason());
    }

    @Test
    void a_nonStream_parsesBlocksAndUsage() throws IOException {
        // 非流式路径：同协议形状的一次性 JSON
        server.respondJson("""
                {"id":"msg_1","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"你好"},{"type":"tool_use","id":"toolu_9","name":"calc","input":{"x":1}}],
                 "stop_reason":"tool_use",
                 "usage":{"input_tokens":12,"cache_creation_input_tokens":1,"cache_read_input_tokens":2,"output_tokens":5}}
                """);

        Response response = model.chat(Request.ofUser("hi"));

        assertEquals("你好", response.message().content());
        assertNotNull(response.message().toolCalls());
        assertEquals(1, response.message().toolCalls().size());
        assertEquals("toolu_9", response.message().toolCalls().get(0).id());
        assertEquals("{\"x\":1}", response.message().toolCalls().get(0).args());
        assertEquals("tool_use", response.finishReason());
        assertEquals(12, response.usage().promptTokens());
        assertEquals(20, response.usage().totalTokens(), "12+1+2+5");
    }

    @Test
    void a_requestCarriesModelAndSystemAndAuth() throws IOException {
        server.respondJson("""
                {"id":"msg_2","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
                """);

        model.chat(new Request(
                List.of(net.hegq.mos4j.model.Message.system("be terse"),
                        net.hegq.mos4j.model.Message.user("hi")),
                null, 0.2d, 128, null, null, null));

        String body = server.lastRequestBody();
        assertTrue(body.contains("\"model\":\"claude-test\""), "模型名必须进请求");
        assertTrue(body.contains("\"max_tokens\":128"), "宿主给的 max_tokens 原样进请求");
        assertTrue(body.contains("\"stream\":false"), "非流式显式 stream=false");
        assertTrue(body.contains("\"system\":\"be terse\""), "system 是顶层字段，不进 messages");
        assertEquals("test-key", server.lastHeader("x-api-key"), "Anthropic 用 x-api-key 鉴权");
        assertEquals("2023-06-01", server.lastHeader("anthropic-version"));
    }
}
