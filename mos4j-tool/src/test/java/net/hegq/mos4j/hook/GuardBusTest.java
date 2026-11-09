package net.hegq.mos4j.hook;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫总线测试（T2.2）：串行、单调、首个拒绝即短路、判定异常 = 拒绝。
 */
class GuardBusTest {

    private static GuardEvent event() {
        return new GuardEvent("shell", "{\"cmd\":\"rm -rf /\"}", "call_1");
    }

    @Test
    void noGuardMeansAllow() {
        GuardBus bus = new GuardBus();
        assertEquals(GuardDecision.Verdict.ALLOW, bus.check(event()).verdict());
    }

    @Test
    void firstDenyWinsAndShortCircuits() {
        AtomicInteger secondGuardCalls = new AtomicInteger();
        GuardBus bus = new GuardBus();
        bus.register(ev -> GuardDecision.deny("危险命令"));
        bus.register(ev -> {
            secondGuardCalls.incrementAndGet();
            return GuardDecision.allow();
        });

        GuardDecision decision = bus.check(event());

        assertEquals(GuardDecision.Verdict.DENY, decision.verdict());
        assertEquals("危险命令", decision.reason());
        assertEquals(0, secondGuardCalls.get(), "首个拒绝即短路，后面的守卫不执行");
    }

    @Test
    void askIsNotDowngradedByLaterAllow() {
        GuardBus bus = new GuardBus();
        bus.register(ev -> GuardDecision.ask("需要人确认"));
        bus.register(ev -> GuardDecision.allow());

        assertEquals(GuardDecision.Verdict.ASK, bus.check(event()).verdict(),
                "单调：一次「要问人」不能被另一个守卫的「随便」盖掉");
    }

    @Test
    void laterDenyOverridesEarlierAsk() {
        GuardBus bus = new GuardBus();
        bus.register(ev -> GuardDecision.ask("需要人确认"));
        bus.register(ev -> GuardDecision.deny("禁止"));

        GuardDecision decision = bus.check(event());
        assertEquals(GuardDecision.Verdict.DENY, decision.verdict(), "更严的判定赢");
        assertEquals("禁止", decision.reason());
    }

    @Test
    void cancelIsTerminal() {
        GuardBus bus = new GuardBus();
        bus.register(ev -> GuardDecision.cancel("用户叫停"));

        assertEquals(GuardDecision.Verdict.CANCEL, bus.check(event()).verdict());
    }

    @Test
    void guardExceptionIsDenied() {
        GuardBus bus = new GuardBus();
        bus.register(ev -> {
            throw new IllegalStateException("守卫炸了");
        });

        GuardDecision decision = bus.check(event());

        assertEquals(GuardDecision.Verdict.DENY, decision.verdict(), "守卫异常 = 拒绝（fail-closed）");
        assertTrue(decision.reason().contains("守卫炸了"), decision.reason());
    }

    @Test
    void nullDecisionIsDenied() {
        GuardBus bus = new GuardBus();
        bus.register(ev -> null);

        assertEquals(GuardDecision.Verdict.DENY, bus.check(event()).verdict(),
                "守卫没给出判定 = 不放行（fail-closed）");
    }
}
