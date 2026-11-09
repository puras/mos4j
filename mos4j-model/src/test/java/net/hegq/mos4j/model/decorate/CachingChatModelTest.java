package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Tool;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地结果缓存装饰器的守护测试（对齐 Go 参考实现 {@code llm.WithCache}）。
 *
 * <p>守护四条语义：只缓存非流式结果、{@code ttl<=0} 不过期、失败不缓存、
 * 命中返回同一个（共享只读）对象。
 */
class CachingChatModelTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 可编程的模型替身。 */
    private static class Fake implements ChatModel {

        final boolean fail;
        int calls = 0;
        int streamCalls = 0;

        Fake(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            calls++;
            if (fail) {
                throw new IllegalStateException("模型挂了");
            }
            return new Response(Message.assistant("答案"), NO_USAGE, Response.FINISH_END_TURN);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chatStream(request, emitter, CancellationToken.none());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            streamCalls++;
            return new Response(Message.assistant("流"), NO_USAGE, Response.FINISH_END_TURN);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    private static Request request(String content) {
        return Request.of(List.of(Message.user(content)));
    }

    @Test
    void theSameRequestHitsTheCache() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));

        Response first = cached.chat(request("hi"));
        Response second = cached.chat(request("hi"));

        assertEquals(1, model.calls, "同样的请求不该打第二次服务端");
        assertSame(first, second, "命中时返回同一个（共享只读）对象");
    }

    @Test
    void aDifferentRequestIsNotServedFromTheCache() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));

        cached.chat(request("hi"));
        cached.chat(request("别的"));

        assertEquals(2, model.calls, "请求内容不同就是不同的请求");
    }

    @Test
    void theToolsArePartOfTheCacheKey() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));
        Request plain = request("hi");
        Request withTool = new Request(plain.messages(),
                List.of(new Tool("read", "读文件", Map.of("type", "object"))),
                null, null, null, null, null);

        cached.chat(plain);
        cached.chat(withTool);

        assertEquals(2, model.calls, "工具清单变了就是不同的请求（不能拿无工具的答案糊弄）");
    }

    @Test
    void aZeroTtlNeverExpires() throws Exception {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ZERO);

        cached.chat(request("hi"));
        Thread.sleep(20);
        cached.chat(request("hi"));

        assertEquals(1, model.calls, "ttl=0 表示不过期");
    }

    @Test
    void anExpiredEntryIsNotServed() throws Exception {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMillis(30));

        cached.chat(request("hi"));
        Thread.sleep(80);
        cached.chat(request("hi"));

        assertEquals(2, model.calls, "过了存活时间就该重新问服务端");
    }

    @Test
    void aFailureIsNotCached() {
        Fake model = new Fake(true);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));

        assertThrows(IllegalStateException.class, () -> cached.chat(request("hi")));
        assertThrows(IllegalStateException.class, () -> cached.chat(request("hi")));

        assertEquals(2, model.calls, "错误不是答案，不该被缓存下来反复吐出来");
    }

    @Test
    void aStreamCallIsNotCached() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));

        cached.chatStream(request("hi"), chunk -> {
        });
        cached.chatStream(request("hi"), chunk -> {
        });

        assertEquals(2, model.streamCalls, "流式不缓存（流没法重放给第二个订阅者）");
        assertEquals(0, cached.size());
    }

    @Test
    void aCancelledCallIsNotServedFromTheCache() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));
        cached.chat(request("hi"));
        CancellationToken cancelled = CancellationToken.create();
        cancelled.cancel();

        assertThrows(CancellationException.class, () -> cached.chat(request("hi"), cancelled));
        assertEquals(1, model.calls, "叫停不能被缓存悄悄吞掉");
    }

    @Test
    void theCacheIsUsableFromSeveralThreads() throws Exception {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        cached.chat(request("hi"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertTrue(model.calls >= 1, "并发下至少要拿到一个真实结果");
        assertEquals(1, cached.size());
    }

    @Test
    void clearingTheCacheForcesAFreshCall() {
        Fake model = new Fake(false);
        CachingChatModel cached = new CachingChatModel(model, Duration.ofMinutes(5));
        cached.chat(request("hi"));

        cached.clear();
        cached.chat(request("hi"));

        assertEquals(2, model.calls);
    }
}
