package net.hegq.mos4j.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J0 守护测试骨架：验证字段名常量的**自身不变量**（不与序列化挂钩，序列化断言在
 * mos4j-llm/session 有了 DTO 后点亮）。
 *
 * <p>这是防漂移体系的第一颗牙：任何人手改这里的常量，先红的不是消费端，是这排测试。</p>
 */
class FieldNamesTest {

    @Test
    void coreEventKeysMatchFrozenListOrderInsensitive() {
        // 冻结清单的核心事件键（§4.4：字段名一个不改，照抄 Go）
        List<String> frozen = List.of(
                "seq", "kind", "turn", "time", "text", "reasoning",
                "toolCalls", "toolCall", "toolResult", "usage",
                "reason", "approval", "compaction", "error");
        assertEquals(frozen, FieldNames.coreEventKeys(),
                "核心事件键必须与 docs/contract/fieldnames.json 一致；改字段名 = 契约变更，须两人评审");
    }

    @Test
    void usageKeysKeepGoSnakeCase() {
        // Usage 是照抄 Go snake_case 的不对称性之一（§4.4）
        assertEquals("prompt_tokens", FieldNames.PROMPT_TOKENS);
        assertEquals("fresh_input_tokens", FieldNames.FRESH_INPUT_TOKENS);
        assertEquals("completion_tokens", FieldNames.COMPLETION_TOKENS);
        assertEquals("total_tokens", FieldNames.TOTAL_TOKENS);
    }

    @Test
    void toolCallKeysMatchGo() {
        assertEquals("id", FieldNames.ID);
        assertEquals("name", FieldNames.NAME);
        assertEquals("args", FieldNames.ARGS);
        assertEquals("callId", FieldNames.CALL_ID);
        assertEquals("isError", FieldNames.IS_ERROR);
    }

    @Test
    void allKeysIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> FieldNames.allKeys().add("sneaky"),
                "契约集合不可被调用方扩展");
    }
}
