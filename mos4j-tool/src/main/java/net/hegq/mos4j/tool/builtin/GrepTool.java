package net.hegq.mos4j.tool.builtin;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * grep 工具（T5.1）：在文件内容里搜正则。
 *
 * <p><b>RE2J 是硬性要求</b>：模式来自模型 = 不可信输入。
 * {@code java.util.regex} 是回溯引擎，病态模式能长时间占死一个线程（ReDoS）；
 * RE2 线性时间，最坏情况也有界。CI 的 C6 门会扫 {@code java.util.regex} import。
 *
 * <p><b>非法正则直接报错</b>，不静默当字面量——模型写的 {@code [abc} 少了半个括号，
 * 当字面量搜会「搜不到」然后让模型以为文件里没有，把错误埋进推理。
 *
 * <p><b>二进制跳过</b>：含 NUL 的文件不搜也不报错（在结果里注明跳过了几个）。
 */
public final class GrepTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "grep";

    /** 缺省结果条数上限。 */
    public static final int DEFAULT_LIMIT = 100;

    /** 结果条数硬上限。 */
    public static final int MAX_LIMIT = 1000;

    /** 单文件读取上限：超过按二进制处理（跳过），不撑爆内存。 */
    public static final int MAX_FILE_BYTES = 1024 * 1024;

    /** 单次搜索的文件数上限。 */
    public static final int MAX_FILES = 20_000;

    private final SandboxedFs fs;

    /** @param fs 文件 jail */
    public GrepTool(SandboxedFs fs) {
        if (fs == null) {
            throw new IllegalArgumentException("tool: grep 需要 fs");
        }
        this.fs = fs;
    }

    /**
     * 参数。
     *
     * @param pattern 正则（RE2 语法）
     * @param path    起始目录或单个文件（缺省 = 锚点根）
     * @param glob    只搜文件名匹配该模式的文件（如 {@code *.go}）
     * @param limit   最多返回多少条命中
     */
    public record Params(
            @ToolParam(desc = "正则表达式（RE2 语法；非法正则会直接报错）", required = true) String pattern,
            @ToolParam(desc = "起始目录或文件（缺省为工作目录根）") String path,
            @ToolParam(desc = "只搜文件名匹配该模式的文件，如 *.go") String glob,
            @ToolParam(desc = "最多返回多少条命中（默认 100，上限 1000）") Integer limit) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在文件内容中搜索正则表达式（RE2 语法，线性时间，不怕病态模式）。"
                + "返回 路径:行号:行内容。二进制文件自动跳过。可用 glob 只搜文件名匹配的文件。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.pattern() == null || params.pattern().isBlank()) {
            throw new ToolException("grep 失败：缺少参数 pattern");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(params.pattern());
        } catch (PatternSyntaxException e) {
            // 非法正则直接报错，不静默当字面量——错误必须让模型看见
            throw new ToolException("grep 失败：正则表达式非法（"
                    + e.getDescription() + "）: " + params.pattern());
        }
        int cap = params.limit() == null ? DEFAULT_LIMIT : params.limit();
        if (cap > MAX_LIMIT) {
            cap = MAX_LIMIT;
        }
        if (cap <= 0) {
            cap = DEFAULT_LIMIT;
        }

        // glob 过滤器：只搜匹配该模式的文件（对齐 Go：先按相对路径试，再按文件名试——
        // 这样 "*.go" 命中任意层级的 .go 文件，"src/**" 命中子目录）
        GlobPattern fileFilter = null;
        if (params.glob() != null && !params.glob().isBlank()) {
            try {
                fileFilter = GlobPattern.compile(params.glob());
            } catch (IllegalArgumentException e) {
                throw new ToolException("grep 失败：glob 模式非法: " + e.getMessage());
            }
        }

        String start = params.path() == null || params.path().isBlank() ? "." : params.path();
        Path target;
        try {
            target = fs.resolveExisting(start).toRealPath();
        } catch (net.hegq.mos4j.fs.PathDeniedError e) {
            throw new ToolException("grep 失败：" + e.getMessage());
        } catch (IOException e) {
            throw new ToolException("grep 失败：" + e.getMessage());
        }

        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            files.add(target);
        } else if (Files.isDirectory(target)) {
            try (Stream<Path> stream = Files.walk(target)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(p)) {
                        files.add(p);
                        if (files.size() > MAX_FILES) {
                            throw new ToolException("grep 失败：搜索范围超过 " + MAX_FILES
                                    + " 个文件。请缩小起始目录");
                        }
                    }
                }
            } catch (IOException e) {
                throw new ToolException("grep 失败：遍历失败 " + e.getMessage());
            }
        } else {
            throw new ToolException("grep 失败：既不是文件也不是目录: " + start);
        }

        StringBuilder out = new StringBuilder();
        int matches = 0;
        int skippedBinary = 0;
        int searched = 0;
        for (Path file : files) {
            if (matches >= cap) {
                break;
            }
            // 显示路径相对搜索起点（与模型给的 path 对得上）
            String displayPath = displayPath(target, file);
            if (fileFilter != null
                    && !fileFilter.matches(displayPath)
                    && !fileFilter.matches(file.getFileName().toString())) {
                continue;
            }
            byte[] bytes;
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    skippedBinary++;
                    continue;
                }
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                continue; // 读不了（权限等）跳过，不中断整体搜索
            }
            if (TextContent.isBinary(bytes)) {
                skippedBinary++;
                continue;
            }
            searched++;
            String text = TextContent.normalizeLineEndings(new String(bytes, StandardCharsets.UTF_8));
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length && matches < cap; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    out.append(displayPath).append(':').append(i + 1).append(':')
                            .append(lines[i]).append('\n');
                    matches++;
                }
            }
        }

        if (matches == 0) {
            StringBuilder note = new StringBuilder("没有匹配");
            if (skippedBinary > 0) {
                note.append("（跳过 ").append(skippedBinary).append(" 个二进制文件）");
            }
            return note.toString();
        }
        String result = out.toString();
        if (skippedBinary > 0) {
            result += "\n（已跳过 " + skippedBinary + " 个二进制文件）";
        }
        if (matches >= cap) {
            result += "\n（已达上限 " + cap + " 条，可能还有更多命中。请缩小搜索范围或提高 limit）";
        }
        return result;
    }

    /**
     * 文件相对搜索起点的展示路径。
     *
     * <p>模型给的 {@code path} 是搜索起点；结果里回相对路径，模型才能把命中和自己的输入对上。
     */
    private static String displayPath(Path target, Path file) {
        if (target.equals(file)) {
            return file.getFileName().toString();
        }
        Path baseDir = Files.isDirectory(target) ? target : target.getParent();
        return baseDir == null
                ? file.getFileName().toString()
                : baseDir.relativize(file).toString();
    }
}
