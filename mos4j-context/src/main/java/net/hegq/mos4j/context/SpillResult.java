package net.hegq.mos4j.context;

import java.util.List;

/**
 * 落盘逃生通道的结果。
 *
 * @param spilled  是否成功落盘
 * @param path     落盘路径（未落盘为空）
 * @param content  交给模型的内容（落盘失败时**原样保留**，由上层决定怎么截断）
 * @param warnings 降级说明（落盘失败、越界等）
 */
public record SpillResult(boolean spilled, String path, String content, List<String> warnings) {

    /** 落盘成功。 */
    public static SpillResult ok(String path, String content) {
        return new SpillResult(true, path, content, List.of());
    }

    /** 落盘失败（诚实降级：内容不丢，只是没有落盘）。 */
    public static SpillResult failed(String content, String warning) {
        return new SpillResult(false, null, content, List.of(warning));
    }
}
