package net.hegq.mos4j.context;

import java.util.List;

/**
 * 压缩结果。
 *
 * @param compacted   是否真的压缩了
 * @param firstKeptSeq 保留段首条事件序号（未压缩时为 0）
 * @param summary     生成的摘要（未压缩时为空）
 * @param tokensBefore 压缩前的上下文 token 估算
 * @param warnings    降级与异常说明（没压成、原因）
 */
public record CompactionResult(boolean compacted, long firstKeptSeq, String summary,
                               int tokensBefore, List<String> warnings) {

    /** 没有触发压缩。 */
    public static CompactionResult skipped(int tokensBefore, String reason) {
        return new CompactionResult(false, 0L, null, tokensBefore, List.of(reason));
    }
}
