package net.hegq.mos4j.context;

/**
 * 提示词的一段（名字 + 内容）。
 *
 * @param name    段名
 * @param content 段内容
 */
public record PromptSection(String name, String content) {
}
