package net.hegq.mos4j.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 参数容错解析测试（机制 M9）。
 */
class ToolArgsTest {

    @Test
    void validJsonIsUnchanged() {
        assertEquals("{\"a\":1}", ToolArgs.normalize("{\"a\":1}"));
    }

    @Test
    void concatenatedObjectsAreMerged() {
        assertEquals("{\"a\":1,\"b\":2}", ToolArgs.normalize("{\"a\":1}{\"b\":2}"));
    }

    @Test
    void laterKeysWin() {
        assertEquals("{\"a\":2}", ToolArgs.normalize("{\"a\":1}{\"a\":2}"));
    }

    @Test
    void unfixableInputIsReturnedAsIs() {
        String broken = "不是 JSON";
        assertEquals(broken, ToolArgs.normalize(broken));
    }

    @Test
    void emptyInputStaysEmpty() {
        assertEquals("", ToolArgs.normalize(""));
        assertEquals("", ToolArgs.normalize(null));
    }
}
