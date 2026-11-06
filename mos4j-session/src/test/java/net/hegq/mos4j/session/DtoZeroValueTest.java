package net.hegq.mos4j.session;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 直序列化时的零值省略（对齐 Go 的 {@code omitempty}）。
 *
 * <p>事件本身走 {@link EventJson} 的手写序列化器；这些 DTO 也会被单独序列化
 * （宿主直接导出工具回执、审批记录），两条路径的口径必须一致。</p>
 */
class DtoZeroValueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolResultOmitsFalseErrorAndEmptyCallId() throws Exception {
        String json = MAPPER.writeValueAsString(new ToolResult("", "内容", false));

        assertFalse(json.contains("isError"), json);
        assertFalse(json.contains("callId"), json);
        assertTrue(json.contains("\"content\":\"内容\""), "content 无 omitempty，必须写出：" + json);
    }

    @Test
    void toolResultKeepsTrueError() throws Exception {
        String json = MAPPER.writeValueAsString(new ToolResult("call_1", "失败", true));

        assertTrue(json.contains("\"isError\":true"), json);
        assertTrue(json.contains("\"callId\":\"call_1\""), json);
    }

    @Test
    void compactionOmitsZeroTokensBefore() throws Exception {
        String json = MAPPER.writeValueAsString(new Compaction("摘要", 7, 0));

        assertFalse(json.contains("tokensBefore"), json);
        assertTrue(json.contains("\"firstKeptSeq\":7"), "firstKeptSeq 无 omitempty，必须写出：" + json);
        assertTrue(json.contains("\"summary\":\"摘要\""), json);
    }

    @Test
    void approvalOmitsEmptyOptionalFields() throws Exception {
        String json = MAPPER.writeValueAsString(new Approval("a1", "read", "", "", ""));

        assertFalse(json.contains("callId"), json);
        assertFalse(json.contains("reason"), json);
        assertFalse(json.contains("outcome"), json);
        assertTrue(json.contains("\"id\":\"a1\""), json);
        assertTrue(json.contains("\"toolName\":\"read\""), json);
    }

    @Test
    void headerOmitsZeroSeedSeq() throws Exception {
        String json = MAPPER.writeValueAsString(Header.of("s1"));

        assertFalse(json.contains("seedSeq"), json);
        assertFalse(json.contains("title"), json);
        assertFalse(json.contains("cwd"), json);
        assertFalse(json.contains("parentId"), json);
        assertTrue(json.contains("\"id\":\"s1\""), json);
        assertTrue(json.contains("\"createdAt\""), json);
    }
}
