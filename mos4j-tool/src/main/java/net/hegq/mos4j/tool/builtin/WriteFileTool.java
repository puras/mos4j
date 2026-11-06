package net.hegq.mos4j.tool.builtin;

import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写文件工具。
 *
 * <p><b>写前观察门</b>（机制 M7）：已存在且没读过的文件，写入被拒；
 * 读过但已被改动/删除的，写入被拒；新建文件不要求先读（createIfAbsent 语义）。
 *
 * <p><b>拒绝文案与 {@code API.md} §4 逐字一致</b>：那是给模型看的行动指引。
 */
public final class WriteFileTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "write";

    private final SandboxedFs fs;
    private final ObserveGate gate;

    /**
     * @param fs   文件 jail
     * @param gate 写前观察门
     */
    public WriteFileTool(SandboxedFs fs, ObserveGate gate) {
        if (fs == null || gate == null) {
            throw new IllegalArgumentException("tool: write 需要 fs 与 gate");
        }
        this.fs = fs;
        this.gate = gate;
    }

    /**
     * 参数。
     *
     * @param path    文件路径（相对 jail 锚点）
     * @param content 文件内容
     */
    public record Params(
            @ToolParam(desc = "文件路径（相对工作目录）", required = true) String path,
            @ToolParam(desc = "要写入的完整内容", required = true) String content) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写入文件（覆盖写）。已存在的文件必须先 read 过；新建文件不需要先 read。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = BuiltinArgs.parse(argsJson, Params.class);
        if (params.path() == null || params.path().isBlank()) {
            throw new ToolException("write 失败：缺少参数 path");
        }
        if (params.content() == null) {
            throw new ToolException("write 失败：缺少参数 content");
        }
        Path target = fs.resolveForWrite(params.path());

        ObserveGate.Verdict verdict = gate.verify(target);
        if (!verdict.allowed()) {
            throw new ToolException(FileOps.writeDenial(params.path(), verdict));
        }

        // 写入侧也拒 NUL：写进去的文件自己都读不回来（读工具当二进制拒）
        String content = TextContent.requireNoNul(params.content(), params.path());

        FileOps.writeAtomically(target, content);
        // 写完就是最新状态：观察门同步刷新，避免紧接着的第二次写被自己挡住
        gate.observe(target);
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        return "已写入 " + params.path() + "（" + bytes + " 字节）";
    }

    /**
     * 目标是否已存在（诊断用）。
     *
     * @param path 路径
     * @return true 表示存在
     */
    public boolean exists(String path) {
        try {
            return Files.exists(fs.resolveExisting(path));
        } catch (Exception e) {
            return false;
        }
    }
}
