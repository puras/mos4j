package net.hegq.mos4j.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开名的规则（对齐 Go 参考实现 {@code tool/mcp.ToolName}）。
 */
class McpToolNamingTest {

    @Test
    void thePublicNameCarriesTheServerNamespace() {
        assertEquals("mcp__gh__search_issues", McpToolNaming.publicName("gh", "search_issues"));
    }

    @Test
    void illegalCharactersBecomeUnderscores() {
        assertEquals("mcp__my_server__a_b", McpToolNaming.publicName("my-server", "a.b"));
    }

    @Test
    void nonAsciiBytesBecomeOneUnderscoreEach() {
        // 一个汉字在 UTF-8 里是 3 个字节 → 3 个下划线（与 Go 的逐字节处理一致）
        assertEquals("mcp__" + "___" + "__" + "tool", McpToolNaming.publicName("中", "tool"));
    }

    @Test
    void longNamesAreTruncatedWithAHashSuffix() {
        String name = McpToolNaming.publicName("server", "a".repeat(100));

        assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= McpToolNaming.MAX_NAME_BYTES, name);
        String suffix = name.substring(name.length() - 9);
        assertEquals('_', suffix.charAt(0), name);
        for (int i = 1; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            assertTrue((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'),
                    "后缀必须是 8 位十六进制: " + name);
        }
    }

    @Test
    void differentLongToolsGetDifferentNames() {
        String a = McpToolNaming.publicName("server", "a".repeat(80) + "x");
        String b = McpToolNaming.publicName("server", "a".repeat(80) + "y");

        assertNotEquals(a, b);
    }

    @Test
    void theSameInputAlwaysProducesTheSameName() {
        assertEquals(McpToolNaming.publicName("gh", "issue"), McpToolNaming.publicName("gh", "issue"));
    }
}
