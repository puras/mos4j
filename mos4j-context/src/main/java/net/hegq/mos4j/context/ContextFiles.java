package net.hegq.mos4j.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文文件发现（项目说明 / 约定文件的自动装配）。
 *
 * <p><b>规则</b>：
 * <ol>
 *   <li><b>候选名优先级</b>：同一目录里按候选名顺序取，优先级高的赢。</li>
 *   <li><b>向上递归</b>：从起始目录一路往上，每层至多取一个文件，直到顶层。</li>
 *   <li><b>同目录 override</b>：一个目录里只认优先级最高的那个候选名，
 *       低优先级的同目录文件被覆盖（两个说明文件打架不是好主意）。</li>
 *   <li><b>真实路径去重</b>：同一个文件（软链接指向同一处）只进上下文一次。</li>
 *   <li><b>字节预算裁剪</b>：超预算的文件被裁剪或丢弃，**并写进 warnings**
 *       （悄悄丢内容 = 模型以为项目没有这条约定）。</li>
 * </ol>
 *
 * <p><b>框架不碰文件系统</b>：读文件、算父目录、算真实路径都由注入的
 * {@link ContextFileReader} 提供；本类只负责规则。
 */
public final class ContextFiles {

    /** 缺省候选名（按优先级）。 */
    public static final List<String> DEFAULT_CANDIDATES =
            List.of("AGENTS.md", "MOS.md", ".mos/AGENTS.md");

    /** 缺省字节预算（64KB）。 */
    public static final int DEFAULT_BYTE_BUDGET = 64 * 1024;

    private final List<ContextFile> files;
    private final List<String> warnings;

    private ContextFiles(List<ContextFile> files, List<String> warnings) {
        this.files = List.copyOf(files);
        this.warnings = List.copyOf(warnings);
    }

    /** 缺省发现（起始目录 + 缺省候选名 + 缺省预算）。 */
    public static ContextFiles discover(String startDir, ContextFileReader reader) throws IOException {
        return discover(startDir, DEFAULT_CANDIDATES, reader, DEFAULT_BYTE_BUDGET);
    }

    /**
     * 发现上下文文件（缺省候选名，自定义字节预算）。
     *
     * @param startDir   起始目录
     * @param reader     读取入口
     * @param byteBudget 字节预算（<=0 = 不限）
     * @return 发现结果
     * @throws IOException 读取失败
     */
    public static ContextFiles discover(String startDir, ContextFileReader reader, int byteBudget)
            throws IOException {
        return discover(startDir, DEFAULT_CANDIDATES, reader, byteBudget);
    }

    /**
     * 发现上下文文件。
     *
     * @param startDir   起始目录
     * @param candidates 候选名（按优先级，可空 = 用缺省）
     * @param reader     读取入口
     * @param byteBudget 字节预算（<=0 = 不限）
     * @return 发现结果
     * @throws IOException 读取失败（单个文件读失败按降级处理，不中断整体发现）
     */
    public static ContextFiles discover(String startDir, List<String> candidates,
                                        ContextFileReader reader, int byteBudget) throws IOException {
        if (reader == null) {
            throw new IllegalArgumentException("context: 发现上下文文件需要读取入口");
        }
        List<String> names = candidates == null || candidates.isEmpty() ? DEFAULT_CANDIDATES : candidates;
        List<String> warnings = new ArrayList<>();
        List<ContextFile> found = new ArrayList<>();
        Set<String> seenRealPaths = new HashSet<>();

        String dir = startDir;
        int depth = 0;
        while (dir != null && !dir.isBlank()) {
            // 同目录 override：按优先级取第一个存在的候选名
            for (String name : names) {
                String path = reader.join(dir, name);
                byte[] content;
                try {
                    content = reader.readIfExists(path);
                } catch (IOException e) {
                    warnings.add("读取失败（已跳过）: " + path + "（" + e.getMessage() + "）");
                    continue;
                }
                if (content == null) {
                    continue;
                }
                String real = reader.realPath(path);
                if (real != null && !seenRealPaths.add(real)) {
                    // 真实路径去重：同一个文件不重复进上下文
                    warnings.add("重复文件已跳过（真实路径相同）: " + path);
                    break;
                }
                found.add(ContextFile.of(path, name, content, false));
                break; // 本层只取一个
            }
            dir = reader.parentOf(dir);
            depth++;
            if (depth > MAX_DEPTH) {
                warnings.add("向上递归超过上限（" + MAX_DEPTH + " 层），已停止");
                break;
            }
        }

        // 字节预算裁剪（从近到远：近的文件优先保留）
        List<ContextFile> trimmed = new ArrayList<>();
        int used = 0;
        for (ContextFile file : found) {
            if (byteBudget <= 0) {
                trimmed.add(file);
                continue;
            }
            int remaining = byteBudget - used;
            if (remaining <= 0) {
                warnings.add("超出字节预算，已丢弃: " + file.path());
                continue;
            }
            if (file.bytes() <= remaining) {
                trimmed.add(file);
                used += file.bytes();
                continue;
            }
            byte[] cut = cutHead(file.content().getBytes(StandardCharsets.UTF_8), remaining);
            trimmed.add(ContextFile.of(file.path(), file.name(), cut, true));
            warnings.add("超出字节预算，已裁剪: " + file.path()
                    + "（" + file.bytes() + " → " + cut.length + " 字节）");
            used += cut.length;
        }

        return new ContextFiles(trimmed, warnings);
    }

    /** 向上递归的层数上限（防目录成环）。 */
    public static final int MAX_DEPTH = 64;

    /** 发现到的文件（近的在前）。 */
    public List<ContextFile> files() {
        return files;
    }

    /** 降级说明（读失败 / 去重 / 裁剪 / 丢弃）。 */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 渲染成一段提示词内容。
     *
     * @return 文本（没有文件时返回空串）
     */
    public String render() {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextFile file : files) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("# ").append(file.path()).append('\n').append(file.content());
        }
        return sb.toString();
    }

    /**
     * 按 UTF-8 字节截断（落在字符边界）。
     *
     * <p>回退到字符边界：被排除的第一个字节若是 UTF-8 连续字节（{@code 0b10xxxxxx}），
     * 说明那个字符从更早的位置就开始了，必须整个退掉——否则解出来是半个字符
     * （半个代理对 = 乱码）。
     */
    private static byte[] cutHead(byte[] content, int limit) {
        int end = Math.min(content.length, Math.max(0, limit));
        while (end > 0 && end < content.length && (content[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] cut = new byte[end];
        System.arraycopy(content, 0, cut, 0, end);
        return cut;
    }

    /** 已发现的上下文文件路径（诊断用）。 */
    public Set<String> paths() {
        Set<String> paths = new LinkedHashSet<>();
        for (ContextFile file : files) {
            paths.add(file.path());
        }
        return paths;
    }
}
