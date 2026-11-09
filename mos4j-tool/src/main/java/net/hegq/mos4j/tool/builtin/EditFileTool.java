package net.hegq.mos4j.tool.builtin;

import tools.jackson.databind.JsonNode;
import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 编辑文件工具（按原文替换，可一次多处）。
 *
 * <p><b>edit 一律要求先 read</b>（机制 M7）：没读过就编辑，等于盲改。
 * 拒绝文案与 {@code API.md} §4 逐字一致。
 *
 * <p><b>每处 old_string 必须唯一命中</b>（不做 replace_all）：替模型猜「改哪一处」是
 * 数据损坏的常见来源。要改多处，模型可以在一次调用里给多处——但每一处都得自己唯一。
 *
 * <p><b>全部按原文定位</b>：先在原始内容里定位所有处，再从后往前统一替换。
 * 顺序不影响结果（第二处的 old_string 不会因为第一处已改而找不到），也不会出现「改到一半」。
 * 重叠的编辑直接报错，请合并成一条。
 *
 * <p><b>参数形状收三种</b>：{@code edits} 传数组、传单个对象、或传一段 JSON 字符串都收下。
 * 有些模型就是这么发的；报错让模型重试一轮，比不上直接收下。
 *
 * <p><b>行尾归一 + BOM 保留</b>：匹配时按归一行尾（{@code \r\n} → {@code \n}）比对，
 * 落盘时按文件原有行尾风格写回；BOM 原样保留，不被读或写悄悄吃掉。
 */
