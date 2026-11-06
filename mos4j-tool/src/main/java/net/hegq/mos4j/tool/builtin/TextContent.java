package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.tool.ToolException;

import java.nio.charset.StandardCharsets;

/**
 * 文本内容的公共处理：行尾归一、BOM 保留、二进制拒绝。
 *
 * <p><b>行尾归一</b>：读出来给模型看的内容统一成 {@code \n}。
 * 模型在不同平台上生成的文本行尾不一致，混着看会让人（和模型）判断错「哪一行是哪一行」。
 *
 * <p><b>BOM 保留</b>：BOM 不在读输出里被悄悄吃掉——
 * 吃掉之后模型写回的文件就丢了 BOM（内容看起来一样，字节不一样）。
 * 所以 BOM 以 {@code U+FEFF} 原样留在读输出里，写回时原样落盘。
 *
 * <p><b>拒绝二进制</b>：带 NUL 字节的一律拒绝。二进制内容进文本工具，
 * 模型看到的是乱码，然后它会「猜」内容——猜出来的写回就是损坏。
 */
final class TextContent {

    private TextContent() {
    }

    /**
     * 判断是否二进制（含 NUL 字节）。
     *
     * @param bytes 字节
     * @return true 表示是二进制
     */
    static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 行尾归一：{@code \r\n} → {@code \n}。
     *
     * @param text 原文
     * @return 归一后的文本
     */
    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n");
    }

    /**
     * 解码并做二进制检查。
     *
     * @param bytes 字节
     * @param path  路径（错误文案用）
     * @return 文本
     */
    static String decode(byte[] bytes, String path) {
        if (isBinary(bytes)) {
            throw new ToolException("拒绝处理二进制文件（内容含 NUL 字节）: " + path);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 拒绝带 NUL 字节的文本。
     *
     * <p>写入侧同样要拒：写入一段带 NUL 的内容，产出的文件自己都读不回来
     * （读工具会当二进制拒），文件从此变成「写进去就废了」的黑洞。
     *
     * @param text 文本
     * @param path 路径（错误文案用）
     * @return 原样返回（便于链式调用）
     */
    static String requireNoNul(String text, String path) {
        if (text != null && text.indexOf('\0') >= 0) {
            throw new ToolException("拒绝处理二进制内容（含 NUL 字节）: " + path);
        }
        return text;
    }
}
