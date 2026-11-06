package net.hegq.mos4j.llm;

import net.hegq.mos4j.core.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试执行器（T6.5）：默认不装配就没有重试；只重试瞬时错误；
 * 取消终止性；有界指数退避；流式只在未吐内容时重试。
 */
class RetryingChatModelTest {

    /** 可编程假模型：按脚本依次抛错，之后返回成功。 */
    private static final class ScriptModel implements ChatModel {
        private final List<RuntimeException> script;
        private final Response ok;
        int calls;

        ScriptModel(List<RuntimeException> script) {
            this.script = new ArrayList<>(script);
            this.ok = new Response(net.hegq.mos4j.llm.model.Message.assistant("done"),
                    new net.hegq.mos4j.llm.model.Usage(1, 1, 1, 2, null), "end_turn");
        }

        private RuntimeException next() {
            calls++;
            if (calls <= script.size()) {
                throw script.get(calls - 1);
            }
            return null;
        }

        @Override
        public Response chat(Request request) {
            RuntimeException e = next();
            if (e != null) {
                throw e;
            }
            return ok;
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            RuntimeException e = next();
            if (e != null) {
                throw e;
            }
            try {
                emitter.emit(new StreamChunk("done", null, null, "end_turn"));
            } catch (Exception ignored) {
                // 测试桩不关心发射异常
            }
            return ok;
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chatStream(request, emitter);
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of();
        }
    }

