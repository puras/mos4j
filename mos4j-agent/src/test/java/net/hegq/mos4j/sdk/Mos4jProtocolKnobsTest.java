package net.hegq.mos4j.sdk;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.session.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面协议旋钮（Q4）：{@code requireFinish}（Gemini 结束原因逃生阀）与 {@code maxTokens}
 * （输出上限，模型级配置）。
 *
 * <p>两条纪律：
 * <ul>
 *   <li><b>配了就要真的生效</b>：门面转发的旋钮必须出现在请求里，否则宿主以为自己限了输出，
 *       其实请求里根本没这个字段；</li>
 *   <li><b>不生效的旋钮要报错</b>：把 {@code requireFinish} 用在非 Gemini 协议上，
 *       静默忽略比报错难查得多。</li>
 * </ul>
 */
class Mos4jProtocolKnobsTest {

    /** 本用例收集到的增量（门面链路）。 */
    private final List<StreamChunk> chunks = new ArrayList<>();

    /** 记录请求的假模型。 */
    private static final class RecordingModel implements ChatModel {

        private final List<Request> requests = new ArrayList<>();

        @Override
        public Response chat(Request request) {
            requests.add(request);
            return new Response(Message.assistant("好"), null, "stop");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    private Path tempDir() throws IOException {
        return Files.createDirectories(Path.of("target", "test-tmp", "knobs-" + System.nanoTime()));
    }

    @Test
    void maxTokensReachesTheModelRequest() throws Exception {
        RecordingModel model = new RecordingModel();
        Mos4j mos4j = Mos4j.builder()
                .model(model)
                .fileTools(false)
                .maxTokens(321)
                .logDir(tempDir().resolve("sessions"))
                .env(Map.of())
                .build();

        mos4j.agent().run(new Session("s1", null), "干活");

        assertEquals(1, model.requests.size());
        assertEquals(321, model.requests.get(0).maxTokens(),
                "配了 maxTokens 就必须真的进请求（模型级配置）");
    }

    @Test
    void withoutTheSettingMaxTokensStaysUnset() throws Exception {
        RecordingModel model = new RecordingModel();
        Mos4j mos4j = Mos4j.builder()
                .model(model)
                .fileTools(false)
                .logDir(tempDir().resolve("sessions"))
                .env(Map.of())
                .build();

        mos4j.agent().run(new Session("s1", null), "干活");

        assertEquals(null, model.requests.get(0).maxTokens(), "没配就留空（用协议自己的缺省）");
    }

    @Test
    void requireFinishOnANonGeminiProtocolFailsFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Mos4j.builder()
                        .protocol(Mos4j.Protocol.OPENAI)
                        .apiKey("sk-test")
                        .fileTools(false)
                        .requireFinish(false)
                        .logDir(tempDir().resolve("sessions"))
                        .env(Map.of())
                        .build());

        assertTrue(error.getMessage().contains("requireFinish"), error.getMessage());
        assertTrue(error.getMessage().contains("Gemini"), error.getMessage());
    }

    @Test
    void requireFinishOnAHostSuppliedModelFailsFast() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Mos4j.builder()
                        .model(new RecordingModel())
                        .fileTools(false)
                        .requireFinish(false)
                        .logDir(tempDir().resolve("sessions"))
                        .env(Map.of())
                        .build());

        assertTrue(error.getMessage().contains("requireFinish"), error.getMessage());
    }

    @Test
    void requireFinishFalseIsAcceptedForGemini() throws Exception {
        // 逃生阀本身在 LLM 层已验（g5b_requireFinishFalse_allowsMissingFinish）；
        // 这里守的是「门面能把它传到模型」这条链路不报错
        Mos4j mos4j = Mos4j.builder()
                .protocol(Mos4j.Protocol.GEMINI)
                .apiKey("g-key")
                .fileTools(false)
                .requireFinish(false)
                .logDir(tempDir().resolve("sessions"))
                .env(Map.of())
                .build();

        assertTrue(mos4j.agent().describe().modelProvider().contains("Gemini"),
                "应该装的是 Gemini 模型: " + mos4j.agent().describe().modelProvider());
    }

    @Test
    void onChunkReachesTheDriverThroughTheFacade() throws Exception {
        // 回归：门面的 stream(true) 已存在，但没有 onChunk 入口——
        // 宿主只能开流式却拿不到任何增量（「有开关、没插座」）。
        // 这里守的是整条链路：Mos4j.Builder.onChunk → Config → RunContext → 驱动。
        Mos4j mos4j = Mos4j.builder()
                .model(new StreamingRecordingModel())
                .fileTools(false)
                .stream(true)
                .onChunk(chunks::add)
                .logDir(tempDir().resolve("sessions"))
                .env(Map.of())
                .build();

        mos4j.agent().run(new Session("s1", null), "干活");

        assertEquals(List.of("甲", "乙"), chunks.stream().map(StreamChunk::delta).toList(),
                "门面装的回调必须真的接到增量");
    }

    /** 会推增量的假模型（专用流式链路）。 */
    private static final class StreamingRecordingModel implements ChatModel {

        @Override
        public Response chat(Request request) {
            throw new AssertionError("stream(true) 时应走 chatStream");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chatStream(request, emitter, CancellationToken.none());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            try {
                emitter.emit(new StreamChunk("甲", null, null, null));
                emitter.emit(new StreamChunk("乙", null, null, null));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new Response(Message.assistant("甲乙"), null, "stop");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.STREAMING);
        }
    }
}
