package net.hegq.mos4j.context;

import java.nio.charset.StandardCharsets;

/**
 * 发现到的一个上下文文件。
 *
 * @param path    路径
 * @param name    候选名（如 {@code AGENTS.md}）
 * @param content 内容（可能被字节预算裁剪）
 * @param bytes   内容字节数（UTF-8）
 * @param trimmed 是否被预算裁剪过
 */
public record ContextFile(String path, String name, String content, int bytes, boolean trimmed) {

    /** 用字节数组构造（UTF-8 解码）。 */
    public static ContextFile of(String path, String name, byte[] content, boolean trimmed) {
        String text = new String(content, StandardCharsets.UTF_8);
        return new ContextFile(path, name, text, content.length, trimmed);
    }
}
