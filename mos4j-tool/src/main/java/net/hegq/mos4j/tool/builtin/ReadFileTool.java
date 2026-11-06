package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读文件工具。
 *
 * <p><b>读也必须过 jail</b>（机制 M9）：只做 lexical 检查的话，
 * 根内一个指向 {@code /etc/passwd} 的符号链接就能把边界外的内容读出来。
 * 所以读走的是和写完全一样的解析器（含 Realpath 校验）。
 *
 * <p><b>读成功即观察</b>（机制 M7）：读过的文件进入写前观察门，
 * 之后 write / edit 才被允许。
 */
public final class ReadFileTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "read";

    /** 缺省最大读取字节数。 */
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private final SandboxedFs fs;
    private final ObserveGate gate;
    private final int maxBytes;

    /**
     * @param fs   文件 jail
     * @param gate 写前观察门
     */
    public ReadFileTool(SandboxedFs fs, ObserveGate gate) {
        this(fs, gate, DEFAULT_MAX_BYTES);
    }

    /**
     * @param fs       文件 jail
     * @param gate     写前观察门
     * @param maxBytes 最大读取字节数
     */
    public ReadFileTool(SandboxedFs fs, ObserveGate gate, int maxBytes) {
        if (fs == null || gate == null) {
            throw new IllegalArgumentException("tool: read 需要 fs 与 gate");
        }
        this.fs = fs;
        this.gate = gate;
        this.maxBytes = maxBytes;
    }

    /**
     * 参数。
     *
     * @param path   文件路径（相对 jail 锚点）
     * @param offset 起始行（1 起）
     * @param limit  最多返回多少行
     */
    public record Params(
            @ToolParam(desc = "文件路径（相对工作目录）", required = true) String path,
            @ToolParam(desc = "起始行（从 1 开始）") Integer offset,
            @ToolParam(desc = "最多返回多少行") Integer limit) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取文件内容。路径相对工作目录；读到的内容会进入写前观察，之后才能覆盖或编辑该文件。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.path() == null || params.path().isBlank()) {
            throw new ToolException("read 失败：缺少参数 path");
        }
        Path target = fs.resolveExisting(params.path());
        if (!Files.isRegularFile(target)) {
            throw new ToolException("read 失败：不是普通文件: " + params.path());
        }
        byte[] bytes = readCapped(target);
        String content = TextContent.decode(bytes, params.path());
        content = TextContent.normalizeLineEndings(content);
        if (params.offset() != null || params.limit() != null) {
            content = window(content, params.offset(), params.limit());
        }
        // 读到了就是「看过了」：写前观察门据此放行后续的 write / edit
        gate.observe(target);
        return content;
    }

    private byte[] readCapped(Path target) throws IOException {
        try (InputStream in = Files.newInputStream(target)) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int total = 0;
            int read;
            while (total < maxBytes && (read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        }
    }
    private static String window(String content, Integer offset, Integer limit) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int from = offset == null ? 1 : Math.max(1, offset);
        if (from > lines.size()) {
            return "";
        }
        int to = limit == null ? lines.size() : Math.min(lines.size(), from - 1 + Math.max(0, limit));
        return String.join("\n", lines.subList(from - 1, to));
    }
}
