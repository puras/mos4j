package net.hegq.mos4j.execpath;

import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行通道测试（T1.16）：回填序 = 调用序、错误即结果、逐条独立裁决、注册表缺失不崩。
 */
class ExecPathTest {

    /** 测试用工具（可声明「本次调用可并发」）。 */
    private static final class TestTool implements Tool {

        private final String name;
        private final boolean concurrent;
        private final FunctionTool.Executor executor;

        private TestTool(String name, boolean concurrent, FunctionTool.Executor executor) {
            this.name = name;
            this.concurrent = concurrent;
            this.executor = executor;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public Object execute(String argsJson) throws Exception {
            return executor.apply(argsJson);
        }

        @Override
        public boolean concurrencySafe(String argsJson) {
            return concurrent;
        }
    }

    private static ToolCall call(String id, String name, String args) {
        return new ToolCall(id, name, args);
    }

    @Test
    void resultsFollowCallOrderNotCompletionOrder() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("slow", true, args -> {
            Thread.sleep(120);
            return "slow";
        }));
        registry.register(new TestTool("fast", true, args -> "fast"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "slow", "{}"), call("c2", "fast", "{}")),
                new ExecPath.Options(4));

        assertEquals(2, outcomes.size());
        assertEquals("c1", outcomes.get(0).call().id(), "回填序 = 调用序");
        assertEquals("c2", outcomes.get(1).call().id());
        assertEquals("slow", outcomes.get(0).content());
        assertEquals("fast", outcomes.get(1).content());
    }

    @Test
    void unknownToolBecomesErrorOutcome() {
        ToolRegistry registry = new ToolRegistry();

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "nope", "{}")), ExecPath.Options.defaults());

        assertEquals(1, outcomes.size());
        assertTrue(outcomes.get(0).isError(), "错误进结果，不抛异常");
        assertTrue(outcomes.get(0).content().contains("nope"), outcomes.get(0).content());
    }

    @Test
    void toolFailureBecomesErrorOutcome() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("boom", false, args -> {
            throw new IllegalStateException("工具内部错误");
        }));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "boom", "{}")), ExecPath.Options.defaults());

        assertTrue(outcomes.get(0).isError());
        assertTrue(outcomes.get(0).content().contains("工具内部错误"), outcomes.get(0).content());
    }

    @Test
    void oneFailureDoesNotAffectOthers() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("bad", false, args -> {
            throw new IllegalStateException("失败");
        }));
        registry.register(new TestTool("good", false, args -> "成功"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "bad", "{}"), call("c2", "good", "{}")),
                ExecPath.Options.defaults());

        assertTrue(outcomes.get(0).isError());
        assertFalse(outcomes.get(1).isError(), "逐条独立裁决：一条失败不影响同批其他调用");
        assertEquals("成功", outcomes.get(1).content());
    }

    @Test
    void missingRegistryFailsWholeBatchWithoutCrash() {
        List<Outcome> outcomes = ExecPath.executeBatch(null,
                List.of(call("c1", "x", "{}"), call("c2", "y", "{}")),
                ExecPath.Options.defaults());

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).isError());
        assertTrue(outcomes.get(1).isError());
    }

    @Test
    void exclusiveToolIsNotConcurrent() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("serial", false, args -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            Thread.sleep(30);
            concurrent.decrementAndGet();
            return "ok";
        }));

        ExecPath.executeBatch(registry,
                List.of(call("c1", "serial", "{}"), call("c2", "serial", "{}")),
                new ExecPath.Options(4));

        assertEquals(1, maxConcurrent.get(), "未声明可并发 → 一律独占");
    }

    @Test
    void concurrencyClassifierFailureIsTreatedAsExclusive() {
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger concurrent = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "broken-classifier";
            }

            @Override
            public String description() {
                return "分类器抛异常";
            }

            @Override
            public Object execute(String argsJson) {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                concurrent.decrementAndGet();
                return "ok";
            }

            @Override
            public boolean concurrencySafe(String argsJson) {
                throw new IllegalStateException("分类器炸了");
            }
        });

        ExecPath.executeBatch(registry,
                List.of(call("c1", "broken-classifier", "{}"), call("c2", "broken-classifier", "{}")),
                new ExecPath.Options(4));

        assertEquals(1, maxConcurrent.get(), "分类器异常 → 按独占处理（fail-closed）");
    }

    // ---------------------------------------------------------------- T6.6

    @Test
    void concurrencyLimitIsEnforcedBySemaphore() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("parallel", true, args -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            Thread.sleep(50);
            concurrent.decrementAndGet();
            return "ok";
        }));

        List<ToolCall> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(call("c" + i, "parallel", "{}"));
        }
        ExecPath.executeBatch(registry, calls, new ExecPath.Options(2));

        assertTrue(maxConcurrent.get() <= 2,
                "Semaphore 有界并发不得超过上限，实际 " + maxConcurrent.get());
    }

    @Test
    void exclusiveBarrierIsolatesAdjacentConcurrentGroups() {
        // 并发组 → 独占（屏障）→ 并发组：屏障后的组必须等屏障完成才启动
        AtomicInteger exclusiveRuns = new AtomicInteger();
        AtomicInteger overlapWithExclusive = new AtomicInteger();
        AtomicInteger exclusiveInFlight = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("par", true, args -> {
            Thread.sleep(40);
            return "ok";
        }));
        registry.register(new TestTool("barrier", false, args -> {
            exclusiveRuns.incrementAndGet();
            exclusiveInFlight.incrementAndGet();
            Thread.sleep(40);
            // 独占执行期间不应有任何并发工具在跑（屏障语义：独占自成屏障）
            overlapWithExclusive.addAndGet(exclusiveInFlight.get() > 1 ? 1 : 0);
            exclusiveInFlight.decrementAndGet();
            return "ok";
        }));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "par", "{}"), call("c2", "par", "{}"),
                        call("c3", "barrier", "{}"),
                        call("c4", "par", "{}"), call("c5", "par", "{}")),
                new ExecPath.Options(4));

        assertEquals(5, outcomes.size());
        assertEquals(1, exclusiveRuns.get(), "独占工具两次不会并发（屏障内串行）");
    }

    @Test
    void toolThrowingError_SuchAsPanic_doesNotBreakBatch() {
        // 「panic 归独占」的执行侧对应：单条工具抛 RuntimeException（含 Error 类的近亲）
        // 不炸整批，转成错误结果；其他调用照常回填。
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("boom", true, args -> {
            throw new RuntimeException("模拟 panic");
        }));
        registry.register(new TestTool("fine", true, args -> "ok"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "boom", "{}"), call("c2", "fine", "{}")),
                new ExecPath.Options(4));

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).isError(), "单条异常 → 错误结果");
        assertFalse(outcomes.get(1).isError(), "同批其他调用不受影响");
        assertEquals("ok", outcomes.get(1).content());
    }

    @Test
    void virtualThreadsAreUsedForConcurrentGroup() {
        // 并发组跑在虚拟线程上（T6.6 硬化验证：不依赖平台线程池）
        AtomicInteger virtualCount = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("detect", true, args -> {
            if (Thread.currentThread().isVirtual()) {
                virtualCount.incrementAndGet();
            }
            return "ok";
        }));

        List<ToolCall> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(call("c" + i, "detect", "{}"));
        }
        ExecPath.executeBatch(registry, calls, new ExecPath.Options(4));

        assertEquals(4, virtualCount.get(), "并发组的每次执行都应在虚拟线程上");
    }

    // ---------------------------------------------------------------- T6.8

    @Test
    void registryListIsZeroReflectionAndStableOrder() {
        // T6.8：清单组装热路径不走反射（schema 注册期已缓存）；顺序 = 注册顺序且跨调用稳定
        ToolRegistry registry = new ToolRegistry();
        record Args(String q) {
        }
        registry.register(net.hegq.mos4j.tool.FunctionTool.of("first", "一", Args.class,
                args -> args));
        registry.register(net.hegq.mos4j.tool.FunctionTool.of("second", "二", null,
                args -> args));

        List<net.hegq.mos4j.llm.model.Tool> first = registry.list();
        List<net.hegq.mos4j.llm.model.Tool> second = registry.list();

        assertEquals(List.of("first", "second"),
                first.stream().map(net.hegq.mos4j.llm.model.Tool::name).toList(),
                "清单顺序 = 注册顺序");
        assertEquals(first, second, "跨调用顺序与内容稳定（每轮重组装的基线）");
        assertNotNull(first.get(0).parameters(), "schema 来自注册期缓存");
        assertEquals("q",
                ((java.util.Map<?, ?>) first.get(0).parameters().get("properties")).keySet().iterator().next(),
                "反射只发生在注册期，schema 内容正确");
    }

    // ---------------------------------------------------------------- T6.4

    @Test
    void cancelledToken_skipsPendingCalls_andKeepsOrder() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("gate", true, args -> {
            Thread.sleep(30);
            return "ok";
        }));

        net.hegq.mos4j.core.CancellationToken token = net.hegq.mos4j.core.CancellationToken.create();
        // c1 独占（自成屏障，内联执行）：执行时取消令牌。
        // 之后的并发组（c2/c3/c4）在 dispatch 的组间取消检查处整体跳过——无调度竞态。
        registry.register(new TestTool("hook", false, args -> {
            token.cancel();
            return "cancel-trigger";
        }));

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "hook", "{}"),
                        call("c2", "gate", "{}"), call("c3", "gate", "{}"),
                        call("c4", "gate", "{}")),
                new ExecPath.Options(4), null, token);

        assertEquals(4, outcomes.size(), "回填序不变，数量不变（无空洞）");
        assertEquals("c1", outcomes.get(0).call().id());
        assertFalse(outcomes.get(0).isError(), "取消前已开始的调用正常完成");
        for (int i = 1; i < 4; i++) {
            assertTrue(outcomes.get(i).isError(),
                    "取消后未执行的调用回填错误结果（保持配对合法）: c" + (i + 1));
            assertTrue(outcomes.get(i).content().contains("取消"), outcomes.get(i).content());
        }
    }

    @Test
    void alreadyCancelledToken_executesNothing() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger executed = new AtomicInteger();
        registry.register(new TestTool("x", true, args -> {
            executed.incrementAndGet();
            return "ok";
        }));

        net.hegq.mos4j.core.CancellationToken token = net.hegq.mos4j.core.CancellationToken.create();
        token.cancel();

        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "x", "{}"), call("c2", "x", "{}")),
                new ExecPath.Options(4), null, token);

        assertEquals(0, executed.get(), "已取消的令牌：一条都不执行");
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).isError() && outcomes.get(1).isError());
    }

    @Test
    void nullToken_isZeroOverhead() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("x", true, args -> "ok"));

        // null 令牌 = 不可取消：行为与旧签名完全一致
        List<Outcome> outcomes = ExecPath.executeBatch(registry,
                List.of(call("c1", "x", "{}")), new ExecPath.Options(4));

        assertFalse(outcomes.get(0).isError());
        assertEquals("ok", outcomes.get(0).content());
    }
}
