package net.hegq.mos4j.hook;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双轨制的<b>结构性</b>测试（T2.2 / L3-9 / 不变式 I5）。
 *
 * <p>「钩子无法绕过守卫」必须是类型层面的事实，不是约定、不是 code review：
 * 守卫的 API 里不允许出现钩子的类型，反之亦然。
 * 一旦两边共用事件类型，钩子就能构造一个「守卫事件」去喂守卫，
 * 于是守卫判定的载荷与真正执行的载荷可能不是同一个——
 * 载荷冻结（机制 M1）当场失效，而守卫只跑一次、不复检，漏判无人知晓。
 *
 * <p>本测试用反射扫描公开签名：出现对方类型即失败。
 */
class DualTrackStructureTest {

    private static List<Class<?>> publicApiOf(Class<?> type) {
        List<Class<?>> types = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            types.add(method.getReturnType());
            for (Parameter parameter : method.getParameters()) {
                types.add(parameter.getType());
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            types.add(nested);
        }
        return types;
    }

    @Test
    void guardApiNeverMentionsHookTypes() {
        List<Class<?>> api = publicApiOf(Guard.class);
        api.addAll(publicApiOf(GuardEvent.class));
        api.addAll(publicApiOf(GuardDecision.class));
        api.addAll(publicApiOf(GuardBus.class));

        for (Class<?> type : api) {
            assertTrue(type != HookEvent.class && type != Hook.class && type != HookBus.class,
                    () -> "守卫 API 里出现了钩子类型: " + type.getName()
                            + "（钩子就能构造守卫事件，绕过守卫）");
        }
    }

    @Test
    void hookApiNeverMentionsGuardTypes() {
        List<Class<?>> api = publicApiOf(Hook.class);
        api.addAll(publicApiOf(HookEvent.class));
        api.addAll(publicApiOf(HookBus.class));

        for (Class<?> type : api) {
            assertTrue(type != GuardEvent.class && type != Guard.class && type != GuardBus.class,
                    () -> "钩子 API 里出现了守卫类型: " + type.getName()
                            + "（钩子就能构造守卫事件，绕过守卫）");
        }
    }

    @Test
    void guardAndHookEventsAreDistinctTypes() {
        assertTrue(!GuardEvent.class.equals(HookEvent.class), "守卫事件与钩子事件必须是两个类型");
    }
}
