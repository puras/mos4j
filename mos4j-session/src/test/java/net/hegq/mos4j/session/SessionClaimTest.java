package net.hegq.mos4j.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话级一次性标记（claim）的守护测试。
 *
 * <p>它是「每会话只做一次」这类行为的落点：MCP 服务器说明注入一次就不重复。
 */
class SessionClaimTest {

    @Test
    void theFirstClaimWinsAndLaterOnesDoNot() {
        Session session = new Session("s1", null);

        assertTrue(session.claim("mcp/instructions"));
        assertFalse(session.claim("mcp/instructions"));
        assertFalse(session.claim("mcp/instructions"));
    }

    @Test
    void differentKeysAreIndependent() {
        Session session = new Session("s1", null);

        assertTrue(session.claim("a"));
        assertTrue(session.claim("b"));
        assertFalse(session.claim("a"));
    }

    @Test
    void anEmptyKeyIsRejected() {
        Session session = new Session("s1", null);

        assertThrows(IllegalArgumentException.class, () -> session.claim(" "));
        assertThrows(IllegalArgumentException.class, () -> session.claim(null));
    }

    @Test
    void claimedKeysAreVisibleToTheHost() {
        Session session = new Session("s1", null);
        session.claim("mcp/instructions");

        assertTrue(session.claims().contains("mcp/instructions"));
    }
}
