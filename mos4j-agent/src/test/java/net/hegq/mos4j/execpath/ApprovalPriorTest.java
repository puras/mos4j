package net.hegq.mos4j.execpath;

import net.hegq.mos4j.core.ApprovalAudit;
import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalPrior;
import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批先验在执行通道里的行为（Go 差异 #3）。
 *
 * <p>对位 Go 的 {@code agent/approval/approval.go}（{@code Service.Decide} 的先验分支）
 * 与 {@code agent/execpath/execpath.go}（ask 分支）。
 *
 * <p><b>三条纪律</b>：
 * <ul>
 *   <li><b>命中就不再问人</b>：人不用被问第二遍；</li>
 *   <li><b>未装配通道仍然 fail-closed</b>：没有问人的能力，就不能说「上次批过」算数；</li>
 *   <li><b>审计照样成对</b>：复用也记一对 asked + decided（新 id）——每个决定都有配对的 asked。</li>
 * </ul>
 */
class ApprovalPriorTest {

    /** 记录审计事件的出口。 */
    private static final class RecordingAudit implements ApprovalAudit {

        private final List<ApprovalRequest> asked = new ArrayList<>();
        private final List<ApprovalOutcome> decided = new ArrayList<>();

        @Override
        public void asked(ApprovalRequest request) {
            asked.add(request);
        }

        @Override
        public void decided(ApprovalRequest request, ApprovalOutcome outcome) {
            decided.add(outcome);
        }
    }

    private static ToolCall call(String id, String name, String args) {
        return new ToolCall(id, name, args);
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("write", "写", null, args -> "written"));
        return registry;
    }

    private static GuardBus askingGuard() {
        return new GuardBus().register(event -> GuardDecision.ask("要写文件"));
    }

    @Test
    void priorHitDoesNotAskTheHumanAgain() {
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel channel = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        RecordingAudit audit = new RecordingAudit();
        ApprovalPrior prior = (turn, toolName, callId) ->
                Optional.of(new ApprovalPrior.Decision(ApprovalOutcome.APPROVED, "ap_old"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry(),
                List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, channel, audit, null));

        assertEquals(0, asked.get(), "命中先验 → 不再问人");
        assertFalse(outcomes.get(0).isError(), "被复用的批准仍然放行");
        assertEquals("written", outcomes.get(0).content());
    }

    @Test
    void priorMissAsksTheHumanAsUsual() {
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel channel = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        RecordingAudit audit = new RecordingAudit();
        ApprovalPrior prior = (turn, toolName, callId) -> Optional.empty();

        List<Outcome> outcomes = ExecPath.executeBatch(registry(),
                List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, channel, audit, null));

        assertEquals(1, asked.get(), "没命中 → 照常问人");
        assertFalse(outcomes.get(0).isError());
    }

    @Test
    void priorHitStillWritesAPairedAudit() {
        ApprovalChannel channel = request -> ApprovalResult.approved();
        RecordingAudit audit = new RecordingAudit();
        ApprovalPrior prior = (turn, toolName, callId) ->
                Optional.of(new ApprovalPrior.Decision(ApprovalOutcome.APPROVED, "ap_old"));

        ExecPath.executeBatch(registry(), List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, channel, audit, null));

        assertEquals(1, audit.asked.size(), "每个决定都有配对的 asked");
        assertEquals(1, audit.decided.size());
        assertEquals(ApprovalOutcome.APPROVED, audit.decided.get(0));
        assertNotEquals("ap_old", audit.asked.get(0).id(), "复用也签发新 id（每次审批身份独立）");
        assertTrue(audit.asked.get(0).reason().contains("ap_old"),
                "「这次没重新问人」的线索要留在审计里：" + audit.asked.get(0).reason());
    }

    @Test
    void withoutAnApprovalChannelThePriorCannotRescueTheCall() {
        RecordingAudit audit = new RecordingAudit();
        ApprovalPrior prior = (turn, toolName, callId) ->
                Optional.of(new ApprovalPrior.Decision(ApprovalOutcome.APPROVED, "ap_old"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry(),
                List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, null, audit, null));

        assertTrue(outcomes.get(0).isError(), "没有问人的能力 → fail-closed");
        assertTrue(outcomes.get(0).content().contains("未装配审批通道"), outcomes.get(0).content());
    }

    @Test
    void aReusedRejectionStillRejects() {
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel channel = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        RecordingAudit audit = new RecordingAudit();
        ApprovalPrior prior = (turn, toolName, callId) ->
                Optional.of(new ApprovalPrior.Decision(ApprovalOutcome.REJECTED, "ap_old"));

        List<Outcome> outcomes = ExecPath.executeBatch(registry(),
                List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, channel, audit, null));

        assertEquals(0, asked.get(), "复用拒绝也不问人");
        assertTrue(outcomes.get(0).isError(), "被复用的拒绝仍然是拒绝");
        assertTrue(outcomes.get(0).content().contains("不要尝试用其他命令"),
                "「不许绕」的半句照旧：" + outcomes.get(0).content());
        assertEquals(ApprovalOutcome.REJECTED, audit.decided.get(0));
    }

    @Test
    void aNonReusablePriorIsIgnored() {
        AtomicInteger asked = new AtomicInteger();
        ApprovalChannel channel = request -> {
            asked.incrementAndGet();
            return ApprovalResult.approved();
        };
        ApprovalPrior prior = (turn, toolName, callId) ->
                Optional.of(new ApprovalPrior.Decision(ApprovalOutcome.UNAVAILABLE, "ap_old"));

        ExecPath.executeBatch(registry(), List.of(call("c1", "write", "{}")),
                new ExecPath.Options(1, 7, prior),
                new ExecPath.Security(askingGuard(), null, channel, new RecordingAudit(), null));

        assertEquals(1, asked.get(), "「无人应答」不是决定 → 照常问人");
    }
}
