package net.hegq.mos4j.policy;

import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 名字级策略测试（T2.4）：Deny 优先、Allow 未配置 = 不限制、Allow 空集 = 全禁、
 * 通配整串锚定、可见性与判定同一份名单。
 */
class ToolNamePolicyTest {

    private static GuardEvent event(String name) {
        return new GuardEvent(name, "{}", "call_1");
    }

    @Test
    void unrestrictedAllowsEverything() {
        ToolNamePolicy policy = ToolNamePolicy.unrestricted();
        assertTrue(policy.allows("shell"));
        assertTrue(policy.allows("read"));
        assertEquals(GuardDecision.Verdict.ALLOW, policy.check(event("shell")).verdict());
    }

    @Test
    void denyWinsOverAllow() {
        ToolNamePolicy policy = ToolNamePolicy.of(List.of("shell", "read"), List.of("shell"));
        assertFalse(policy.allows("shell"), "Deny 优先");
        assertTrue(policy.allows("read"));
    }

    @Test
    void configuredEmptyAllowDeniesEverything() {
        ToolNamePolicy policy = ToolNamePolicy.of(List.of(), List.of());
        assertFalse(policy.allows("shell"), "配了空集 = 全禁（与「未配置」不同）");
        assertEquals(GuardDecision.Verdict.DENY, policy.check(event("shell")).verdict());
    }

    @Test
    void unconfiguredAllowDoesNotRestrict() {
        ToolNamePolicy policy = ToolNamePolicy.of(null, List.of("shell"));
        assertTrue(policy.allows("read"), "Allow 未配置 = 不限制");
        assertFalse(policy.allows("shell"), "Deny 仍然生效");
    }

    @Test
    void wildcardIsAnchoredToTheWholeName() {
        ToolNamePolicy policy = ToolNamePolicy.of(List.of("fs.*"), List.of());
        assertTrue(policy.allows("fs.read"), "前缀通配命中");
        assertFalse(policy.allows("x.fs.read"), "整串锚定：不命中中间带前缀的名字");
        assertFalse(policy.allows("fs"), "整串锚定：'fs.*' 不等于 'fs'");
    }

    @Test
    void starMatchesEverything() {
        ToolNamePolicy policy = ToolNamePolicy.of(List.of("*"), List.of());
        assertTrue(policy.allows("anything"));
    }

    @Test
    void visibleUsesTheSameListAsCheck() {
        ToolNamePolicy policy = ToolNamePolicy.of(List.of("fs.*", "read"), List.of("fs.write"));
        List<String> all = List.of("fs.read", "fs.write", "read", "shell");

        List<String> visible = policy.visible(all);

        assertEquals(List.of("fs.read", "read"), visible);
        for (String name : all) {
            assertEquals(policy.allows(name), visible.contains(name),
                    () -> "可见性与判定不一致: " + name);
        }
    }

    @Test
    void withDenyDoesNotMutateOriginal() {
        ToolNamePolicy base = ToolNamePolicy.of(List.of("*"), List.of());
        ToolNamePolicy hardened = base.withDeny(List.of("shell"));

        assertTrue(base.allows("shell"), "原策略不变");
        assertFalse(hardened.allows("shell"));
    }
}
