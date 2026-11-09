package net.hegq.mos4j.context;

/**
 * 截断后的工具输出（事实 + 事实的边界）。
 *
 * @param content       截断后交给模型的内容
 * @param truncated     是否发生了截断
 * @param originalLines 原始行数
 * @param originalBytes 原始字节数（UTF-8）
 * @param keptLines     保留行数
 * @param keptBytes     保留字节数（UTF-8，不含截断标记）
 * @param keep          保留策略（留头 / 留尾 / 两头各半）
 */
public record TruncatedOutput(String content, boolean truncated, int originalLines, int originalBytes,
                              int keptLines, int keptBytes, Truncation.Keep keep) {
}
