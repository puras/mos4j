package net.hegq.mos4j.session;

import net.hegq.mos4j.core.ApprovalOutcome;
import net.hegq.mos4j.core.ApprovalRequest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批审计落日志（J2 · 机制 M10）。
 *
 * <p>审批事件<b>全量落日志</b>、<b>不进投影</b>；asked / decided 必须成对、
 * 用同一个 id 配对；{@code reason} 只属于 asked（契约 API.md）。
 */
class SessionApprovalAuditTest {

    private static ApprovalRequest request() {
        return new ApprovalRequest("ap_1", "shell", "call_1", "要人确认");
    }

    @Test
    void askedAndDecidedAreBothWrittenAndPairedById() throws IOException {
        Session session = new Session("s1", null);
        SessionApprovalAudit audit = new SessionApprovalAudit(session, 3);

        audit.asked(request());
        audit.decided(request(), ApprovalOutcome.APPROVED);

        List<Event> events = session.events();
        assertEquals(2, events.size());
        assertEquals(EventKind.APPROVAL_ASKED, events.get(0).kind());
        assertEquals(EventKind.APPROVAL_DECIDED, events.get(1).kind());
        assertEquals("ap_1", events.get(0).approval().id());
        assertEquals("ap_1", events.get(1).approval().id());
        assertEquals(3, events.get(0).turn());
        assertEquals(3, events.get(1).turn());
        assertEquals("approved", events.get(1).approval().outcome());
    }

    @Test
    void reasonOnlyAppearsOnTheAskedEvent() throws IOException {
        Session session = new Session("s1", null);
        SessionApprovalAudit audit = new SessionApprovalAudit(session, 1);

        audit.asked(request());
        audit.decided(request(), ApprovalOutcome.REJECTED);

        assertEquals("要人确认", session.events().get(0).approval().reason());
        assertNull(session.events().get(1).approval().reason(),
                "decided 不重复携带 reason（契约 API.md）");
    }

    @Test
    void approvalEventsDoNotEnterTheProjection() throws IOException {
        Session session = new Session("s1", null);
        SessionApprovalAudit audit = new SessionApprovalAudit(session, 1);

        audit.asked(request());
        audit.decided(request(), ApprovalOutcome.APPROVED);

        assertTrue(session.messages().isEmpty(), "审批事件不进投影：" + session.messages());
    }

    @Test
    void decidedCarriesTheOutcomeWireValue() throws IOException {
        Session session = new Session("s1", null);
        SessionApprovalAudit audit = new SessionApprovalAudit(session, 1);

        audit.decided(request(), ApprovalOutcome.UNAVAILABLE);

        assertEquals("unavailable", session.events().get(0).approval().outcome());
    }

    @Test
    void allFourOutcomesHaveWireValues() throws IOException {
        Session session = new Session("s1", null);
        SessionApprovalAudit audit = new SessionApprovalAudit(session, 1);

        for (ApprovalOutcome outcome : ApprovalOutcome.values()) {
            audit.decided(request(), outcome);
        }

        List<String> wires = session.events().stream()
                .map(e -> e.approval().outcome())
                .toList();
        assertEquals(List.of("approved", "rejected", "cancelled", "unavailable"), wires,
                "四档结局的线上取值必须固定");
    }
}
