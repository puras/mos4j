package net.hegq.mos4j.model;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型提供方注册表的守护测试（对齐 Go 参考实现 {@code llm/registry.go}）。
 *
 * <p>守护三条：名字 → 工厂的映射、未注册的名字 fail-closed、名字列表有序。
 */
class ModelRegistryTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 只会说一句话的模型。 */
    private static class Fake implements ChatModel {

        final String name;

        Fake(String name) {
            this.name = name;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return new Response(Message.assistant(name), NO_USAGE, Response.FINISH_END_TURN);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    @Test
    void aRegisteredProviderIsCreatedByName() throws Exception {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", config -> new Fake("openai:" + config.get("model")));

        ChatModel model = registry.create("openai", Map.of("model", "gpt-x"));

        assertEquals("openai:gpt-x", model.chat(Request.ofUser("hi")).message().content());
    }

    @Test
    void anUnknownProviderFailsClosed() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", config -> new Fake("openai"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.create("anthropic", Map.of()));

        assertTrue(error.getMessage().contains("anthropic"), "报错要说清是哪个名字");
        assertTrue(error.getMessage().contains("openai"), "报错要列出已注册的名字");
    }

    @Test
    void theNamesAreSorted() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", config -> new Fake("openai"));
        registry.register("anthropic", config -> new Fake("anthropic"));
        registry.register("gemini", config -> new Fake("gemini"));

        assertEquals(List.of("anthropic", "gemini", "openai"), registry.names());
        assertEquals(3, registry.size());
    }

    @Test
    void anEmptyNameIsRejected() {
        ModelRegistry registry = new ModelRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register("", config -> new Fake("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, config -> new Fake("x")));
        assertThrows(IllegalArgumentException.class, () -> registry.register("openai", null));
    }

    @Test
    void aFactoryFailureIsNotSwallowed() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("broken", config -> {
            throw new IllegalStateException("没有 api_key");
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.create("broken", Map.of()));

        assertEquals("没有 api_key", error.getMessage());
    }

    @Test
    void theRegistryIsUsableFromSeveralThreads() throws Exception {
        ModelRegistry registry = new ModelRegistry();
        AtomicInteger created = new AtomicInteger();
        registry.register("openai", config -> {
            created.incrementAndGet();
            return new Fake("openai");
        });
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        registry.create("openai", Map.of());
                    } catch (Exception e) {
                        // 记下来：测试结束时要能看到
                        e.printStackTrace();
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
        assertEquals(threads, created.get());
        assertTrue(registry.contains("openai"));
        assertFalse(registry.contains("nope"));
    }

    @Test
    void registeringTheSameNameTwiceOverridesTheFactory() throws Exception {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", config -> new Fake("第一个"));
        registry.register("openai", config -> new Fake("第二个"));

        ChatModel model = registry.create("openai", Map.of());

        assertEquals("第二个", model.chat(Request.ofUser("hi")).message().content());
        assertEquals(1, registry.size());
    }

    @Test
    void aNullConfigIsTreatedAsEmpty() throws Exception {
        ModelRegistry registry = new ModelRegistry();
        List<String> seen = new ArrayList<>();
        registry.register("openai", config -> {
            seen.add(String.valueOf(config));
            return new Fake("openai");
        });

        registry.create("openai", null);

        assertEquals("{}", seen.get(0));
    }
}
