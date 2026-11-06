package net.hegq.mos4j.llm.protocol.openai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.EmptyResponseException;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StatusError;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Tool;
import net.hegq.mos4j.llm.support.FakeLlmServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 适配器的协议映射与错误分层测试（T1.4 / T1.5 / T1.6 / T1.7）。
 */
class OpenAiChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeLlmServer server;
    private OpenAiChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLlmServer();
        model = new OpenAiChatModel(OpenAiConfig.of("secret-key", server.baseUrl(), "gpt-test"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void chat_mapsRequestAndResponse() throws IOException {
        server.respondJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"你好"},
                "finish_reason":"stop"}],
                "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                """);

        Response response = model.chat(new Request(
                List.of(Message.user("你好")),
                List.of(new Tool("read", "读文件", Map.of("type", "object"))),
                0.2d, 256, null, null, null));

        assertEquals("你好", response.message().content());
        assertEquals("stop", response.finishReason());
        assertEquals(17, response.usage().totalTokens());
        assertEquals(12, response.usage().freshInput());

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        assertEquals("gpt-test", body.get("model").asText());
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertEquals("你好", body.get("messages").get(0).get("content").asText());
        assertEquals("read", body.get("tools").get(0).get("function").get("name").asText());
        assertEquals("function", body.get("tools").get(0).get("type").asText());
        assertEquals(0.2d, body.get("temperature").asDouble());
        assertEquals(256, body.get("max_tokens").asInt());
        assertEquals("Bearer secret-key", server.lastAuthHeader());
    }

    @Test
    void chatStream_setsStreamFlags() throws IOException {
        server.respondSse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\ndata: [DONE]\n");

        model.chatStream(Request.ofUser("hi"), chunk -> {
        });

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        assertTrue(body.get("stream").asBoolean(), "流式请求必须带 stream=true");
        assertTrue(body.get("stream_options").get("include_usage").asBoolean(),
                "流式请求必须请求用量（否则拿不到 token 口径）");
    }

    @Test
    void toolCallArgs_stayRawJsonText() throws IOException {
        server.respondJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function",
                "function":{"name":"read","arguments":"{\\"path\\":\\"a.txt\\"}"}}]},
                "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """);

        Response response = model.chat(Request.ofUser("读文件"));

        assertNotNull(response.message().toolCalls());
        assertEquals("call_1", response.message().toolCalls().get(0).id());
        assertEquals("read", response.message().toolCalls().get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", response.message().toolCalls().get(0).args(),
                "工具参数必须保持原始 JSON 文本（载荷冻结）");
    }

    @Test
    void httpError_becomesStatusErrorWithCode() {
        server.respondStatus(429, "{\"error\":{\"message\":\"rate limited\",\"code\":\"rate_limit_exceeded\"}}");

        StatusError error = assertThrows(StatusError.class, () -> model.chat(Request.ofUser("hi")));

        assertEquals("openai", error.provider());
        assertEquals(429, error.statusCode());
        assertTrue(error.getMessage().contains("rate limited"), "错误正文必须可见：" + error.getMessage());
        assertEquals(429, StatusError.statusCodeOf(error));
    }

    @Test
    void emptyChoices_becomesEmptyResponseSentinel() {
        server.respondJson("{\"choices\":[]}");

        assertThrows(EmptyResponseException.class, () -> model.chat(Request.ofUser("hi")));
    }

    @Test
    void multimodal_imageMapsToImageUrl() throws IOException {
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.llm.model.Role.USER, null,
                        List.of(net.hegq.mos4j.llm.model.ContentPart.imageUrl("https://x/y.png")),
                        null, null, null, null)),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("image_url", part.get("type").asText());
        assertEquals("https://x/y.png", part.get("image_url").get("url").asText());
    }

    @Test
    void multimodal_audioMapsToInputAudio() throws IOException {
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.llm.model.Role.USER, null,
                        List.of(new net.hegq.mos4j.llm.model.ContentPart(
                                "audio", null, null, "QUJD", "audio/wav")),
                        null, null, null, null)),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("input_audio", part.get("type").asText(), "音频不能当图片发");
        assertEquals("QUJD", part.get("input_audio").get("data").asText());
        assertEquals("wav", part.get("input_audio").get("format").asText());
    }

    @Test
    void audioWithoutDataFailsFast() {
        server.respondJson("{\"choices\":[]}");

        assertThrows(IllegalArgumentException.class, () -> model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.llm.model.Role.USER, null,
                        List.of(new net.hegq.mos4j.llm.model.ContentPart(
                                "audio", null, "https://x/a.wav", null, "audio/wav")),
                        null, null, null, null)),
                null, null, null, null, null, null)));
    }

    @Test
    void nonOk2xxIsSuccess() throws IOException {
        // 201/204 也算成功：口径是「非 2xx 才算失败」，不是「只有 200 算成功」
        server.respondStatus(201, "{\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"好\"},\"finish_reason\":\"stop\"}]}");

        Response response = model.chat(Request.ofUser("hi"));

        assertEquals("好", response.message().content());
    }

    @Test
    void cancellation_abortsRequest() throws InterruptedException {
        server.delay(Duration.ofSeconds(5)).respondSse("data: [DONE]\n");

        CancellationToken token = CancellationToken.create();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            token.cancel();
        });
        canceller.start();

        assertThrows(CancellationException.class, () -> model.chatStream(Request.ofUser("hi"), chunk -> {
        }, token));
        canceller.join(2000);
    }
}
