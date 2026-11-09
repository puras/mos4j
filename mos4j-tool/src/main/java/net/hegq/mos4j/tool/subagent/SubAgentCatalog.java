package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理清单：装配期发现的结果（不可变）。
 *
 * <p><b>站位</b>：<b>一个子代理 = 一个普通工具</b>（agents-as-tools）。主 Agent 用
 * function calling 点名调用 {@code subagent__<name>}，子代理在自己的会话里用
 * <b>收窄过的工具面</b>干活，只回一段<b>固定结构</b>的回执
 * （{@code {status, summary, findings[]}}）。
 *
 * <p><b>装配期固定</b>：不做运行期热加载（技能集有显式 reload，子代理集小得多，
 * 且子代理是「角色」不是「说明书」——热换角色等于热换执行语义）。
 *
 * <p><b>框架不碰文件系统</b>：读文件与列目录都走注入的 {@link SubAgentReader}。
 * 子代理目录列表本身就是宿主声明的授权范围，框架不另行校验根集合。
 *
 * <p><b>清单是常驻内容</b>：它进系统提示词，每一轮都在 → 自带字节预算；
 * 超预算时先丢描述（保留名字），再丢条目，丢了多少如实写进末尾提示（不静默丢）。
 */
public final class SubAgentCatalog {

    /** 子代理定义文件名（子代理目录下的这个文件就是子代理本体）。 */
    public static final String FILE_NAME = "AGENT.md";

    /**
     * 子代理工具名前缀（工具名 = 前缀 + 子代理名）。
     *
     * <p>命名对齐 MCP 的 {@code mcp__<server>__<tool>} 命名空间惯例。
     */
    public static final String TOOL_PREFIX = "subagent__";

    /** 子代理名长度上限（同技能口径：≤64 字符）。 */
    public static final int MAX_NAME_LENGTH = 64;

    /** 子代理描述长度上限（同技能口径：≤1024 字符）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** 子代理清单的默认字节上限（约 2K token）。 */
    public static final int DEFAULT_MAX_CATALOG_BYTES = 8 * 1024;

    /** 子代理清单的标签名（进系统提示词）。 */
    public static final String CATALOG_TAG = "available_subagents";

    /**
     * 宿主模型目录的存在性探针（装配层注入）。
     *
     * <p>存在性判断归宿主：框架看不到宿主的模型目录（那是宿主的事）。
     */
    @FunctionalInterface
    public interface ModelProbe {
        /**
         * 宿主目录里有没有这个模型。
         *
         * @param model 模型 id
         * @return 有则 true
         */
        boolean exists(String model);
    }

    /**
     * 子代理发现的选项。
     *
     * @param dirs            子代理目录列表（按优先级从高到低；顺序即优先级，同名先到先得）
     * @param reader          读文件与列目录的能力（可空 = 读本地文件）
     * @param maxCatalogBytes 清单渲染的字节上限（≤0 = 缺省 8KB）
     * @param modelProbe      模型存在性探针（可空 = 不校验模型覆盖）
     */
    public record Options(
            List<String> dirs,
            SubAgentReader reader,
            int maxCatalogBytes,
            ModelProbe modelProbe) {

        /** 构造器（列表防御性复制）。 */
        public Options {
            dirs = dirs == null ? List.of() : List.copyOf(dirs);
        }

        /**
         * 常用形态：只给目录。
         *
         * @param dirs 子代理目录（优先级从高到低）
         * @return 选项
         */
        public static Options of(List<String> dirs) {
            return new Options(dirs, null, 0, null);
        }
    }

    private final List<SubAgentDefinition> definitions;
    private final List<String> warnings;
    private final SubAgentReader reader;
    private final int maxCatalogBytes;

    private SubAgentCatalog(List<SubAgentDefinition> definitions, List<String> warnings,
                            SubAgentReader reader, int maxCatalogBytes) {
        this.definitions = List.copyOf(definitions);
        this.warnings = List.copyOf(warnings);
        this.reader = reader;
        this.maxCatalogBytes = maxCatalogBytes;
    }

