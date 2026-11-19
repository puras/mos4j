package net.hegq.mos4j.examples.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.chat.Message;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.chat.Role;
import net.hegq.mos4j.model.chat.StreamEmitter;
import net.hegq.mos4j.model.protocol.anthropic.chat.AnthropicChatModel;
import net.hegq.mos4j.model.protocol.anthropic.chat.AnthropicConfig;
import net.hegq.mos4j.model.protocol.gemini.chat.GeminiChatModel;
import net.hegq.mos4j.model.protocol.gemini.chat.GeminiConfig;
import net.hegq.mos4j.model.protocol.openai.chat.OpenAiChatModel;
import net.hegq.mos4j.model.protocol.openai.chat.OpenAiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯模型示例的用例：证明示例里的调用面**真的能跑**，两层验证，都不碰外网：
 *
 * <ol>
 *   <li>示例流程跑在共享的脚本化替身 {@link StubModel} 上（调用面语义：消息怎么带、
 *       多轮状态怎么归宿主、增量到没到宿主）；</li>
 *   <li>三个真协议模型打在本地假 HTTP 端点上（报文层抽样：换协议真的只是换装配，
 *       装配代码没有退化成「看起来对、其实没跑过」）。完整报文语料由 mos4j-model
 *       自己的 L1 用例守，这里只抽样最常用的一条通路。</li>
 * </ol>
 */
class ModelExampleTest {

    // ------------------------------------------------------------- 语义层（StubModel）

    @Test
    @DisplayName("示例流程：替身模型上非流式 / 流式 / 多轮全跑通")
    void exampleFlowsRunOnStub() throws Exception {
        StubModel model = StubModel.of(StubModel.text("1+1=2"));

        assertEquals("1+1=2", ModelExample.chatOnce(model, "只回答：1+1=?"));
        assertEquals(1, model.requests().size(), "非流式只调了一次模型");
        assertEquals(Role.SYSTEM, model.requests().get(0).messages().get(0).role(),
                "system 提示词是消息列表第一条");
        assertEquals(Role.USER, model.requests().get(0).messages().get(1).role());

        StubModel raw = StubModel.of(StubModel.text("流式回答"));
        StreamingStub streaming = new StreamingStub(raw);
        StringBuilder deltas = new StringBuilder();
        assertEquals("流式回答", ModelExample.chatStream(streaming, "问", chunkRecorder(deltas)),
                "流式返回聚合后的完整文本");
        assertEquals("流式回答", deltas.toString(), "增量块确实到达了宿主给的 emitter");

        StubModel multi = StubModel.of(StubModel.text("1+1=2"), StubModel.text("你问的是 1+1"));
        assertEquals("你问的是 1+1", ModelExample.multiTurn(multi, "我刚才问了你什么？"));
        assertEquals(2, multi.requests().size(), "多轮 = 两次独立调用");
        List<Message> second = multi.requests().get(1).messages();
        assertEquals(Role.USER, second.get(1).role(), "第二轮带回了上一轮的用户消息");
        assertEquals("1+1=2", second.get(2).content(), "第二轮带回了上一轮的助手消息");
        assertEquals(Role.ASSISTANT, second.get(2).role());
        assertEquals("我刚才问了你什么？", second.get(3).content());
    }

    @Test
    @DisplayName("能力快照：capabilities() 如实返回，supports() 按它判断")
    void capabilitiesAreVisible() {
        StubModel stub = StubModel.of(StubModel.text("ok"));
        assertTrue(stub.capabilities().isEmpty(), "替身不带能力位");
        assertFalse(stub.supports(Capability.STREAMING));
    }

    @Test
    @DisplayName("流式 fail-closed：不支持流式的模型直接拒绝，不试运气")
    void streamWithoutCapabilityIsRefused() {
        // 共享替身不带任何能力位：正是「能力缺口 fail-closed」要拦的形状
        StubModel noStream = StubModel.of(StubModel.text("不该被看到"));
        assertThrows(IllegalStateException.class,
                () -> ModelExample.chatStream(noStream, "问"));
    }

    @Test
    @DisplayName("统一调用面：未知协议直接报错（fail-closed，不静默挑一个）")
    void unknownProtocolFailsClosed() {
        StubModel stub = StubModel.of(StubModel.text("ok"));
        assertThrows(IllegalStateException.class,
                () -> ModelExample.run("llama.cpp-自造协议", stub, "问"));
        assertEquals("ok", ModelExample.run("openai", stub, "问"), "注册过的协议照常走");
    }

