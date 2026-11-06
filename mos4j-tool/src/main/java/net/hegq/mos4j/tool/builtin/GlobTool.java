package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * glob 工具（T5.2）：按模式找文件。
 *
 * <p>模式不含 {@code /} 时按<b>文件名</b>匹配，含 {@code /} 时按<b>相对根的路径</b>匹配。
 * 遍历走 {@link SandboxedFs} 解析过的根——它只会给出 jail 内的真实路径，
 * 符号链接指向 jail 外的内容不会出现在遍历结果里（ jail 读侧语义，M9）。
 */
public final class GlobTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "glob";

    /** 单次遍历的文件数上限（防大目录树拖垮一轮对话）。 */
    public static final int MAX_FILES = 20_000;

    /** 缺省返回的匹配文件数上限。 */
    public static final int DEFAULT_LIMIT = 1000;

    /** 返回条数的硬上限。 */
    public static final int MAX_LIMIT = 10_000;

    private final SandboxedFs fs;

    /** @param fs 文件 jail */
    public GlobTool(SandboxedFs fs) {
        if (fs == null) {
            throw new IllegalArgumentException("tool: glob 需要 fs");
        }
        this.fs = fs;
    }

    /**
     * 参数。
     *
     * @param pattern glob 模式
     * @param path    起始目录（缺省 = 锚点根）
     * @param limit   最多返回多少个匹配
     */
    public record Params(
            @ToolParam(desc = "glob 模式，如 *.java 或 src/**/*.java", required = true) String pattern,
            @ToolParam(desc = "起始目录（缺省为工作目录根）") String path,
            @ToolParam(desc = "最多返回多少个匹配（默认 1000，上限 10000）") Integer limit) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按 glob 模式查找文件。模式不含 / 时按文件名匹配；含 / 时按相对路径匹配。"
                + "支持 *（不跨目录）、**（跨目录）、?（单字符）、[abc]（字符类）、{a,b}（多选一）。"
                + "limit 限制返回条数（默认 1000，上限 10000）。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.pattern() == null || params.pattern().isBlank()) {
            throw new ToolException("glob 失败：缺少参数 pattern");
        }
        GlobPattern glob;
        try {
            glob = GlobPattern.compile(params.pattern());
        } catch (IllegalArgumentException e) {
            throw new ToolException("glob 失败：" + e.getMessage());
        }

        String start = params.path() == null || params.path().isBlank() ? "." : params.path();
        Path root;
        try {
            root = fs.resolveExisting(start).toRealPath();
        } catch (net.hegq.mos4j.fs.PathDeniedError e) {
            throw new ToolException("glob 失败：" + e.getMessage());
        } catch (IOException e) {
            throw new ToolException("glob 失败：" + e.getMessage());
        }
        if (!Files.isDirectory(root)) {
            throw new ToolException("glob 失败：起始目录不是目录: " + start);
        }

        boolean byName = !params.pattern().contains("/");
        int limit = params.limit() == null ? DEFAULT_LIMIT : params.limit();
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        List<String> hits = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                if (hits.size() >= MAX_FILES) {
                    throw new ToolException("glob 失败：匹配范围超过 " + MAX_FILES
                            + " 个文件。请缩小起始目录或使用更精确的模式");
                }
                String candidate = byName
                        ? p.getFileName().toString()
                        : root.relativize(p).toString();
                if (glob.matches(candidate)) {
                    if (hits.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    hits.add(root.relativize(p).toString());
                }
            }
        } catch (ToolException e) {
            throw e;
        } catch (IOException e) {
            throw new ToolException("glob 失败：遍历失败 " + e.getMessage());
        }
        if (hits.isEmpty()) {
            return "没有匹配的文件";
        }
        String result = String.join("\n", hits);
        if (truncated) {
            result += "\n（已达上限 " + limit + " 个匹配，可能还有更多。请缩小范围或提高 limit）";
        }
        return result;
    }
}
