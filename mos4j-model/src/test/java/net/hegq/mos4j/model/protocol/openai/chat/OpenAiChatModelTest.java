package net.hegq.mos4j.model.protocol.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.chat.EmptyResponseException;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.StatusError;
import net.hegq.mos4j.model.chat.Message;
import net.hegq.mos4j.model.chat.Tool;
import net.hegq.mos4j.model.support.FakeModelServer;
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

    private FakeModelServer server;
    private OpenAiChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeModelServer();
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
        server.respondSse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n");

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
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(net.hegq.mos4j.model.chat.ContentPart.imageUrl("https://x/y.png")),
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
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart(
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
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart(
                                "audio", null, "https://x/a.wav", null, "audio/wav")),
                        null, null, null, null)),
                null, null, null, null, null, null)));
    }

    @Test
    void multimodal_fileMapsToImageUrlWithDataUrl() throws IOException {
        // Go 口径（toOpenAIContent 的 file 分支）：OpenAI 兼容协议没有文件块，file 走 image_url
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart(
                                "file", null, null, "QUJD", "application/pdf")),
                        null, null, null, null)),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("image_url", part.get("type").asText(), "OPENAI 协议里 file 只能走 image_url（对齐 Go）");
        assertEquals("data:application/pdf;base64,QUJD",
                part.get("image_url").get("url").asText(), "base64 必须包成 data URL，不能只发类型");
    }

    @Test
    void multimodal_filePrefersRemoteUrl() throws IOException {
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart(
                                "file", null, "https://x/a.pdf", "QUJD", "application/pdf")),
                        null, null, null, null)),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("https://x/a.pdf", part.get("image_url").get("url").asText(),
                "URL 优先（对齐 Go mediaURL）");
    }

    @Test
    void audioFormat_mapsMimeToWireFormat() throws IOException {
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        // audio/mpeg → mp3（旧实现会退化成子类型 mpeg，服务端不接受）
        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart(
                                "audio", null, null, "QUJD", "audio/mpeg")),
                        null, null, null, null)),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("mp3", part.get("input_audio").get("format").asText(),
                "audio/mpeg 必须映射成 mp3（对齐 Go audioFormat）");
    }

    @Test
    void unknownPartTypeFailsFast() {
        server.respondJson("{\"choices\":[]}");

        // 不再发「只有 type 的空壳块」：发出去的是服务端看不懂的东西，等于请求已废
        assertThrows(IllegalArgumentException.class, () -> model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, null,
                        List.of(new net.hegq.mos4j.model.chat.ContentPart("video", null, null, null, null)),
                        null, null, null, null)),
                null, null, null, null, null, null)));
    }

    @Test
    void reasoningContent_isEchoedOnAssistantMessages() throws IOException {
        // DeepSeek-R1 / Qwen thinking 模式：assistant 的 reasoning_content 必须原样回传，否则第二轮 400
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"2\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(Message.user("1+1=?"),
                        new Message(net.hegq.mos4j.model.chat.Role.ASSISTANT, "先分解再求解", null,
                                null, null, null, "先分解再求解的思考过程")),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        JsonNode assistant = body.get("messages").get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("先分解再求解的思考过程", assistant.get("reasoning_content").asText(),
                "请求侧必须回传 reasoning_content（对齐 Go injectReasoningContent）");
    }

    @Test
    void reasoningContent_absentWhenEmptyOrNonAssistant() throws IOException {
        server.respondJson("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}");

        model.chat(new Request(
                List.of(new Message(net.hegq.mos4j.model.chat.Role.USER, "hi", null, null, null, null,
                                "不该回传的原因"),
                        Message.assistant("ok")),
                null, null, null, null, null, null));

        JsonNode body = MAPPER.readTree(server.lastRequestBody());
        assertTrue(body.get("messages").get(0).get("reasoning_content") == null,
                "只对 assistant 消息注入");
        assertTrue(body.get("messages").get(1).get("reasoning_content") == null,
                "空推理不写字段（不发明\"reasoning_content\":null）");
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
