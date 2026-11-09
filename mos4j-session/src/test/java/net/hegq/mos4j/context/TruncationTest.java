package net.hegq.mos4j.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 截断：行/字节双限、按工具定向保留、UTF-8 安全。
 */
class TruncationTest {

    @Test
    @DisplayName("不超限就原样返回：一个字都不动")
    void contentWithinLimitsIsReturnedVerbatim() {
        Truncation truncation = Truncation.defaultPolicy();

        TruncatedOutput out = truncation.apply("read", "短内容");

        assertFalse(out.truncated());
        assertEquals("短内容", out.content());
        assertEquals(1, out.originalLines());
    }

    @Test
    @DisplayName("read 留头：文件开头才是内容")
    void readKeepsTheHead() {
        Truncation truncation = Truncation.builder().maxLines(3).maxBytes(100_000).build();

        TruncatedOutput out = truncation.apply("read", "1\n2\n3\n4\n5");

        assertTrue(out.truncated());
        assertTrue(out.content().startsWith("1\n2\n3"), out.content());
        assertFalse(out.content().contains("\n4"), out.content());
        assertFalse(out.content().contains("\n5"), out.content());
        assertEquals(3, out.keptLines(), "保留行数只数内容行，不数截断标记");
        assertEquals(Truncation.Keep.HEAD, out.keep());
    }

    @Test
    @DisplayName("shell 留尾：报错几乎总在最后一行")
    void shellKeepsTheTail() {
        Truncation truncation = Truncation.builder().maxLines(3).maxBytes(100_000).build();

        TruncatedOutput out = truncation.apply("shell", "1\n2\n3\n4\n5");

        assertTrue(out.content().endsWith("5"), out.content());
        assertFalse(out.content().contains("\n1"), out.content());
        assertEquals(Truncation.Keep.TAIL, out.keep());
    }

    @Test
    @DisplayName("其余工具两头各半")
    void otherToolsKeepBothEnds() {
        Truncation truncation = Truncation.builder().maxLines(4).maxBytes(100_000).build();

        TruncatedOutput out = truncation.apply("web_fetch", "1\n2\n3\n4\n5\n6");

        assertTrue(out.content().startsWith("1\n2"), out.content());
        assertTrue(out.content().endsWith("5\n6"), out.content());
        assertEquals(Truncation.Keep.BOTH, out.keep());
    }

    @Test
    @DisplayName("head 集合与 Go 一致：read/current_time/grep/glob/ls 都留头")
    void headToolsMatchTheGoReference() {
        Truncation truncation = Truncation.builder().maxLines(3).maxBytes(100_000).build();

        for (String tool : List.of("read", "current_time", "grep", "glob", "ls")) {
            TruncatedOutput out = truncation.apply(tool, "1\n2\n3\n4\n5");
            assertEquals(Truncation.Keep.HEAD, out.keep(), tool + " 应留头");
        }
    }

    @Test
    @DisplayName("字节上限切在字符边界上：不切出半个汉字")
    void byteLimitCutsOnCharacterBoundary() {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(60).build();

        TruncatedOutput out = truncation.apply("read", "一二三四五".repeat(20));

        assertTrue(out.truncated());
        String head = out.content().split("\n")[0];
        assertFalse(head.contains("�"), head);
        assertTrue("一二三四五".repeat(20).startsWith(head), head);
        assertTrue(out.keptBytes() <= 60, "keptBytes=" + out.keptBytes());
    }

    @Test
    @DisplayName("字节上限不切开代理对（emoji 不变乱码）")
    void byteLimitDoesNotSplitSurrogatePairs() {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(60).build();

        TruncatedOutput out = truncation.apply("read", "😀".repeat(100));

        assertTrue(out.truncated());
        // 保留的头部必须是原文的前缀，且不能出现落单的代理项
        String head = out.content().split("\n")[0];
        assertTrue("😀".repeat(100).startsWith(head), head);
        assertFalse(hasUnpairedSurrogate(head), "代理对被切开了: " + head);
        assertTrue(out.keptBytes() <= 60, "含标记也不能超过字节硬上限: " + out.keptBytes());
    }

    @Test
    @DisplayName("字节硬上限含标记：结果不超过上限")
    void byteLimitIncludesTheMarker() {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(64).build();

        TruncatedOutput out = truncation.apply("grep", "0123456789".repeat(50));

        assertTrue(out.truncated());
        assertTrue(out.keptBytes() <= 64, "keptBytes=" + out.keptBytes());
    }

    /** 字符串里是否有落单的代理项。 */
    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("截断标记说清楚丢了多少")
    void markerTellsWhatWasDropped() {
        Truncation truncation = Truncation.builder().maxLines(2).maxBytes(100_000).build();

        TruncatedOutput out = truncation.apply("read", "1\n2\n3\n4\n5");

        assertTrue(out.content().contains("已截断"), out.content());
        assertTrue(out.content().contains("共 5 行"), out.content());
    }

    @Test
    @DisplayName("指定更小的字节上限：上限含标记，且切在字符边界")
    void explicitByteLimitIsHonoured() {
        Truncation truncation = Truncation.builder().maxLines(100).maxBytes(100_000).build();

        TruncatedOutput out = truncation.apply("read", "一二三四五".repeat(20), 60);

        assertTrue(out.truncated());
        assertTrue(out.keptBytes() <= 60, "keptBytes=" + out.keptBytes());
        String head = out.content().split("\n")[0];
        assertFalse(head.contains("�"), head);
        assertTrue("一二三四五".repeat(20).startsWith(head), head);
    }
}
