package net.hegq.mos4j.hook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钩子总线测试（T2.1）：waterfall / 短路 / 替换 / null 显式报错 / 未表态 = 放行。
 */
class HookBusTest {

    private static HookEvent event() {
        return new HookEvent("read", "{\"path\":\"a.txt\"}", "call_1");
    }

    @Test
    void noHookMeansEventUnchanged() {
        HookBus bus = new HookBus();
        HookEvent input = event();
        assertSame(input, bus.run(input), "未表态 = 放行（事件原样返回）");
    }

    @Test
    void hooksRunInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        HookBus bus = new HookBus();
        bus.register((ev, next) -> {
            order.add("first");
            return next.next(ev);
        });
        bus.register((ev, next) -> {
            order.add("second");
            return next.next(ev);
        });

        bus.run(event());

        assertEquals(List.of("first", "second"), order, "按注册顺序逐层委托");
    }

    @Test
    void hookCanShortCircuitTheChain() {
        List<String> order = new ArrayList<>();
        HookBus bus = new HookBus();
        bus.register((ev, next) -> {
            order.add("first");
            return ev; // 不调用 next = 短路
        });
        bus.register((ev, next) -> {
            order.add("second");
            return next.next(ev);
        });

        bus.run(event());

        assertEquals(List.of("first"), order, "短路：后面的钩子不执行");
    }

    @Test
    void hookCanReplaceTheEvent() {
        HookBus bus = new HookBus();
        bus.register((ev, next) -> new HookEvent("other", "{}", "call_2"));

        HookEvent result = bus.run(event());

        assertEquals("other", result.toolName(), "替换：钩子返回的事件成为最终载荷");
    }

    @Test
    void nilEventIsAnExplicitError() {
        HookBus bus = new HookBus();
        bus.register((ev, next) -> null);

        HookBus.NilHookEventException error =
                assertThrows(HookBus.NilHookEventException.class, () -> bus.run(event()));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("null"), error.getMessage());
    }

    @Test
    void hookRejectionPropagates() {
        HookBus bus = new HookBus();
        bus.register((ev, next) -> {
            throw new HookRejectedException("不许调这个工具");
        });

        HookRejectedException error = assertThrows(HookRejectedException.class, () -> bus.run(event()));
        assertEquals("不许调这个工具", error.getMessage());
    }

    @Test
    void brokenHookBecomesRejection() {
        HookBus bus = new HookBus();
        bus.register((ev, next) -> {
            throw new IllegalStateException("插件崩了");
        });

        HookRejectedException error = assertThrows(HookRejectedException.class, () -> bus.run(event()));
        assertTrue(error.getMessage().contains("插件崩了"), error.getMessage());
    }

    @Test
    void snapshotIsIndependentCopy() {
        HookBus bus = new HookBus();
        HookEvent input = event();
        HookEvent snapshot = bus.snapshot(input);

        assertEquals(input, snapshot);
        assertTrue(snapshot != input, "快照是独立副本");
    }
}
