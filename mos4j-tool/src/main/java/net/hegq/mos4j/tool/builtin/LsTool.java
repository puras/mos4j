package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ls 工具（T5.3）：列目录。
 *
 * <p>含隐藏项（点开头）、按名排序；每项输出 {@code name<TAB>type<TAB>size}。
 * 目录不递归——递归是 glob 的活，ls 只看一层（职责分开，输出才可预期）。
 */
public final class LsTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "ls";

    /** 单次列出的条目上限。 */
    public static final int MAX_ENTRIES = 5000;

    /** 缺省返回的条目数上限。 */
    public static final int DEFAULT_LIMIT = 500;

    /** 返回条数的硬上限。 */
    public static final int MAX_LIMIT = 5000;

    private final SandboxedFs fs;

    /** @param fs 文件 jail */
    public LsTool(SandboxedFs fs) {
        if (fs == null) {
            throw new IllegalArgumentException("tool: ls 需要 fs");
        }
        this.fs = fs;
    }

    /**
     * 参数。
     *
     * @param path  目录（缺省 = 锚点根）
     * @param limit 最多返回多少条目
     */
    public record Params(
            @ToolParam(desc = "要列出的目录（缺省为工作目录根）") String path,
            @ToolParam(desc = "最多返回多少条目（默认 500，上限 5000）") Integer limit) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出目录内容（含隐藏项，按名称排序）。每项格式：名称\\t类型(dir/file)\\t字节大小（目录的大小列为 -）。不递归。"
                + "limit 限制返回条数（默认 500，上限 5000）。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = argsJson == null || argsJson.isBlank()
                ? new Params(null, null)
                : BuiltinArgs.parse(argsJson, Params.class);
        String dir = params.path() == null || params.path().isBlank() ? "." : params.path();
        int limit = params.limit() == null ? DEFAULT_LIMIT : params.limit();
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        Path target;
        try {
            target = fs.resolveExisting(dir);
        } catch (IOException e) {
            throw new ToolException("ls 失败：" + e.getMessage());
        }
        if (!Files.isDirectory(target)) {
            throw new ToolException("ls 失败：不是目录: " + dir);
        }

        StringBuilder out = new StringBuilder();
        int count = 0;
        boolean truncated;
        try (Stream<Path> stream = Files.list(target)) {
            List<Path> entries = new ArrayList<>();
            for (Path p : (Iterable<Path>) stream::iterator) {
                entries.add(p);
                if (entries.size() > MAX_ENTRIES) {
                    throw new ToolException("ls 失败：目录条目超过 " + MAX_ENTRIES + "，请换更深的子目录");
                }
            }
            entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
            truncated = entries.size() > limit;
            if (truncated) {
                entries = new ArrayList<>(entries.subList(0, limit));
            }
            for (Path p : entries) {
                boolean isDir = Files.isDirectory(p);
                out.append(p.getFileName().toString()).append('\t')
                        .append(isDir ? "dir" : "file").append('\t')
                        .append(isDir ? "-" : String.valueOf(Files.size(p)))
                        .append('\n');
                count++;
            }
        } catch (IOException e) {
            throw new ToolException("ls 失败：读取目录失败 " + e.getMessage());
        }
        if (count == 0) {
            return "（空目录）";
        }
        String result = out.toString().stripTrailing();
        if (truncated) {
            result += "\n（已达上限 " + limit + " 条，可能还有更多。请换更深的子目录或提高 limit）";
        }
        return result;
    }
}
