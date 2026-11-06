package net.hegq.mos4j.context;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具输出限流器：截断 + 落盘逃生通道（驱动在写 tool/result 前过一遍）。
 *
 * <p><b>顺序很重要</b>：先截断（模型看到的是有限内容），再把**完整内容**落盘。
 * 反过来做（先落盘再截断）会把完整内容又塞回上下文，限流就白做了。
 *
 * <p><b>落盘失败不假装成功</b>：写失败时只留截断内容并明确告知「完整内容没保存」，
 * 模型就不会去找一个不存在的文件（然后报告「文件不存在」——把降级说成事实）。
 */
public final class ToolOutputLimiter {

    private final Truncation truncation;
    private final SpillStore spillStore;

    private ToolOutputLimiter(Truncation truncation, SpillStore spillStore) {
        this.truncation = truncation;
        this.spillStore = spillStore;
    }

    /**
     * 构造限流器。
     *
     * @param truncation 截断策略（可空 = 不截断）
     * @param spillStore 落盘通道（可空 = 不落盘）
     * @return 限流器
     */
    public static ToolOutputLimiter of(Truncation truncation, SpillStore spillStore) {
        return new ToolOutputLimiter(truncation, spillStore);
    }

    /** 截断策略（可空）。 */
    public Truncation truncation() {
        return truncation;
    }

    /** 落盘通道（可空）。 */
    public SpillStore spillStore() {
        return spillStore;
    }

    /**
     * 限流一段工具输出。
     *
     * @param toolName 工具名（决定保留方向）
     * @param callId   调用 id（用于落盘文件名，便于回溯）
     * @param content  原始输出
     * @return 限流结果
     */
    public LimitedOutput limit(String toolName, String callId, String content) {
        String text = content == null ? "" : content;
        if (truncation == null) {
            return new LimitedOutput(text, false, null, List.of());
        }
        TruncatedOutput truncated = truncation.apply(toolName, text);
        if (!truncated.truncated()) {
            return new LimitedOutput(truncated.content(), false, null, List.of());
        }

        if (spillStore == null) {
            return new LimitedOutput(truncated.content(), true, null,
                    List.of("输出已截断，且未装配落盘通道：完整内容没有保留"));
        }

        SpillResult spilled = spillStore.spill(spillName(toolName, callId), text);
        // 落盘提示要给模型看（路径在提示里），所以它也得占预算：
        // 先算提示的字节数，再把剩下的预算分给内容，最后拼上提示。
        // 否则「最多 50KB」会被提示顶破。
        String receipt = spilled.spilled()
                ? "\n\n[完整输出已落盘] 路径：" + spilled.path()
                        + "\n（需要完整内容时，用读文件工具读这个路径）"
                : "\n\n[落盘失败：完整输出未能保存] " + String.join("; ", spilled.warnings());
        int budget = truncation.maxBytes() - Truncation.utf8Length(receipt);
        List<String> warnings = new ArrayList<>(spilled.warnings());
        String body;
        if (budget > 0) {
            body = truncation.apply(toolName, text, budget).content();
        } else {
            // 上限比提示还小：只留提示（提示里至少还有路径），并明说内容没保留
            body = "";
            warnings.add("字节上限小于落盘提示长度：内容未保留，仅保留落盘提示");
        }
        return new LimitedOutput(body + receipt, true,
                spilled.spilled() ? spilled.path() : null, List.copyOf(warnings));
    }

    /**
     * 限流结果。
     *
     * @param content   最终给模型看的内容
     * @param truncated 是否发生了截断
     * @param spillPath 完整内容的落盘路径（未落盘 = null）
     * @param warnings  降级说明
     */
    public record LimitedOutput(String content, boolean truncated, String spillPath, List<String> warnings) {
    }

    /** 落盘文件名（工具名 + 调用 id，便于回溯到日志）。 */
    private static String spillName(String toolName, String callId) {
        String name = toolName == null ? "output" : toolName;
        if (callId != null && !callId.isBlank()) {
            name = name + "-" + callId;
        }
        return name;
    }
}
