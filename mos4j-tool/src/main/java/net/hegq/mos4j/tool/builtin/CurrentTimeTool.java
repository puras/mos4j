package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
/**
 * current_time 工具（T5.4）：给模型当前时间。
 *
 * <p>没有安全副作用，但它有个测试上的讲究：<b>时钟是注入的</b>。
 * 直接 {@code Instant.now()} 的工具没法测「返回的到底是不是现在」，
 * 测试只能做字符串形状断言——注入 Supplier 后测试可以拨时钟。
 */
public final class CurrentTimeTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "current_time";

    private static final java.util.Locale LOCALE = java.util.Locale.ROOT;

    private final Supplier<Instant> clock;

    /** 用系统时钟构造。 */
    public CurrentTimeTool() {
        this(Instant::now);
    }

    /**
     * 用注入的时钟构造（测试用）。
     *
     * @param clock 时钟
     */
    public CurrentTimeTool(Supplier<Instant> clock) {
        if (clock == null) {
            throw new IllegalArgumentException("tool: current_time 需要时钟");
        }
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "获取当前时间（UTC）。无参数。用于给事件打时间戳、计算耗时等场景。";
    }

    @Override
    public Object execute(String argsJson) {
        if (argsJson != null && !argsJson.isBlank() && !argsJson.trim().equals("{}")) {
            throw new ToolException("current_time 失败：本工具不接受参数");
        }
        Instant now = clock.get();
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        return utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", LOCALE))
                + "（" + now.toEpochMilli() + " ms since epoch）";
    }
}