public final class EditFileTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "edit";

    private final SandboxedFs fs;
    private final ObserveGate gate;

    /**
     * @param fs   文件 jail
     * @param gate 写前观察门
     */
    public EditFileTool(SandboxedFs fs, ObserveGate gate) {
        if (fs == null || gate == null) {
            throw new IllegalArgumentException("tool: edit 需要 fs 与 gate");
        }
        this.fs = fs;
        this.gate = gate;
    }

    /**
     * 参数。
     *
     * @param path  文件路径（相对 jail 锚点）
     * @param edits 一处或多处替换
     */
    public record Params(
            @ToolParam(desc = "文件路径（相对工作目录）", required = true) String path,
            @ToolParam(desc = "一处或多处替换；每处的 old_string 必须在文件里唯一出现",
                    required = true) List<Edit> edits) {
    }

    /**
     * 一处替换。
     *
     * @param old_string 要被替换的原文本
     * @param new_string 替换成的新文（空字符串表示删除该片段）
     */
    public record Edit(
            @ToolParam(desc = "要被替换的原文，必须在文件中唯一出现", required = true) String old_string,
            @ToolParam(desc = "替换后的新文本（空字符串表示删除该片段）", required = true) String new_string) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按原文替换文件内容（可一次多处）。参数 edits 传一处或多处替换，"
                + "每处含 old_string 与 new_string。编辑前必须先 read 该文件；"
                + "每处的 old_string 必须与文件内容逐字一致，且在文件中唯一出现。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        JsonNode root = BuiltinArgs.readTree(argsJson);
        String path = text(root, "path");
        if (path == null || path.isBlank()) {
            throw new ToolException("edit 失败：缺少参数 path");
        }
        List<Edit> edits = readEdits(root.get("edits"));
        if (edits.isEmpty()) {
            throw new ToolException("edit 失败：缺少参数 edits");
        }
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            int no = i + 1;
            if (edit.old_string() == null || edit.old_string().isEmpty()) {
                throw new ToolException("edit 失败：第 " + no + " 处编辑缺少 old_string");
            }
            if (edit.new_string() == null) {
                throw new ToolException("edit 失败：第 " + no + " 处编辑缺少 new_string");
            }
            if (edit.old_string().equals(edit.new_string())) {
                throw new ToolException("edit 失败：第 " + no + " 处编辑的 old_string 与 new_string 相同，无需编辑");
            }
            // 写入侧也拒 NUL：编辑出来的文件自己都读不回来
            TextContent.requireNoNul(edit.old_string(), path);
            TextContent.requireNoNul(edit.new_string(), path);
        }

        Path target = fs.resolveExisting(path);
        // 写入侧也要过一遍写权限：只读根里的文件能读不能改。
        // （先解析读路径，再解析写路径：两者可能落在不同的根上。）
        fs.resolveForWrite(path);
        ObserveGate.Verdict verdict = gate.verify(target);
        if (!verdict.allowed()) {
            throw new ToolException(FileOps.editDenial(path, verdict));
        }

        byte[] bytes = Files.readAllBytes(target);
        String raw = TextContent.decode(bytes, path);
        String normalized = TextContent.normalizeLineEndings(raw);

        // 全部按原文定位：先定位再统一应用，顺序不影响结果
        List<Located> located = new ArrayList<>(edits.size());
        for (int i = 0; i < edits.size(); i++) {
            int no = i + 1;
            String needle = TextContent.normalizeLineEndings(edits.get(i).old_string());
            int occurrences = count(normalized, needle);
            if (occurrences == 0) {
                throw new ToolException("edit 失败：第 " + no + " 处编辑的 old_string 在 " + path + " 中没有找到");
            }
            if (occurrences > 1) {
                throw new ToolException("edit 失败：第 " + no + " 处编辑的 old_string 在 " + path + " 中出现 "
                        + occurrences + " 次，无法确定改哪一处。请提供更长的上下文（每处都要唯一）");
            }
            int start = normalized.indexOf(needle);
            located.add(new Located(no, start, start + needle.length(), edits.get(i)));
        }
        // 重叠检查：改同一片区域会让结果取决于顺序——直接报错，请合并成一条
        List<Located> sorted = new ArrayList<>(located);
        sorted.sort(Comparator.comparingInt(l -> l.start));
        for (int i = 1; i < sorted.size(); i++) {
            Located previous = sorted.get(i - 1);
            Located current = sorted.get(i);
            if (current.start < previous.end) {
                throw new ToolException("edit 失败：第 " + current.no() + " 处编辑与第 " + previous.no()
                        + " 处重叠，请合并成一条 edit");
            }
        }

        // 从后往前替换：前面的替换不会让后面的偏移失效
        String updated = normalized;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Located locatedEdit = sorted.get(i);
            String replacement = TextContent.normalizeLineEndings(locatedEdit.edit().new_string());
            updated = updated.substring(0, locatedEdit.start())
                    + replacement
                    + updated.substring(locatedEdit.end());
        }
        // 还原行尾风格（BOM 原样留在字符串里）
        String finalText = raw.contains("\r\n") ? updated.replace("\n", "\r\n") : updated;

        FileOps.writeAtomically(target, finalText);
        gate.observe(target);
        return "已编辑 " + path + "（替换 " + sorted.size() + " 处）";
    }

    /** 一处替换的定位结果。 */
    private record Located(int no, int start, int end, Edit edit) {
    }

    /**
     * 读 {@code edits}：数组 / 单个对象 / JSON 字符串都收下。
     */
    private static List<Edit> readEdits(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        JsonNode value = node;
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return List.of();
            }
            try {
                value = BuiltinArgs.readTree(text);
            } catch (ToolException e) {
                throw new ToolException("edit 失败：edits 不是合法的 JSON: " + e.getMessage());
            }
            if (value.isTextual()) {
                throw new ToolException("edit 失败：edits 是嵌套的 JSON 字符串，无法解析");
            }
        }
        if (value.isArray()) {
            List<Edit> edits = new ArrayList<>(value.size());
            for (JsonNode item : value) {
                edits.add(readEdit(item));
            }
            return edits;
        }
        if (value.isObject()) {
            return List.of(readEdit(value));
        }
        throw new ToolException("edit 失败：edits 必须是对象或数组");
    }

    /** 读一处替换。 */
    private static Edit readEdit(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new ToolException("edit 失败：edits 里每一处必须是对象");
        }
        return new Edit(text(node, "old_string"), text(node, "new_string"));
    }

    /** 取字符串字段（缺失或 null 返回 null）。 */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static int count(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