    @Test
    @DisplayName("期限取消：真实 HTTP 通路，到点 cancel() → 底层交换被真中止")
    void deadlineCancellationSurfaces() throws Exception {
        // 服务端 3 秒后才回包；宿主 200ms 就取消——验证取消打穿到 HTTP 层，不是只置标志
        FakeEchoServer server = new FakeEchoServer("{}", 3000);
        ChatModel model = new OpenAiChatModel(
                OpenAiConfig.of("sk-test", server.baseUrl(), "gpt-test"));
        CancellationToken token = CancellationToken.create();
        Thread timer = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        }, "deadline");
        timer.start();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> model.chat(Request.ofUser("问"), token),
                    "协作式检查点如实抛取消（不重试、不静默吞）");
        } finally {
            timer.join();
            server.close();
        }
    }

    // --------------------------------------------------- 报文层抽样（真协议 × 本地假端点）

    @Test
    @DisplayName("三个协议同一调用面：各自装配、各自端点，报文带上了消息；Gemini 模型名在 URL 不在 body")
    void allThreeProtocolsUseTheSameCallSurface() throws Exception {
        FakeEchoServer openaiServer = new FakeEchoServer("""
                {"choices":[{"message":{"role":"assistant","content":"报文替身回答"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}""");
        FakeEchoServer anthropicServer = new FakeEchoServer("""
                {"content":[{"type":"text","text":"报文替身回答"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":3,"output_tokens":4}}""");
        FakeEchoServer geminiServer = new FakeEchoServer("""
                {"candidates":[{"content":{"parts":[{"text":"报文替身回答"}]},
                                "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,
                                  "totalTokenCount":7}}""");

        record ProtocolCase(String protocol, FakeEchoServer server, ChatModel model,
                            String wireModel, String finishReason, boolean modelInBody) {
        }
        List<ProtocolCase> cases = List.of(
                new ProtocolCase("openai", openaiServer,
                        new OpenAiChatModel(OpenAiConfig.of("sk-test", openaiServer.baseUrl(), "gpt-test")),
                        "gpt-test", "stop", true),
                new ProtocolCase("anthropic", anthropicServer,
                        new AnthropicChatModel(AnthropicConfig.of("sk-test", anthropicServer.baseUrl(), "claude-test")),
                        "claude-test", "end_turn", true),
                new ProtocolCase("gemini", geminiServer,
                        new GeminiChatModel(GeminiConfig.of("key-test", geminiServer.baseUrl(), "gemini-test")),
                        "gemini-test", "STOP", false));

        try {
            for (ProtocolCase expected : cases) {
                String answer = ModelExample.run(expected.protocol(), expected.model(), "只回答：1+1=?");
                assertEquals("报文替身回答", answer, expected.protocol() + " 的回答");

                String body = expected.server().lastRequestBody();
                if (expected.modelInBody()) {
                    assertTrue(body.contains("\"" + expected.wireModel() + "\""),
                            expected.protocol() + "：模型名进了请求体: " + body);
                } else {
                    assertFalse(body.contains(expected.wireModel()),
                            expected.protocol() + "：模型名走 URL，不在请求体里");
                }
                assertTrue(body.contains("只回答：1+1=?"), expected.protocol() + "：用户问题进了请求体");
                assertTrue(body.contains(ModelExample.SYSTEM_PROMPT),
                        expected.protocol() + "：系统提示词进了请求体");

                Response response = expected.model().chat(Request.ofUser("再问一次"));
                assertEquals(expected.finishReason(), response.finishReason(),
                        expected.protocol() + "：结束原因如实透传");
                assertEquals(3, response.usage().freshInput(), "输入 token（去缓存口径）");
                assertEquals(4, response.usage().completionTokens(), "输出 token");
            }
        } finally {
            for (ProtocolCase expected : cases) {
                expected.server().close();
            }
        }
    }

    /** 本地假模型端点：起一个回包的 HTTP 服务，记下请求体（报文层抽样的替身）。 */
    private static final class FakeEchoServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");

        FakeEchoServer(String responseBody) throws IOException {
            this(responseBody, 0);
        }

        FakeEchoServer(String responseBody, long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange, responseBody, delayMillis));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        private void handle(HttpExchange exchange, String responseBody, long delayMillis)
                throws IOException {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try (InputStream in = exchange.getRequestBody()) {
                lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        String lastRequestBody() {
            return lastRequestBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 给替身补上流式能力位（替身本身不声明能力；包装后才能过 fail-closed 检查）。 */
    private static final class StreamingStub implements ChatModel {
        private final StubModel delegate;

        StreamingStub(StubModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response chat(Request request) {
            return delegate.chat(request);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return delegate.chat(request, cancellation);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return delegate.chatStream(request, emitter);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return delegate.chatStream(request, emitter, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.STREAMING);
        }
    }

    /** 把增量块拼起来的 emitter（断言「增量到达宿主」用）。 */
    private static StreamEmitter chunkRecorder(StringBuilder sink) {
        return chunk -> {
            if (chunk.delta() != null) {
                sink.append(chunk.delta());
            }
        };
    }
}
