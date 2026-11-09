package net.hegq.mos4j.execpath;

import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.core.Texts;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.HookEvent;
import net.hegq.mos4j.hook.HookRejectedException;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行通道的安全纵深测试（J2 · L3-1 … L3-9）。
 *
 * <p>覆盖：守卫拒绝、钩子拒绝、载荷冻结（三个字段各一条）、ask 无审批通道、
 * 审批人工拒绝（含「不许绕」）、审批无人应答、错误只可加重、守卫只跑一次。
 */
class ExecPathSecurityTest {

    private static class TestTool implements Tool {

        private final String name;

        private TestTool(String name) {
            this.name = name;
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
        public Object execute(String argsJson) {
            return "ok";
        }
    }

    private static ToolRegistry registryWith(String name) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool(name));
        return registry;
    }

    private static ToolCall call(String id, String name, String args) {
        return new ToolCall(id, name, args);
    }

    private static List<Outcome> run(ToolRegistry registry, ToolCall call, ExecPath.Security security) {
        return ExecPath.executeBatch(registry, List.of(call), ExecPath.Options.defaults(), security);
    }

    // ---------- L3-1 守卫拒绝 ----------

    @Test
    void l3_1_guardDeniesWithFixedWording() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.deny("命令里有 rm -rf"));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertEquals(Texts.guardRejected("命令里有 rm -rf"), outcome.content());
    }

    // ---------- L3-2 钩子拒绝 ----------

    @Test
    void l3_2_hookRejectsWithFixedWording() {
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> {
            throw new HookRejectedException("这个工具本轮不许用");
        });

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, hooks, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertEquals(Texts.hookRejected("这个工具本轮不许用"), outcome.content());
    }

    // ---------- L3-3 载荷冻结（三个字段各一条） ----------

    @Test
    void l3_3_rewritingNameIsFrozenViolation() {
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> new HookEvent("other_tool", ev.args(), ev.callId()));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, hooks, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.content().contains("载荷冻结"), outcome.content());
        assertEquals(Texts.PAYLOAD_FROZEN, outcome.content());
    }

    @Test
    void l3_3_rewritingArgsIsFrozenViolation() {
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> new HookEvent(ev.toolName(), "{\"cmd\":\"evil\"}", ev.callId()));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, hooks, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertEquals(Texts.PAYLOAD_FROZEN, outcome.content());
    }

    @Test
    void l3_3_rewritingCallIdIsFrozenViolation() {
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> new HookEvent(ev.toolName(), ev.args(), "other_id"));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, hooks, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertEquals(Texts.PAYLOAD_FROZEN, outcome.content());
    }

    @Test
    void l3_3_frozenToolIsNotExecuted() {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "shell";
            }

            @Override
            public String description() {
                return "shell";
            }

            @Override
            public Object execute(String argsJson) {
                executions.incrementAndGet();
                return "ok";
            }
        });
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> new HookEvent(ev.toolName(), "{\"cmd\":\"evil\"}", ev.callId()));

        run(registry, call("c1", "shell", "{}"), new ExecPath.Security(null, hooks, null, null, null));

        assertEquals(0, executions.get(), "载荷冻结违约的调用不执行");
    }

    // ---------- L3-4 ask 但没有审批通道 ----------

    @Test
    void l3_4_askWithoutChannelIsDenied() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.content().contains("未装配审批通道（fail-closed）"), outcome.content());
        assertEquals(Texts.askWithoutChannel("要人确认"), outcome.content());
    }

    // ---------- L3-5 审批人工拒绝（含「不许绕」） ----------

    @Test
    void l3_5_humanRejectionKeepsTheNoWorkaroundSentence() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> ApprovalResult.rejected("这会删掉你的实验目录");

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, null, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.content().contains("不要尝试用其他命令或方式达成同一目的"),
                "不许绕半句不能丢: " + outcome.content());
        assertTrue(outcome.content().contains("这会删掉你的实验目录"), outcome.content());
        assertEquals(Texts.approvalRejected("这会删掉你的实验目录"), outcome.content());
    }

    // ---------- L3-6 审批无人应答 ----------

    @Test
    void l3_6_noAnswerIsFailClosed() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> ApprovalResult.unavailable("没人应答");

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, null, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.content().contains("人工确认无人应答（fail-closed）"), outcome.content());
    }

    // ---------- 批准只对本次调用生效 ----------

    @Test
    void approvalAppliesToThisCallOnly() {
        AtomicInteger asked = new AtomicInteger();
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };

        List<Outcome> outcomes = ExecPath.executeBatch(registryWith("shell"),
                List.of(call("c1", "shell", "{}"), call("c2", "shell", "{}")),
                ExecPath.Options.defaults(),
                new ExecPath.Security(guards, null, approvals, null, null));

        assertEquals(2, asked.get(), "每次调用都重新问（批准不缓存）");
        assertFalse(outcomes.get(0).isError());
        assertFalse(outcomes.get(1).isError());
    }

    // ---------- L3-8 错误只可加重不可减轻 ----------

    @Test
    void l3_8_postHookCannotWashAFailureIntoSuccess() {
        PostHook post = (event, content, isError) -> "假装没问题";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("boom") {
            @Override
            public Object execute(String argsJson) {
                throw new IllegalStateException("工具内部错误");
            }
        });

        Outcome outcome = run(registry, call("c1", "boom", "{}"),
                new ExecPath.Security(null, null, null, null, post)).get(0);

        assertTrue(outcome.isError(), "失败不能被洗成成功");
        assertTrue(outcome.content().contains("工具内部错误"), outcome.content());
    }

    @Test
    void l3_8_postHookCanWorsenASuccess() {
        PostHook post = (event, content, isError) -> "结果被污染了";

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, null, null, null, post)).get(0);

        assertTrue(outcome.isError(), "成功可以被改失败（加重）");
        assertEquals("结果被污染了", outcome.content());
    }

    // ---------- L3-9 双轨制：守卫对同一次调用只跑一次 ----------

    @Test
    void l3_9_guardRunsExactlyOncePerCall() {
        AtomicInteger guardCalls = new AtomicInteger();
        GuardBus guards = new GuardBus();
        guards.register(ev -> {
            guardCalls.incrementAndGet();
            return GuardDecision.allow();
        });

        ExecPath.executeBatch(registryWith("shell"),
                List.of(call("c1", "shell", "{}"), call("c2", "shell", "{}")),
                ExecPath.Options.defaults(),
                new ExecPath.Security(guards, null, null, null, null));

        assertEquals(2, guardCalls.get(), "每次调用判定一次（不因并发分组重复判定）");
    }

    @Test
    void l3_9_guardSeesTheFinalPayload() {
        AtomicInteger guardCalls = new AtomicInteger();
        GuardBus guards = new GuardBus();
        guards.register(ev -> {
            guardCalls.incrementAndGet();
            return GuardDecision.allow();
        });
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> next.next(ev));

        ExecPath.executeBatch(registryWith("shell"),
                List.of(call("c1", "shell", "{\"cmd\":\"ls\"}")),
                ExecPath.Options.defaults(),
                new ExecPath.Security(guards, hooks, null, null, null));

        assertEquals(1, guardCalls.get());
    }

    // ---------- 守卫取消 → 终止本轮 ----------

    @Test
    void cancelVerdictTerminatesTheRound() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.cancel("用户叫停"));

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.terminate(), "取消 = 终止本轮");
    }

    // ---------- 零开销降级 ----------

    @Test
    void noSecurityMeansSameBehaviorAsBefore() {
        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"), ExecPath.Security.none()).get(0);
        assertFalse(outcome.isError());
        assertEquals("ok", outcome.content());
    }

    // ---------- 编排顺序：守卫先于钩子 ----------

    @Test
    void guardRunsBeforeHooksAndDeniedCallNeverReachesHooks() {
        AtomicInteger hookCalls = new AtomicInteger();
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.deny("先拒"));
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> {
            hookCalls.incrementAndGet();
            return next.next(ev);
        });

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, hooks, null, null, null)).get(0);

        assertTrue(outcome.isError());
        assertEquals(0, hookCalls.get(), "已被守卫拒掉的调用，钩子不该跑");
    }

    @Test
    void hookChainRunsBeforeTheFreezeCheck() {
        AtomicInteger hookCalls = new AtomicInteger();
        HookBus hooks = new HookBus();
        hooks.register((ev, next) -> {
            hookCalls.incrementAndGet();
            return new HookEvent(ev.toolName(), "{\"cmd\":\"evil\"}", ev.callId());
        });

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(null, hooks, null, null, null)).get(0);

        assertEquals(1, hookCalls.get());
        assertEquals(Texts.PAYLOAD_FROZEN, outcome.content());
    }

    // ---------- 零开销降级（T2.10） ----------

    @Test
    void nullBusesBehaveLikeNotInstalled() {
        ToolRegistry registry = registryWith("shell");
        List<Outcome> withNone = ExecPath.executeBatch(registry,
                List.of(call("c1", "shell", "{}")), ExecPath.Options.defaults(), ExecPath.Security.none());
        List<Outcome> withNulls = ExecPath.executeBatch(registry,
                List.of(call("c1", "shell", "{}")), ExecPath.Options.defaults(),
                new ExecPath.Security(null, null, null, null, null));

        assertEquals(withNone.get(0).content(), withNulls.get(0).content(),
                "null 总线 ≡ 未注入");
        assertEquals(withNone.get(0).isError(), withNulls.get(0).isError());
    }

    @Test
    void emptyBusesBehaveLikeNotInstalled() {
        ToolRegistry registry = registryWith("shell");
        List<Outcome> withNone = ExecPath.executeBatch(registry,
                List.of(call("c1", "shell", "{}")), ExecPath.Options.defaults(), ExecPath.Security.none());
        List<Outcome> withEmpty = ExecPath.executeBatch(registry,
                List.of(call("c1", "shell", "{}")), ExecPath.Options.defaults(),
                new ExecPath.Security(new GuardBus(), new HookBus(), null, null, null));

        assertEquals(withNone.get(0).content(), withEmpty.get(0).content());
        assertFalse(withEmpty.get(0).isError());
    }

    // ---------- 审批通道异常：归一为 unavailable，不伪装成人工拒绝 ----------

    @Test
    void throwingApprovalChannelBecomesUnavailableAndStillWritesTheDecision() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> {
            throw new IllegalStateException("审批面板挂了");
        };
        List<net.hegq.mos4j.core.ApprovalOutcome> outcomes = new java.util.ArrayList<>();
        net.hegq.mos4j.core.ApprovalAudit audit = new net.hegq.mos4j.core.ApprovalAudit() {
            @Override
            public void asked(ApprovalRequest request) {
            }

            @Override
            public void decided(ApprovalRequest request, net.hegq.mos4j.core.ApprovalOutcome outcome) {
                outcomes.add(outcome);
            }
        };

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, audit, null)).get(0);

        assertTrue(outcome.isError());
        assertTrue(outcome.content().contains("人工确认无人应答（fail-closed）"), outcome.content());
        assertEquals(List.of(net.hegq.mos4j.core.ApprovalOutcome.UNAVAILABLE), outcomes,
                "通道异常必须落成 unavailable，不是 rejected");
    }

    // ---------- 审计写失败即中止 ----------

    @Test
    void auditWriteFailureAbortsTheCall() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> ApprovalResult.approved();
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String name() {
                return "shell";
            }

            @Override
            public String description() {
                return "shell";
            }

            @Override
            public Object execute(String argsJson) {
                executions.incrementAndGet();
                return "ok";
            }
        });
        net.hegq.mos4j.core.ApprovalAudit audit = new net.hegq.mos4j.core.ApprovalAudit() {
            @Override
            public void asked(ApprovalRequest request) {
                throw new java.io.UncheckedIOException(
                        new java.io.IOException("磁盘满了"));
            }

            @Override
            public void decided(ApprovalRequest request, net.hegq.mos4j.core.ApprovalOutcome outcome) {
            }
        };

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> run(registry, call("c1", "shell", "{}"),
                        new ExecPath.Security(guards, null, approvals, audit, null)));

        assertTrue(error.getMessage().contains("磁盘满了"), error.getMessage());
        assertEquals(0, executions.get(), "审计写不进去就不许执行工具");
    }

    // ---------- 审批请求内容 ----------

    @Test
    void approvalRequestCarriesToolAndCallId() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel approvals = request -> {
            asked.incrementAndGet();
            assertEquals("shell", request.toolName());
            assertEquals("c1", request.callId());
            assertEquals("要人确认", request.reason());
            assertTrue(request.id() != null && !request.id().isEmpty(), "审批 id 用于审计配对");
            return ApprovalResult.approved();
        };

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, null, null)).get(0);

        assertEquals(1, asked.get());
        assertFalse(outcome.isError());
    }

    @Test
    void approvedCallActuallyExecutes() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        ApprovalChannel approvals = request -> ApprovalResult.approved();

        Outcome outcome = run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, null, null)).get(0);

        assertFalse(outcome.isError());
        assertEquals("ok", outcome.content());
    }

    @Test
    void approvalRequestIsRecordedOnlyWhenAuditIsWired() {
        GuardBus guards = new GuardBus();
        guards.register(ev -> GuardDecision.ask("要人确认"));
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel approvals = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        ApprovalRequest[] seen = new ApprovalRequest[1];
        net.hegq.mos4j.core.ApprovalAudit audit = new net.hegq.mos4j.core.ApprovalAudit() {
            @Override
            public void asked(ApprovalRequest request) {
                seen[0] = request;
            }

            @Override
            public void decided(ApprovalRequest request, net.hegq.mos4j.core.ApprovalOutcome outcome) {
                // 记录结局即可
            }
        };

        run(registryWith("shell"), call("c1", "shell", "{}"),
                new ExecPath.Security(guards, null, approvals, audit, null));

        assertEquals(1, asked.get());
        assertTrue(seen[0] != null, "审计出口记录了 asked");
    }
}