    private static RetryPolicy fast() {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(50))
                .build();
    }

    /** 不真睡的睡眠器（记录时长）。 */
    private static final class RecordingSleeper implements RetryingChatModel.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration.toMillis());
        }
    }

    @Test
    void noPolicyMeansPassThrough_singleCall() {
        ScriptModel delegate = new ScriptModel(List.of(new StatusError("openai", 500, "boom")));
        RetryingChatModel model = new RetryingChatModel(delegate, null);

        assertThrows(StatusError.class, () -> model.chat(Request.ofUser("hi")));
        assertEquals(1, delegate.calls, "默认不装配就没有重试");
    }

    @Test
    void retriesTransient_thenSucceeds() {
        ScriptModel delegate = new ScriptModel(List.of(
                new StatusError("openai", 429, "rate limited"),
                new StatusError("openai", 503, "unavailable")));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingChatModel model = new RetryingChatModel(delegate, fast(),
                RandomGenerator.getDefault(), sleeper);

        Response response = model.chat(Request.ofUser("hi"));
        assertEquals("done", response.message().content());
        assertEquals(3, delegate.calls);
        assertEquals(2, sleeper.sleeps.size(), "两次失败各退避一次");
    }

    @Test
    void doesNotRetryPermanentErrors() {
        ScriptModel delegate = new ScriptModel(List.of(new StatusError("openai", 400, "bad request")));
        RetryingChatModel model = new RetryingChatModel(delegate, fast(),
                RandomGenerator.getDefault(), new RecordingSleeper());

        StatusError error = assertThrows(StatusError.class, () -> model.chat(Request.ofUser("hi")));
        assertEquals(400, error.statusCode());
        assertEquals(1, delegate.calls, "4xx 不重试");
    }

    @Test
    void emptyResponseIsTransient_truncatedIsNot() {
        RetryPolicy policy = fast();
        assertTrue(policy.isTransient(new EmptyResponseException()));
        assertTrue(policy.isTransient(new UncheckedIOException("reset", new IOException())));
        assertTrue(policy.isTransient(new StatusError("openai", 502, "bad gateway")));
        java.util.concurrent.CancellationException cancelled = new java.util.concurrent.CancellationException();
        assertTrue(!policy.isTransient(cancelled), "取消终止性");
        assertTrue(!policy.isTransient(new StreamTruncatedException()), "截断不重试（M5）");
        assertTrue(!policy.isTransient(new StreamAbortedException("stop", null)), "宿主中断不重试");
    }

    @Test
    void exhaustsAttempts_thenThrowsLast() {
        ScriptModel delegate = new ScriptModel(List.of(
                new StatusError("openai", 500, "a"),
                new StatusError("openai", 500, "b"),
                new StatusError("openai", 500, "c")));
        RetryingChatModel model = new RetryingChatModel(delegate, fast(),
                RandomGenerator.getDefault(), new RecordingSleeper());

        StatusError error = assertThrows(StatusError.class, () -> model.chat(Request.ofUser("hi")));
        assertEquals("openai: c", error.getMessage(), "重试耗尽抛最后一次的错误");
        assertEquals(3, delegate.calls);
    }

    @Test
    void cancellationDuringBackoff_isTerminal() {
        ScriptModel delegate = new ScriptModel(List.of(new StatusError("openai", 500, "boom")));
        CancellationToken token = CancellationToken.create();
        RetryingChatModel.Sleeper cancelling = duration -> {
            token.cancel();
            Thread.sleep(duration.toMillis());
        };
        RetryingChatModel model = new RetryingChatModel(delegate, fast(),
                RandomGenerator.getDefault(), cancelling);

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> model.chat(Request.ofUser("hi"), token));
        assertEquals(1, delegate.calls, "退避中取消 → 不再发起下一次尝试");
    }

    @Test
    void backoffIsBoundedExponentialWithJitter() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(12)
                .initialBackoff(Duration.ofMillis(100))
                .maxBackoff(Duration.ofMillis(400))
                .build();
        java.util.random.RandomGenerator random = new java.util.Random(42);

        for (int attempt = 1; attempt <= 12; attempt++) {
            long ms = policy.backoff(attempt, random).toMillis();
            assertTrue(ms >= 0, "退避非负: " + ms);
            if (attempt == 1) {
                assertTrue(ms >= 80 && ms <= 120, "首次 ≈100ms±20%: " + ms);
            } else if (attempt == 2) {
                assertTrue(ms >= 160 && ms <= 240, "第二次 ≈200ms±20%: " + ms);
            } else {
                assertTrue(ms >= 320 && ms <= 480,
                        "第三次起封顶 400ms±20%: attempt=" + attempt + " ms=" + ms);
            }
        }
    }

    @Test
    void streamRetryOnlyBeforeFirstEmittedChunk() {
        // 第一次尝试吐了一个块再失败；第二次尝试成功——不允许重试（内容会重复）
        ChatModel flaky = new ChatModel() {
            int calls;
            boolean emitted;

            @Override
            public Response chat(Request request) {
                throw new IllegalStateException("不走这条路");
            }

            @Override
            public Response chat(Request request, CancellationToken cancellation) {
                return chat(request);
            }

            @Override
            public Response chatStream(Request request, StreamEmitter emitter) {
                calls++;
                try {
                    if (calls == 1) {
                        emitter.emit(new StreamChunk("half", null, null, null));
                        throw new StatusError("openai", 500, "mid-stream failure");
                    }
                    emitter.emit(new StreamChunk("half", null, null, null));
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                }
                return new Response(net.hegq.mos4j.llm.model.Message.assistant("half"),
                        new net.hegq.mos4j.llm.model.Usage(0, 0, 0, 0, null), null);
            }

            @Override
            public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
                return chatStream(request, emitter);
            }

            @Override
            public java.util.Set<Capability> capabilities() {
                return java.util.Set.of();
            }
        };
        RetryingChatModel model = new RetryingChatModel(flaky, fast(),
                RandomGenerator.getDefault(), new RecordingSleeper());

        StatusError error = assertThrows(StatusError.class,
                () -> model.chatStream(Request.ofUser("hi"), chunk -> {
                }));
        assertEquals("openai: mid-stream failure", error.getMessage());
    }

    @Test
    void streamRetryWorksWhenNothingEmitted() {
        ScriptModel delegate = new ScriptModel(List.of(new StatusError("openai", 503, "early failure")));
        RetryingChatModel model = new RetryingChatModel(delegate, fast(),
                RandomGenerator.getDefault(), new RecordingSleeper());

        List<String> chunks = new ArrayList<>();
        Response response = model.chatStream(Request.ofUser("hi"),
                chunk -> chunks.add(chunk.delta() == null ? "" : chunk.delta()));

        assertEquals("done", String.join("", chunks));
        assertEquals(2, delegate.calls, "未吐内容时可以重试");
    }
}
