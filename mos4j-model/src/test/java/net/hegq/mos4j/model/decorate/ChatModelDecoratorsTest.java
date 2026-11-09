package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ModelTimeoutException;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装饰器（观测 / 超时 / 降级）的守护测试。
 *
 * <p>守护的口径与 Go 参考实现 {@code llm/wrap.go} 一致：
 * 观测统一在结束时上报、超时是协作式取消、降级只在失败时发生且能力集取并集。
 */
class ChatModelDecoratorsTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 可编程的模型替身。 */
    private static class Fake implements ChatModel {

        final String name;
        final boolean fail;
        final Set<Capability> capabilities;
        List<CancellationToken> tokens = new ArrayList<>();
        int calls = 0;

        Fake(String name, boolean fail, Capability... capabilities) {
            this.name = name;
            this.fail = fail;
            this.capabilities = capabilities.length == 0
                    ? Set.of()
                    : EnumSet.copyOf(List.of(capabilities));
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            calls++;
            tokens.add(cancellation);
            if (fail) {
                throw new IllegalStateException(name + " 挂了");
            }
            return new Response(Message.assistant(name), NO_USAGE, Response.FINISH_END_TURN);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chatStream(request, emitter, CancellationToken.none());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return capabilities;
        }
    }

    private static Request request() {
        return Request.of(List.of(Message.user("hi")));
    }

    @Test
    void aSuccessfulCallIsReportedOnce() {
        Fake model = new Fake("主", false);
        List<CallRecord> records = new ArrayList<>();

        Response response = new LoggingChatModel(model, records::add).chat(request());

        assertEquals("主", response.message().content());
        assertEquals(1, records.size());
        assertTrue(records.get(0).succeeded());
        assertNotNull(records.get(0).response());
        assertFalse(records.get(0).stream());
    }

    @Test
    void aFailedCallIsAlsoReported() {
        Fake model = new Fake("主", true);
        List<CallRecord> records = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> new LoggingChatModel(model, records::add).chat(request()));

        assertEquals(1, records.size());
        assertFalse(records.get(0).succeeded());
        assertNotNull(records.get(0).error());
    }

    @Test
    void aStreamCallIsFlaggedAsStream() {
        Fake model = new Fake("主", false);
        List<CallRecord> records = new ArrayList<>();

        new LoggingChatModel(model, records::add).chatStream(request(), chunk -> {
        });

        assertEquals(1, records.size());
        assertTrue(records.get(0).stream());
    }

    @Test
    void theTimeoutCancelsTheModelCooperatively() {
        // 模型一直等到令牌被取消：超时靠协作式取消生效。
        Fake slow = new Fake("慢", false) {
            @Override
            public Response chat(Request request, CancellationToken cancellation) {
                calls++;
                tokens.add(cancellation);
                while (!cancellation.isCancelled()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                cancellation.throwIfCancelled();
                return new Response(Message.assistant("慢"), NO_USAGE, Response.FINISH_END_TURN);
            }
        };

        TimeoutChatModel timed = new TimeoutChatModel(slow, Duration.ofMillis(50));

        assertThrows(ModelTimeoutException.class, () -> timed.chat(request()));
        assertTrue(slow.tokens.get(0).isCancelled(), "超时要把令牌置为已取消");
    }

    @Test
    void anExternalCancellationIsNotReportedAsATimeout() {
        Fake model = new Fake("主", false);
        CancellationToken external = CancellationToken.create();
        external.cancel();

        TimeoutChatModel timed = new TimeoutChatModel(model, Duration.ofSeconds(30));

        assertThrows(CancellationException.class, () -> timed.chat(request(), external));
    }

    @Test
    void aFastCallIsNotAffectedByTheTimeout() {
        Fake model = new Fake("快", false);
        TimeoutChatModel timed = new TimeoutChatModel(model, Duration.ofSeconds(30));

        Response response = timed.chat(request());

        assertEquals("快", response.message().content());
        assertFalse(model.tokens.get(0).isCancelled());
    }

    @Test
    void aFailedPrimaryFallsBackToTheBackup() {
        Fake primary = new Fake("主", true);
        Fake backup = new Fake("备", false);

        Response response = new FallbackChatModel(primary, backup).chat(request());

        assertEquals("备", response.message().content());
        assertEquals(1, primary.calls);
        assertEquals(1, backup.calls);
    }

    @Test
    void aHealthyPrimaryIsNotTouchedByTheBackup() {
        Fake primary = new Fake("主", false);
        Fake backup = new Fake("备", false);

        Response response = new FallbackChatModel(primary, backup).chat(request());

        assertEquals("主", response.message().content());
        assertEquals(0, backup.calls, "主模型没失败就不该动备用");
    }

    @Test
    void withoutABackupAFailureStillPropagates() {
        Fake primary = new Fake("主", true);

        assertThrows(IllegalStateException.class,
                () -> new FallbackChatModel(primary, null).chat(request()));
    }

    @Test
    void theCapabilitiesAreTheUnionOfBothModels() {
        Fake primary = new Fake("主", false, Capability.FUNCTION_CALLING);
        Fake backup = new Fake("备", false, Capability.STREAMING);

        Set<Capability> capabilities = new FallbackChatModel(primary, backup).capabilities();

        assertTrue(capabilities.contains(Capability.FUNCTION_CALLING));
        assertTrue(capabilities.contains(Capability.STREAMING));
    }

    @Test
    void theDecoratorsComposeExplicitly() {
        Fake model = new Fake("主", false);
        List<CallRecord> records = new ArrayList<>();

        ChatModel composed = new LoggingChatModel(
                new TimeoutChatModel(model, Duration.ofSeconds(30)),
                records::add);

        Response response = composed.chat(request());

        assertEquals("主", response.message().content());
        assertEquals(1, records.size());
        assertSame(model, ((ChatModelDecorator) ((ChatModelDecorator) composed).delegate()).delegate());
    }
}
