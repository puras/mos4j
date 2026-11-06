package net.hegq.mos4j.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能事件落日志（J7 · 机制 M10）。
 *
 * <p>两类事件：{@code skill/activate}（激活留痕，fail-closed）与
 * {@code skill/allowed-tools-mismatch}（诊断，不拦加载）。两者都<b>只进日志、不进投影</b>——
 * 技能正文已经作为普通 {@code tool/result} 进上下文了，再来一条「加载了 X」只是噪声。
 */
class SessionSkillAuditTest {

    @Test
    void activateWritesMetadataOnly() throws IOException {
        Session session = new Session("s1", null);
        SessionSkillAudit audit = new SessionSkillAudit(session, 4);

        audit.activate("deploy", "发布流程", "/skills/deploy");

        List<Event> events = session.events();
        assertEquals(1, events.size());
        Event event = events.get(0);
        assertEquals(EventKind.SKILL_ACTIVATE, event.kind());
        assertEquals("skill/activate", event.kind().wire());
        assertEquals(4, event.turn());
        assertEquals("deploy", event.skill().name());
        assertEquals("发布流程", event.skill().description());
        assertEquals("/skills/deploy", event.skill().dir());
        assertTrue(event.skill().missingAllowed() == null, "激活事件不带 mismatch 载荷");
        assertNull(event.text(), "事件不带正文（正文在 tool/result 里）");
    }

    @Test
    void mismatchWritesOnlyTheMissingList() throws IOException {
        Session session = new Session("s1", null);
        SessionSkillAudit audit = new SessionSkillAudit(session, 2);

        audit.allowedToolsMismatch("scoped", List.of("deploy", "git"));

        Event event = session.events().get(0);
        assertEquals(EventKind.SKILL_ALLOWED_TOOLS_MISMATCH, event.kind());
        assertEquals("skill/allowed-tools-mismatch", event.kind().wire());
        assertEquals("scoped", event.skill().name());
        assertEquals(List.of("deploy", "git"), event.skill().missingAllowed());
        assertNull(event.skill().description(), "诊断事件不重复携带描述");
        assertNull(event.skill().dir());
    }

    @Test
    void skillEventsDoNotEnterTheProjection() throws IOException {
        Session session = new Session("s1", null);
        SessionSkillAudit audit = new SessionSkillAudit(session, 1);

        audit.activate("deploy", "发布流程", "/skills/deploy");
        audit.allowedToolsMismatch("deploy", List.of("git"));

        assertTrue(session.messages().isEmpty(), "技能事件只进日志：" + session.messages());
    }

    @Test
    void skillEventIsImmutable() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a", "b"));
        SkillEvent event = new SkillEvent("x", null, null, mutable);
        mutable.add("c");

        assertEquals(List.of("a", "b"), event.missingAllowed(), "载荷防御性拷贝（事件发出后不该被改）");
    }

    @Test
    void sessionConstructorRejectsNull() {
        assertThrowsIllegalArgument();
    }

    private static void assertThrowsIllegalArgument() {
        try {
            new SessionSkillAudit(null, 1);
            throw new AssertionError("应当拒绝没有会话的审计器");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank(), "拒绝要说明原因");
        }
    }
}