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
 * <p><b>规则</b>（与 Go 参考版 {@code agent/contextfile} 同口径）：
 * <ol>
 *   <li><b>候选名优先级</b>：同一目录里按候选名顺序取第一个命中的：
 *       {@code AGENTS.override.md} &gt; {@code AGENTS.md} &gt; {@code AGENTS.MD}
 *       &gt; {@code CLAUDE.md} &gt; {@code CLAUDE.MD}。
 *       同目录的 override 因此**替换**同目录的其它候选，但不影响其它目录。</li>
 *   <li><b>扫描顺序</b>：全局目录 → 各级祖先目录（由外向内）→ 工作目录。
 *       越靠近工作目录的约定排在**后面**（更具体的在后生效）。</li>
 *   <li><b>向上递归层数</b>：缺省 {@value #DEFAULT_MAX_DEPTH} 层（只看起始目录本身）；
 *       负数 = 不限（走到顶）。</li>
 *   <li><b>真实路径去重</b>：同一个文件（软链接指向同一处）只进上下文一次。</li>
 *   <li><b>字节预算裁剪</b>：超预算的文件被裁剪（行边界优先）或丢弃，
 *       **并写进 warnings**（悄悄丢内容 = 模型以为项目没有这条约定）。</li>
 *   <li><b>去 BOM</b>：带 BOM 的正文会在第一行留下乱码字符。</li>
 * </ol>
 *
 * <p><b>框架不碰文件系统</b>：读文件、算父目录、算真实路径都由注入的
 * {@link ContextFileReader} 提供；本类只负责规则。
 */
public final class ContextFiles {

    /** 缺省候选名（按优先级，与 Go 参考版同名单同顺序）。 */
    public static final List<String> DEFAULT_CANDIDATES =
            List.of("AGENTS.override.md", "AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD");

    /** 缺省字节预算（32KB，与 Go 参考版同量级）。 */
    public static final int DEFAULT_BYTE_BUDGET = 32 * 1024;

    /**
     * 缺省向上递归层数（0 = 只看起始目录本身）。
     *
     * <p>缺省不递归：向上递归会把仓库根、家目录里的约定一起吃进来，
     * 那取决于「从哪启动」，不是宿主显式要的。
     */
    public static final int DEFAULT_MAX_DEPTH = 0;

    /** 全局目录名（相对用户 HOME；MoS 自己的目录，与项目目录无关）。 */
    public static final String DEFAULT_GLOBAL_DIR_NAME = ".mos";

    /** 分节标题（与 Go 参考版同文案）。 */
    private static final String SECTION_HEADER = "项目约定与指引（来自以下上下文文件）：";

    /** 块之间与标题之后的分隔。 */
    private static final String SEPARATOR = "\n\n";

    /** 截断说明预留字节（说明本身也进提示词，不能让它把预算顶穿）。 */
    private static final int TRUNC_NOTE_RESERVE = 128;

    private final List<ContextFile> files;
    private final List<String> warnings;
    private final String text;

    private ContextFiles(List<ContextFile> files, List<String> warnings, String text) {
        this.files = List.copyOf(files);
        this.warnings = List.copyOf(warnings);
        this.text = text == null ? "" : text;
    }

    /** 缺省发现（起始目录本身 + 缺省候选名 + 缺省预算 + 不递归）。 */
    public static ContextFiles discover(String startDir, ContextFileReader reader) throws IOException {
        return discover(startDir, new Options(), reader);
    }

    /**
     * 发现上下文文件（缺省候选名，自定义字节预算）。
     *
     * @param startDir   起始目录
     * @param reader     读取入口
     * @param byteBudget 字节预算（0 = 缺省 32KB；负数 = 不限）
     * @return 发现结果
     * @throws IOException 读取失败
     */
    public static ContextFiles discover(String startDir, ContextFileReader reader, int byteBudget)
            throws IOException {
        return discover(startDir, new Options().byteBudget(byteBudget), reader);
    }

    /**
     * 发现上下文文件（缺省候选名，自定义字节预算）。
     *
     * @param startDir   起始目录
     * @param candidates 候选名（按优先级，可空 = 用缺省）
     * @param reader     读取入口
     * @param byteBudget 字节预算（{@code <=0} = 不限）
     * @return 发现结果
     * @throws IOException 读取失败（单个文件读失败按降级处理，不中断整体发现）
     */
    public static ContextFiles discover(String startDir, List<String> candidates,
                                        ContextFileReader reader, int byteBudget) throws IOException {
        return discover(startDir, new Options().candidates(candidates).byteBudget(byteBudget), reader);
    }

    /**
     * 发现上下文文件（完整参数）。
     *
     * @param startDir 起始目录
     * @param options  发现参数（可空 = 用缺省）
     * @param reader   读取入口
     * @return 发现结果
     * @throws IOException 起始目录不存在 / 不是目录（宿主配置错了，不是可降级的故障）
     */
    public static ContextFiles discover(String startDir, Options options, ContextFileReader reader)
            throws IOException {
        if (reader == null) {
            throw new IllegalArgumentException("context: 发现上下文文件需要读取入口");
        }
        if (startDir == null || startDir.isBlank()) {
            throw new IllegalArgumentException("context: 上下文目录不能为空");
        }
        Options opts = options == null ? new Options() : options;
        if (!reader.isDirectory(startDir)) {
            throw new IOException("context: 上下文目录不存在或不是目录: " + startDir);
        }

        List<String> names = opts.candidates == null || opts.candidates.isEmpty()
                ? DEFAULT_CANDIDATES : opts.candidates;
        List<String> warnings = new ArrayList<>();
        List<ContextFile> found = new ArrayList<>();
        Set<String> seenRealPaths = new HashSet<>();

        for (String dir : scanDirs(startDir, opts, reader)) {
            ContextFile file = loadFromDir(dir, names, reader, warnings);
            if (file == null) {
                continue;
            }
            String real = reader.realPath(file.path());
            if (real != null && !seenRealPaths.add(real)) {
                // 真实路径去重：同一个文件不重复进上下文
                warnings.add("重复文件已跳过（真实路径相同）: " + file.path());
                continue;
            }
            found.add(file);
        }

        Rendered rendered = render(found, opts.byteBudget, warnings);
        return new ContextFiles(rendered.files, warnings, rendered.text);
    }

    /**
     * 要扫的目录（合并顺序）：全局目录 → 各级祖先（由外向内）→ 起始目录。
     */
    private static List<String> scanDirs(String startDir, Options options, ContextFileReader reader) {
        List<String> dirs = new ArrayList<>();
        for (String global : options.globalDirs) {
            if (global == null || global.isBlank()) {
                continue;
            }
            dirs.add(expandHome(global));
        }
        // 祖先链先按「起始目录 → 父目录」收集，最后倒序放进结果：
        // 越外层的约定越一般（排前面），越靠近工作目录的越具体（排后面）。
        List<String> ancestors = new ArrayList<>();
        ancestors.add(startDir);
        String current = startDir;
        int depth = 0;
        while (options.maxDepth < 0 || depth < options.maxDepth) {
            String parent = reader.parentOf(current);
            if (parent == null || parent.isBlank() || parent.equals(current)) {
                break;
            }
            current = parent;
            ancestors.add(current);
            depth++;
        }
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            dirs.add(ancestors.get(i));
        }
        return dirs;
    }

    /**
     * 在一个目录里取第一个命中的候选文件。
     *
     * <p>读不动就记警告并**继续试下一个候选**（文件坏了不等于这个目录没有约定）。
     */
    private static ContextFile loadFromDir(String dir, List<String> names,
                                           ContextFileReader reader, List<String> warnings) {
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
            String text = stripBom(new String(content, StandardCharsets.UTF_8));
            return new ContextFile(path, name, text,
                    text.getBytes(StandardCharsets.UTF_8).length, false);
        }
        return null;
    }

    /**
     * 渲染成合并正文，并按预算裁剪。
     *
     * <p>只进正文的文件才留在结果里（发现但装不下的不算「加载到了」，
     * 否则文件清单与正文会各说各话）。
     */
    private static Rendered render(List<ContextFile> found, int byteBudget, List<String> warnings) {
        if (found.isEmpty()) {
            return new Rendered(List.of(), "");
        }
        List<ContextFile> included = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        int maxBytes = byteBudget < 0 ? 0 : (byteBudget == 0 ? DEFAULT_BYTE_BUDGET : byteBudget);
        int used = SECTION_HEADER.length() + SEPARATOR.length();
        int skipped = 0;

        for (ContextFile file : found) {
            String open = "<project_instructions path=\"" + file.path() + "\">\n";
            int overhead = open.length() + "</project_instructions>".length() + TRUNC_NOTE_RESERVE;
            String content = file.content();
            boolean cut = false;
            if (maxBytes > 0) {
                int remaining = maxBytes - used - overhead - SEPARATOR.length();
                if (remaining <= 0) {
                    // 预算用完了：更具体的文件不再加载（如实记警告，不静默丢）
                    skipped++;
                    continue;
                }
                String trimmed = cutToBudget(content, remaining);
                cut = !trimmed.equals(content);
                content = trimmed;
            }
            String note = "";
            if (cut) {
                note = "\n[上下文文件超出字节预算，已截断：保留前 "
                        + content.getBytes(StandardCharsets.UTF_8).length
                        + " 字节 / 共 " + file.bytes() + " 字节]\n";
                warnings.add("超出字节预算，已裁剪: " + file.path()
                        + "（" + file.bytes() + " → "
                        + content.getBytes(StandardCharsets.UTF_8).length + " 字节）");
            }
            blocks.add(open + content + note + "</project_instructions>");
            included.add(cut
                    ? new ContextFile(file.path(), file.name(), content,
                    content.getBytes(StandardCharsets.UTF_8).length, true)
                    : file);
            used += open.length() + content.length() + note.length()
                    + "</project_instructions>".length() + SEPARATOR.length();
        }

        if (skipped > 0) {
            warnings.add("后续 " + skipped + " 个上下文文件因字节预算不足未加载");
        }
        if (included.isEmpty()) {
            // 一个都没装下：不留空标题（空正文 = 没有上下文文件）
            return new Rendered(List.of(), "");
        }
        return new Rendered(included, SECTION_HEADER + SEPARATOR + String.join(SEPARATOR, blocks));
    }

    /**
     * 把正文裁到 limit 字节内：优先切在行边界，切不到就退回字符边界
     * （不切碎多字节字符）。
     *
     * @param text  正文
     * @param limit 字节上限
     * @return 裁后的正文
     */
    private static String cutToBudget(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return text;
        }
        int cut = Math.max(0, limit);
        // 退到字符边界：被排除的第一个字节若是 UTF-8 连续字节，说明那个字符从更早处开始
        while (cut > 0 && cut < bytes.length && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        String head = new String(bytes, 0, cut, StandardCharsets.UTF_8);
        // 行边界优先——但别为了一行尾巴丢掉大半预算（超长行按字符边界切）。
        // 行位置按**字节**算（与 Go 参考版一致）：字符索引会把多字节行算短。
        int newline = -1;
        for (int i = cut - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                newline = i;
                break;
            }
        }
        if (newline > 0 && cut - newline <= limit / 2) {
            return new String(bytes, 0, newline + 1, StandardCharsets.UTF_8);
        }
        return head;
    }

    /** 去 UTF-8 BOM（带 BOM 的正文进提示词会在第一行留下乱码字符）。 */
    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * 展开路径开头的 {@code ~}（宿主常直接写 {@code ~/.mos}）。
     *
     * <p>取不到 HOME 时原样返回（没有 HOME 不是故障）。
     */
    public static String expandHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if (!path.equals("~") && !path.startsWith("~/")) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return path;
        }
        return path.equals("~") ? home : home + path.substring(1);
    }

    /** 发现参数（可空字段走缺省）。 */
    public static final class Options {

        private List<String> candidates = DEFAULT_CANDIDATES;
        private List<String> globalDirs = List.of();
        private int byteBudget = DEFAULT_BYTE_BUDGET;
        private int maxDepth = DEFAULT_MAX_DEPTH;

        /** 候选名（按优先级）。 */
        public Options candidates(List<String> candidates) {
            this.candidates = candidates == null ? DEFAULT_CANDIDATES : List.copyOf(candidates);
            return this;
        }

        /** 全局目录（用户级约定；按给出的顺序加载，先给的更一般）。 */
        public Options globalDirs(List<String> globalDirs) {
            this.globalDirs = globalDirs == null ? List.of() : List.copyOf(globalDirs);
            return this;
        }

        /** 字节预算（0 = 缺省 32KB；负数 = 不限）。 */
        public Options byteBudget(int byteBudget) {
            this.byteBudget = byteBudget;
            return this;
        }

        /** 向上递归层数（0 = 只看起始目录本身；负数 = 不限）。 */
        public Options maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }
    }

    /** 渲染结果（文件清单 + 合并正文）。 */
    private record Rendered(List<ContextFile> files, String text) {
    }

    /** 发现到的文件（合并顺序：全局 → 由外向内的祖先 → 起始目录）。 */
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
        return text;
    }

    /** 合并正文的字节数（描述用）。 */
    public int textBytes() {
        return text.getBytes(StandardCharsets.UTF_8).length;
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
