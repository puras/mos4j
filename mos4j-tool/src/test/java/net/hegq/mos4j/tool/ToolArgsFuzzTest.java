package net.hegq.mos4j.tool;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fuzz（T6.7）：工具参数容错的随机化回归。
 *
 * <p><b>策略</b>：任意字节/字符序列进 {@link ToolArgs#normalize}——
 * 返回值永远是<b>可安全传给工具的字符串</b>（不抛异常、不返回 null）；
 * 合法单根 JSON 原样返回（逐字不变）。</p>
 */
class ToolArgsFuzzTest {

    @Test
    void randomGarbage_neverThrows_alwaysReturnsString() {
        Random random = new Random(20260206);
        for (int i = 0; i < 500; i++) {
            // ① 完全随机字节（含非法 UTF-8）
            byte[] garbage = new byte[random.nextInt(64)];
            random.nextBytes(garbage);
            String input = new String(garbage, StandardCharsetsFuzz.UTF8);
            String out = ToolArgs.normalize(input);
            assertNotNull(out, "随机字节输入不得返回 null");
            assertTrue(out.length() <= Math.max(input.length(), 2),
                    "输出不得膨胀: 输入 " + input.length() + " 输出 " + out.length());

            // ② 随机可见字符拼接
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 20; j++) {
                sb.append((char) (0x20 + random.nextInt(0x7e - 0x20)));
            }
            String ascii = sb.toString();
            assertNotNull(ToolArgs.normalize(ascii));

            // ③ null / 空串 / 空白
            assertEquals("", ToolArgs.normalize(null));
            assertEquals("", ToolArgs.normalize("   "));
        }
    }

    @Test
    void validSingleRootJson_isVerbatim() {
        // 合法单根 JSON：normalize 必须原样返回（不重排、不改格式）
        String[] verbatim = {
                "{\"a\":1}",
                "{ \"b\" : [1, 2, {\"c\": null}] }",
                "{\"unicode\":\"emoji \\ud83d\\ude00 中文\"}",
                "[]",
                "\"plain string\"",
                "42",
        };
        for (String json : verbatim) {
            assertEquals(json, ToolArgs.normalize(json), "合法 JSON 必须逐字不变: " + json);
        }
    }

    /** 隔离 Charset 常量（避免测试类间的意外耦合）。 */
    private static final class StandardCharsetsFuzz {
        static final java.nio.charset.Charset UTF8 = java.nio.charset.StandardCharsets.UTF_8;
    }
}
