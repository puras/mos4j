package net.hegq.mos4j.policy;

import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数级策略测试（T2.5）：三档裁决、解析失败 = 拒绝、只读载荷快照。
 */
class ParamPolicyTest {

    private static GuardEvent event(String tool, String args) {
        return new GuardEvent(tool, args, "call_1");
    }

    @Test
    void denyVerdict() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.DENY)
                .build();

        GuardDecision decision = policy.check(event("shell", "{\"cmd\":\"rm -rf /\"}"));
        assertEquals(GuardDecision.Verdict.DENY, decision.verdict());
    }

    @Test
    void askVerdict() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.ASK)
                .build();

        assertEquals(GuardDecision.Verdict.ASK, policy.check(event("shell", "{\"cmd\":\"ls\"}")).verdict());
    }

    @Test
    void allowVerdict() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.ALLOW)
                .build();

        assertEquals(GuardDecision.Verdict.ALLOW, policy.check(event("shell", "{\"cmd\":\"ls\"}")).verdict());
    }

    @Test
    void unmentionedParametersAreAllowed() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.DENY)
                .build();

        assertEquals(GuardDecision.Verdict.ALLOW, policy.check(event("shell", "{\"cwd\":\"/tmp\"}")).verdict(),
                "没写规则的参数 = 允许");
    }

    @Test
    void strictestVerdictWins() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.ALLOW)
                .rule("shell", "path", ParamPolicy.Verdict.DENY)
                .build();

        GuardDecision decision = policy.check(event("shell", "{\"cmd\":\"ls\",\"path\":\"/etc\"}"));
        assertEquals(GuardDecision.Verdict.DENY, decision.verdict(), "取最严的一档");
    }

    @Test
    void brokenJsonIsDenied() {
        ParamPolicy policy = ParamPolicy.builder()
                .rule("shell", "cmd", ParamPolicy.Verdict.ALLOW)
                .build();

        GuardDecision decision = policy.check(event("shell", "{不是 JSON"));
        assertEquals(GuardDecision.Verdict.DENY, decision.verdict(), "参数解析失败 = 拒绝");
        assertTrue(decision.reason().contains("参数解析失败"), decision.reason());
    }

    @Test
    void snapshotIsReadOnly() throws Exception {
        ParamPolicy policy = ParamPolicy.empty();
        Map<String, Object> snapshot = policy.snapshot("{\"path\":\"a.txt\",\"n\":1,\"nested\":{\"k\":\"v\"}}");

        assertEquals("a.txt", snapshot.get("path"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", "y"),
                "载荷快照只读：策略不该能改参数");
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) snapshot.get("nested")).put("k", "changed"),
                "嵌套载荷同样只读");
    }

    @Test
    void wholeToolRuleAppliesToEveryParameter() {
        ParamPolicy policy = ParamPolicy.builder()
                .tool("shell", ParamPolicy.Verdict.ASK)
                .build();

        assertEquals(GuardDecision.Verdict.ASK, policy.check(event("shell", "{\"cmd\":\"ls\"}")).verdict());
        assertEquals(GuardDecision.Verdict.ASK, policy.check(event("shell", "{\"cwd\":\"/tmp\"}")).verdict());
    }

    @Test
    void specificRuleOverridesTheToolDefaultForThatParameter() {
        ParamPolicy policy = ParamPolicy.builder()
                .tool("shell", ParamPolicy.Verdict.DENY)
                .rule("shell", "cmd", ParamPolicy.Verdict.ALLOW)
                .build();

        assertEquals(GuardDecision.Verdict.ALLOW,
                policy.check(event("shell", "{\"cmd\":\"ls\"}")).verdict(),
                "具体参数规则优先于工具默认规则");
    }

    @Test
    void strictestVerdictWinsAcrossDefaultAndSpecificRules() {
        ParamPolicy policy = ParamPolicy.builder()
                .tool("shell", ParamPolicy.Verdict.DENY)
                .rule("shell", "cmd", ParamPolicy.Verdict.ALLOW)
                .build();

        GuardDecision decision = policy.check(event("shell", "{\"cmd\":\"ls\",\"path\":\"/etc\"}"));

        assertEquals(GuardDecision.Verdict.DENY, decision.verdict(),
                "默认规则仍然作用于其他参数，整体取最严");
    }
}