    /**
     * 发现并装配子代理（一次性扫描）。
     *
     * <p><b>降级而非失败</b>：目录读不动、定义文件坏了——都<b>跳过这个子代理</b>并记进
     * {@link #warnings()}，其余照常装。装配期不因为某个子代理坏掉而拒绝启动。
     *
     * <p><b>但模型覆盖是硬失败</b>：声明了模型、宿主目录里没有 → 直接报错
     * （「声明了却没装 = 假能力」，不静默降级到父模型——降级等于让模型以为自己在用 A、
     * 实际在用 B）。
     *
     * @param options 发现选项
     * @return 子代理清单
     * @throws SubAgentException 模型覆盖不存在（fail-closed）
     */
    public static SubAgentCatalog load(Options options) {
        Options opts = options == null ? Options.of(List.of()) : options;
        SubAgentReader reader = opts.reader() == null ? new OsSubAgentReader() : opts.reader();
        int budget = opts.maxCatalogBytes() > 0 ? opts.maxCatalogBytes() : DEFAULT_MAX_CATALOG_BYTES;
        List<SubAgentDefinition> definitions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, SubAgentDefinition> seen = new LinkedHashMap<>();
        for (String dir : opts.dirs()) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (SubAgentDefinition definition : loadDir(dir, reader, warnings)) {
                SubAgentDefinition previous = seen.get(definition.name());
                if (previous != null) {
                    warnings.add("子代理 \"" + definition.name() + "\" 重名：保留 " + previous.dir()
                            + "，跳过 " + definition.dir() + "（先到先得）");
                    continue;
                }
                seen.put(definition.name(), definition);
                definitions.add(definition);
            }
        }
        checkModels(definitions, opts.modelProbe());
        return new SubAgentCatalog(definitions, warnings, reader, budget);
    }

    /**
     * 校验模型覆盖（声明了但宿主目录里没有 → 报错）。
     *
     * @param definitions 子代理声明
     * @param probe       模型存在性探针（可空 = 不校验）
     */
    private static void checkModels(List<SubAgentDefinition> definitions, ModelProbe probe) {
        if (probe == null) {
            return;
        }
        for (SubAgentDefinition definition : definitions) {
            String model = definition.model() == null ? "" : definition.model().strip();
            if (model.isEmpty()) {
                continue;
            }
            if (!probe.exists(model)) {
                throw new SubAgentException("子代理 \"" + definition.name() + "\" 声明的模型 \"" + model
                        + "\" 不在宿主目录里（声明了却没装 = 假能力，不静默降级到父模型）");
            }
        }
    }

    /** 子代理总数。 */
    public int len() {
        return definitions.size();
    }

    /** 全部子代理（按装配顺序：目录优先级 → 目录内按名排序）。 */
    public List<SubAgentDefinition> all() {
        return definitions;
    }

    /** 全部子代理名。 */
    public List<String> names() {
        List<String> out = new ArrayList<>(definitions.size());
        for (SubAgentDefinition definition : definitions) {
            out.add(definition.name());
        }
        return List.copyOf(out);
    }

    /**
     * 按名字查子代理。
     *
     * @param name 子代理名
     * @return 子代理；不存在返回 null
     */
    public SubAgentDefinition lookup(String name) {
        if (name == null) {
            return null;
        }
        for (SubAgentDefinition definition : definitions) {
            if (definition.name().equals(name)) {
                return definition;
            }
        }
        return null;
    }

    /**
     * 装配期的降级说明（读不动的目录、被跳过的定义、同名碰撞……）。
     *
     * <p>不静默丢内容：装不上的子代理都要在这里说一句，宿主在 Describe 快照里能看见。
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 渲染子代理清单（进系统提示词）。
     *
     * <p><b>渲染是纯函数</b>：同一份清单渲染两次逐字节一致（装配期渲染一次即固定，
     * prompt cache 靠这个）。
     *
     * @return 清单文本（没有子代理时是空串）
     */
    public String render() {
        if (definitions.isEmpty()) {
            return "";
        }
        if (renderCatalog(definitions, true, 0).getBytes(StandardCharsets.UTF_8).length <= maxCatalogBytes) {
            return renderCatalog(definitions, true, 0);
        }
        String descNote = "\n（清单预算受限：此处只列子代理名，描述略）";
        String namesOnly = renderCatalog(definitions, false, 0);
        if (namesOnly.getBytes(StandardCharsets.UTF_8).length + descNote.length() <= maxCatalogBytes) {
            return namesOnly + descNote;
        }
        // 装不下：逐条塞到预算为止（预算含末尾的省略提示）。
        int kept = 0;
        while (kept < definitions.size()) {
            String text = renderCatalog(definitions.subList(0, kept + 1), false, definitions.size() - kept - 1);
            if (text.getBytes(StandardCharsets.UTF_8).length > maxCatalogBytes) {
                break;
            }
            kept++;
        }
        String partial = renderCatalog(definitions.subList(0, kept), false, definitions.size() - kept);
        if (partial.getBytes(StandardCharsets.UTF_8).length <= maxCatalogBytes) {
            return partial;
        }
        // 连一条都列不下（连省略提示都装不下）：退到最小形态，如实说明「没列」。
        String minimal = "<" + CATALOG_TAG + ">\n（子代理清单过长，未列出）\n</" + CATALOG_TAG + ">";
        if (minimal.getBytes(StandardCharsets.UTF_8).length <= maxCatalogBytes) {
            return minimal;
        }
        // 预算小到连标签都放不下：不注入清单（工具仍在，模型按需调用）。
        return "";
    }

    /**
     * 读子代理的指令正文（现读）。
     *
     * <p>现读的意义：改了 {@code AGENT.md} 立刻生效——不用重扫、不用重启。
     *
     * @param definition 子代理声明
     * @return 指令正文
     * @throws SubAgentException 读不动 / frontmatter 缺失（fail-closed）
     */
    public String readInstructions(SubAgentDefinition definition) {
        byte[] raw;
        try {
            raw = reader.readFile(definition.instructionsPath());
        } catch (IOException e) {
            throw new SubAgentException("读子代理 \"" + definition.name() + "\" 的定义文件 \""
                    + definition.instructionsPath() + "\" 失败: " + e.getMessage(), e);
        }
        SubAgentFrontmatter.Split split = SubAgentFrontmatter.split(raw);
        if (split.front() == null) {
            // 装配期已校验过 frontmatter 存在；这里仍兜一层（文件被改过）。
            throw new SubAgentException("子代理 \"" + definition.name() + "\" 的定义文件缺少 frontmatter");
        }
        return split.body().strip();
    }

    /**
     * 造一次子代理调用的输入：读指令正文（现读）+ 带上声明的白名单与模型覆盖。
     *
     * <p>任务与上下文由父模型给（工具参数）；工具面与模型覆盖来自声明——<b>实际</b>工具面
     * 由执行体按「白名单 ∩ 父可见集」收窄（deny 优先）。
     *
     * @param definition  子代理声明
     * @param task        任务
     * @param extraContext 附加上下文（可空）
     * @return 调用输入
     * @throws SubAgentException 读不动定义文件
     */
    public SubAgentSpec buildSpec(SubAgentDefinition definition, String task, String extraContext) {
        String instructions = readInstructions(definition);
        return new SubAgentSpec(definition.name(), task, extraContext, instructions,
                definition.tools(), definition.model());
    }

    /**
     * 为清单里的每个子代理造一个工具（名字 {@code subagent__<name>}）。
     *
     * <p>顺序按清单顺序（确定性，利于 prompt cache）。
     *
     * @param runner  执行体（宿主注入）
     * @param timeout 单次调用上限（零或负 = 不限）
     * @return 工具列表
     */
    public List<Tool> tools(SubAgentRunner runner, java.time.Duration timeout) {
        List<Tool> out = new ArrayList<>(definitions.size());
        for (SubAgentDefinition definition : definitions) {
            out.add(new SubAgentTool(definition, this, runner, timeout));
        }
        return List.copyOf(out);
    }

    /**
     * 子代理的工具名（{@code subagent__<name>}）。
     *
     * @param name 子代理名
     * @return 工具名
     */
    public static String toolName(String name) {
        return TOOL_PREFIX + name;
    }

    // ---- 内部实现 ----

    /**
     * 装配一个目录下的子代理。
     *
     * <p>规则：目录自己含 {@code AGENT.md} → 它就是子代理根（不再下钻）；否则它的每个直接
     * 子目录（含 {@code AGENT.md}）是一个子代理；裸 {@code .md} 文件不算子代理
     * （裸文件没有「子代理目录」，角色基准无从谈起）。
     */
    private static List<SubAgentDefinition> loadDir(String dir, SubAgentReader reader, List<String> warnings) {
        byte[] raw = readDefinitionFile(dir, reader, warnings);
        if (raw != null) {
            SubAgentDefinition definition = buildDefinition(dir, raw, warnings);
            return definition == null ? List.of() : List.of(definition);
        }
        List<SubAgentReader.Entry> entries;
        try {
            entries = reader.readDir(dir);
        } catch (IOException e) {
            warnings.add("子代理目录 \"" + dir + "\" 读不动: " + e.getMessage());
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (SubAgentReader.Entry entry : entries) {
            if (entry.directory() && !entry.name().startsWith(".")) {
                names.add(entry.name());
            }
        }
        Collections.sort(names); // 确定性输出：与文件系统返回顺序无关
        List<SubAgentDefinition> out = new ArrayList<>();
        for (String name : names) {
            String sub = Path.of(dir, name).toString();
            byte[] subRaw = readDefinitionFile(sub, reader, warnings);
            if (subRaw == null) {
                continue;
            }
            SubAgentDefinition definition = buildDefinition(sub, subRaw, warnings);
            if (definition != null) {
                out.add(definition);
            }
        }
        return out;
    }

    /** 读子代理目录下的 {@code AGENT.md}（「没有这个文件」是常态，不出声）。 */
    private static byte[] readDefinitionFile(String dir, SubAgentReader reader, List<String> warnings) {
        String path = Path.of(dir, FILE_NAME).toString();
        try {
            return reader.readFile(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            warnings.add("子代理定义文件 \"" + path + "\" 读不动: " + e.getMessage());
            return null;
        }
    }

    /** 解析并校验一个定义文件；合法才返回声明。 */
    private static SubAgentDefinition buildDefinition(String dir, byte[] raw, List<String> warnings) {
        String path = Path.of(dir, FILE_NAME).toString();
        SubAgentFrontmatter.Split split = SubAgentFrontmatter.split(raw);
        if (split.front() == null) {
            warnings.add("子代理 \"" + path + "\" 缺少 frontmatter（文件须以 --- 开头）");
            return null;
        }
        Map<String, Object> meta;
        try {
            meta = SubAgentFrontmatter.parse(split.front());
        } catch (SubAgentException e) {
            warnings.add("子代理 \"" + path + "\" 的 frontmatter 解析失败: " + e.getMessage());
            return null;
        }
        String name = SubAgentFrontmatter.asString(meta.get("name")).strip();
        if (name.isEmpty()) {
            // name 缺失回退父目录名（同技能口径）
            Path pathOfDir = Path.of(dir);
            name = pathOfDir.getFileName() == null ? "" : pathOfDir.getFileName().toString();
        }
        SubAgentDefinition definition = new SubAgentDefinition(
                name,
                SubAgentFrontmatter.asString(meta.get("description")),
                SubAgentFrontmatter.asString(meta.get("model")),
                SubAgentFrontmatter.asStringList(meta.get("tools")),
                dir,
                path,
                !split.body().strip().isEmpty());
        String problem = definition.validate();
        if (problem != null) {
            warnings.add("子代理 \"" + path + "\" 不合法: " + problem);
            return null;
        }
        return definition;
    }

    /** 渲染清单正文。 */
    private static String renderCatalog(List<SubAgentDefinition> definitions, boolean withDesc, int omitted) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(CATALOG_TAG).append(">\n");
        sb.append("以下子代理可按需派用（用 ").append(TOOL_PREFIX).append("<name> 工具点名调用）：\n");
        for (SubAgentDefinition definition : definitions) {
            sb.append("- ").append(definition.name());
            if (withDesc) {
                if (!definition.description().isEmpty()) {
                    sb.append(": ").append(definition.description());
                }
                if (!definition.tools().isEmpty()) {
                    sb.append("（可用工具：").append(String.join("、", definition.tools())).append('）');
                }
            }
            sb.append('\n');
        }
        if (omitted > 0) {
            sb.append("（另有 ").append(omitted).append(" 个子代理因清单预算未列出）\n");
        }
        sb.append("</").append(CATALOG_TAG).append('>');
        return sb.toString();
    }
}
