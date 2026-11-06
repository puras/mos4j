package net.hegq.mos4j.tool.skill;

import com.google.re2j.Pattern;
import net.hegq.mos4j.tool.ToolException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能清单：持有<b>当前快照</b>，可显式重扫替换。
 *
 * <p><b>站位：渐进式披露</b>——元数据进上下文、正文按需加载。清单（name + description）
 * 进<b>系统提示词</b>（装配期渲染一次 → 静态，保 prompt cache）；正文由
 * {@link SkillTool} 按需取（不走 read 工具：技能正文来自宿主声明的目录，
 * 与「读工作区文件」的权限面分开）。
 *
 * <p><b>快照语义</b>：一次扫描的结果是不可变的（{@link Snapshot}），目录用原子指针持有——
 * 正在跑的会话继续用旧快照，下一次调用看到新快照，不会出现「半个目录」的中间态
 * （有的技能是新版本、有的是旧版本）。
 *
 * <p><b>不做技能级工具收窄</b>：{@code allowed-tools} 只解析、只留痕（诊断里明说
 * 「当前不生效」），执行语义归宿主（policy 名单 + 注册表可见性）。理由：PI 的
 * {@code allowed-tools} 只存在于文档、DSH 连字段都没有，两家都没有「技能激活 → 工具面收窄」
 * 的机制；MoS 不自创第二套安全真相。
 *
 * <p><b>目录是数据面</b>：技能目录可以显式重扫（{@link #reload()}）——那是数据刷新，
 * 不是运行期插件/组件替换（组件替换会改执行语义，技能目录只改「有哪些说明书」）。
 */
public final class SkillCatalog {

    /** 技能文件名（技能根目录下的这个文件就是技能本体）。 */
    public static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * 单个技能正文的默认字节上限（约 16K token）。
     *
     * <p>正文进的是工具结果，那道截断网兜得住体积；但<b>装不装</b>技能是装配期的决定：
     * 正文离谱大的技能多半是放错了文件（把整本手册塞进 SKILL.md），装进去只会挤占
     * 每次调用的上下文——超限的技能在<b>调用时</b>报错（不是从清单里消失，
     * 后者会让模型以为技能不存在）。
     */
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    /**
     * 技能清单的默认字节上限（约 2K token）。
     *
     * <p>清单是<b>常驻</b>内容：它进系统提示词，每一轮都在。技能多的时候清单比正文更贵——
     * 所以清单自带预算，超预算时先丢描述（保留名字），再丢条目，丢了多少如实写进末尾提示
     * （不静默丢）。
     */
    public static final int DEFAULT_MAX_CATALOG_BYTES = 8 * 1024;

    /** 技能名长度上限（PI 同款：≤64 字符）。 */
    public static final int MAX_NAME_LENGTH = 64;

    /** 技能描述长度上限（PI 同款：≤1024 字符）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    /** 技能清单的标签名（进系统提示词）。 */
    public static final String CATALOG_TAG = "available_skills";

    /** 技能正文的标签名（进工具结果）。 */
    public static final String CONTENT_TAG = "skill_content";

    /** 技能名的合法形态：kebab-case（不得首尾连字符、不得连续连字符）。 */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** 可见工具名探针（框架不绑工具注册表：装配层注入）。 */
    @FunctionalInterface
    public interface ToolProbe {
        /**
         * 当前模型可见的工具名。
         *
         * @return 工具名（不可变视图即可）
         */
        List<String> visibleToolNames();
    }

    /**
     * 技能发现的选项。
     *
     * @param dirs            技能目录列表（按优先级从高到低；顺序即优先级，同名先到先得）
     * @param reader          读文件与列目录的能力（可空 = 读本地文件）
     * @param maxBodyBytes    单个技能正文的字节上限（≤0 = 缺省）
     * @param maxCatalogBytes 清单渲染的字节上限（≤0 = 缺省）
     * @param toolProbe       可见工具集探针（可空 = 不做 allowed-tools 求交诊断）
     */
    public record Options(
            List<String> dirs,
            SkillReader reader,
            int maxBodyBytes,
            int maxCatalogBytes,
            ToolProbe toolProbe) {

        public Options {
            dirs = dirs == null ? List.of() : List.copyOf(dirs);
        }

        /**
         * 常用形态：只给目录。
         *
         * @param dirs 技能目录（优先级从高到低）
         * @return 选项
         */
        public static Options of(List<String> dirs) {
            return new Options(dirs, null, 0, 0, null);
        }

        /**
         * 换预算（≤0 = 沿用缺省）。
         *
         * @param maxBodyBytes    单个正文的字节上限
         * @param maxCatalogBytes 清单的字节上限
         * @return 新选项
         */
        public Options limits(int maxBodyBytes, int maxCatalogBytes) {
            return new Options(dirs, reader, maxBodyBytes, maxCatalogBytes, toolProbe);
        }
    }

    /**
     * allowed-tools 求交诊断结果。
     *
     * @param missing 声明了但当前模型看不见的工具名（已排序）
     * @param notice  给人看的文案（无缺失时空串）
     */
    public record AllowedToolsCheck(List<String> missing, String notice) {
    }

    /**
     * 用户手势展开的结果。
     *
     * @param text     展开后的文本（未命中时是原文）
     * @param expanded 是否命中并展开
     */
    public record Expansion(String text, boolean expanded) {
    }

    /** 一次目录扫描的不可变结果（快照）。 */
    record Snapshot(List<Skill> skills, List<String> warnings, String digest) {
    }

    private final List<String> dirs;
    private final SkillReader reader;
    private final int maxBodyBytes;
    private final int maxCatalogBytes;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private volatile ToolProbe toolProbe;

    private SkillCatalog(Options options) {
        this.dirs = options.dirs();
        this.reader = options.reader() == null ? new OsSkillReader() : options.reader();
        this.maxBodyBytes = options.maxBodyBytes() > 0
                ? options.maxBodyBytes() : DEFAULT_MAX_BODY_BYTES;
        this.maxCatalogBytes = options.maxCatalogBytes() > 0
                ? options.maxCatalogBytes() : DEFAULT_MAX_CATALOG_BYTES;
        this.toolProbe = options.toolProbe();
        this.snapshot.set(scan());
    }

    /**
     * 发现并装配技能（首次全量扫描）。
     *
     * <p><b>降级而非失败</b>：目录读不动、技能文件坏了——都<b>跳过这个技能</b>并记进
     * {@link #warnings()}，其余技能照常装（不静默丢内容）。装配期不因为技能坏掉而拒绝启动。
     *
     * <p><b>正文不读进内存</b>：目录扫描只取 frontmatter 元数据与正文路径，
     * 正文在调用时现读。
     *
     * @param options 发现选项
     * @return 技能清单
     */
    public static SkillCatalog load(Options options) {
        return new SkillCatalog(options == null ? Options.of(List.of()) : options);
    }

    /**
     * 技能总数（含仅用户可调用的）。
     *
     * @return 技能数
     */
    public int len() {
        Snapshot current = snapshot.get();
        return current == null ? 0 : current.skills().size();
    }

    /**
     * 全部技能（按装配顺序：目录优先级 → 目录内按名排序）。
     *
     * @return 技能列表（不可变）
     */
    public List<Skill> all() {
        Snapshot current = snapshot.get();
        return current == null ? List.of() : current.skills();
    }

    /**
     * <b>模型可见</b>的技能：{@code disable-model-invocation} 的技能不在其中
     * （模型看不到、也加载不了，只能用户显式调用）。
     *
     * @return 模型可见技能列表
     */
    public List<Skill> modelVisible() {
        List<Skill> out = new ArrayList<>();
        for (Skill skill : all()) {
            if (!skill.disableModelInvocation()) {
                out.add(skill);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 模型可见的技能名（清单渲染与快照用）。
     *
     * @return 技能名列表
     */
    public List<String> modelVisibleNames() {
        List<String> out = new ArrayList<>();
        for (Skill skill : modelVisible()) {
            out.add(skill.name());
        }
        return List.copyOf(out);
    }

    /**
     * 按名字查技能（含仅用户可调用的那些——用户显式手势要用）。
     *
     * @param name 技能名
     * @return 技能；不存在返回 null
     */
    public Skill lookup(String name) {
        if (name == null) {
            return null;
        }
        for (Skill skill : all()) {
            if (skill.name().equals(name)) {
                return skill;
            }
        }
        return null;
    }

    /**
     * 装配期的降级说明（读不动的目录、被跳过的技能、同名碰撞……）。
     *
     * <p>不静默丢内容：装不上的技能都要在这里说一句，宿主在 Describe 快照里能看见。
     *
     * @return 诊断列表
     */
    public List<String> warnings() {
        Snapshot current = snapshot.get();
        return current == null ? List.of() : current.warnings();
    }

    /**
     * 注入可见工具集探针（装配层在注册表就位后调）。
     *
     * <p>每次<b>激活时现查</b>（不缓存）——policy 热替换、MCP 换代后自然跟上。
     *
     * @param probe 探针（可空 = 不做诊断）
     */
    public void setToolProbe(ToolProbe probe) {
        this.toolProbe = probe;
    }

    /**
     * 把某技能声明的 allowed-tools 与当前可见工具集求交（<b>只诊断不收窄</b>）。
     *
     * @param skill 技能
     * @return 诊断结果（没接探针 → 空结果）
     */
    public AllowedToolsCheck checkAllowedTools(Skill skill) {
        List<String> declared = skill.allowedTools();
        ToolProbe probe = toolProbe;
        if (declared.isEmpty() || probe == null) {
            return new AllowedToolsCheck(List.of(), "");
        }
        Set<String> visible = new HashSet<>(probe.visibleToolNames());
        List<String> missing = new ArrayList<>();
        for (String name : declared) {
            if (!visible.contains(name)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return new AllowedToolsCheck(List.of(), "");
        }
        Collections.sort(missing);
        String notice = "技能声明的工具当前不可用: " + String.join(", ", missing)
                + "（allowed-tools 不收窄工具面，仅提示；若为 deferred 工具可用 tool_search 激活）";
        return new AllowedToolsCheck(List.copyOf(missing), notice);
    }

    /**
     * 渲染技能清单（进系统提示词）。
     *
     * <p>清单是<b>常驻</b>内容（每轮都在），所以自带字节预算。超预算时三级降级：
     * 全量 → 只留技能名（丢描述）→ 逐条塞到装得下为止；丢掉的条数如实写进末尾提示，
     * 不静默丢（模型知道还有技能存在，但清单里没列）。
     *
     * <p>渲染是<b>纯函数</b>：同一份清单渲染两次逐字节一致（装配期渲染一次即固定，
     * prompt cache 靠这个）。
     *
     * @return 清单文本（没有模型可见技能时是空串）
     */
    public String render() {
        List<Skill> visible = modelVisible();
        if (visible.isEmpty()) {
            return "";
        }
        int budget = maxCatalogBytes;
        if (renderCatalog(visible, true, 0).getBytes(StandardCharsets.UTF_8).length <= budget) {
            return renderCatalog(visible, true, 0);
        }
        String descNote = "\n（清单预算受限：此处只列技能名，描述略）";
        String namesOnly = renderCatalog(visible, false, 0);
        if (namesOnly.getBytes(StandardCharsets.UTF_8).length + descNote.length() <= budget) {
            return namesOnly + descNote;
        }
        // 装不下：逐条塞到预算为止（预算含末尾的省略提示）。
        int kept = 0;
        while (kept < visible.size()) {
            String text = renderCatalog(visible.subList(0, kept + 1), false, visible.size() - kept - 1);
            if (text.getBytes(StandardCharsets.UTF_8).length > budget) {
                break;
            }
            kept++;
        }
        String partial = renderCatalog(visible.subList(0, kept), false, visible.size() - kept);
        if (partial.getBytes(StandardCharsets.UTF_8).length <= budget) {
            return partial;
        }
        // 连一条都列不下（连省略提示都装不下）：退到最小形态，如实说明「没列」。
        String minimal = "<" + CATALOG_TAG + ">\n（技能清单过长，未列出）\n</" + CATALOG_TAG + ">";
        if (minimal.getBytes(StandardCharsets.UTF_8).length <= budget) {
            return minimal;
        }
        // 预算小到连标签都放不下：不注入清单（工具仍在，模型按需调用）。
        return "";
    }

    /**
     * 读技能正文（调用时读，不驻留）。
     *
     * <p>现读的意义：改了 {@code SKILL.md} 立刻生效——不用重扫、不用重启、不用盯文件。
     *
     * <p>预算在这里校验（不是装配期）：正文超限是<b>调用时报错</b>，
     * 不是「技能从清单里消失」——后者会让模型以为技能不存在。
     *
     * @param skill 技能
     * @return 正文文本
     * @throws SkillException 读不动 / 文件损坏 / 超预算
     */
    public String body(Skill skill) {
        byte[] raw;
        try {
            raw = reader.readFile(skill.bodyPath());
        } catch (IOException e) {
            throw new SkillException("读技能 \"" + skill.name() + "\" 的正文失败: " + e.getMessage(), e);
        }
        SkillFrontmatter.Split split = SkillFrontmatter.split(raw);
        if (split.front() == null) {
            throw new SkillException("技能 \"" + skill.name() + "\" 的文件已损坏（缺少 frontmatter）");
        }
        String body = split.body();
        int size = body.getBytes(StandardCharsets.UTF_8).length;
        if (maxBodyBytes > 0 && size > maxBodyBytes) {
            throw new SkillException("技能 \"" + skill.name() + "\" 正文超预算（"
                    + size + " 字节，上限 " + maxBodyBytes + "）");
        }
        return body;
    }

    /**
     * 渲染技能正文块（进工具结果 / 用户手势展开）。
     *
     * <p>正文现读 + 渲染；读不到就报错（不返回半截正文——半截指令比没指令更糟）。
     *
     * @param skill 技能
     * @return 正文块
     * @throws SkillException 读不动 / 超预算
     */
    public String renderContent(Skill skill) {
        String body = body(skill);
        return "<" + CONTENT_TAG + " name=\"" + skill.name() + "\">\n"
                + escapeTag(body, CONTENT_TAG) + "\n</" + CONTENT_TAG + ">";
    }

    /**
     * 展开用户文本里的技能手势（{@code /name}）。
     *
     * <p>手势 = 文本的<b>首个</b> token 形如 {@code /name}，且 name 是已知技能：命中就把手势
     * 换成技能正文块，其后的文字（参数）追加在正文之后。
     *
     * <p>框架<b>不自动扫</b>用户文本：误触发风险（用户只是在句子里提到 {@code "/deploy"} 就展开
     * 一大段正文），而且 SDK 没有用户命令面。由宿主决定何时调用（拿到用户输入时调一次即可）。
     *
     * <p>未命中返回原文与 {@code expanded=false}——那不是错误（那句话可能根本不是手势）。
     *
     * <p>仅用户可调用的技能在这里<b>可以</b>展开：这正是该标记的用途（模型不许调，用户可以）。
     *
     * @param text 用户输入
     * @return 展开结果
     * @throws SkillException 命中手势但正文读不出来（不能拿半截正文糊弄用户）
     */
    public Expansion expandInvocation(String text) {
        if (text == null) {
            return new Expansion(null, false);
        }
        String trimmed = stripLeading(text);
        if (!trimmed.startsWith("/")) {
            return new Expansion(text, false);
        }
        String rest = trimmed.substring(1);
        String name = rest;
        String tail = "";
        int index = indexOfWhitespace(rest);
        if (index >= 0) {
            name = rest.substring(0, index);
            tail = rest.substring(index + 1).strip();
        }
        Skill skill = lookup(name);
        if (skill == null) {
            return new Expansion(text, false);
        }
        String content = renderContent(skill);
        if (!tail.isEmpty()) {
            content = content + "\n\n" + tail;
        }
        return new Expansion(content, true);
    }

    /**
     * 重扫技能目录（显式、幂等、全量）。
     *
     * <p><b>幂等</b>：目录摘要没变就<b>不换快照</b>（{@code changed=false}、notice 空）——
     * 宿主可以无脑调（比如每条用户消息前调一次）。
     *
     * <p><b>全量</b>：目录可能增删改名，增量扫不出「谁没了」。
     *
     * <p>注意：<b>静态系统提示词不重渲染</b>（保 prompt cache）——清单变化只走 user 侧消息。
     *
     * @return 重扫结果（差异明细与给模型的 user 侧短消息）
     */
    public SkillReloadResult reload() {
        Snapshot next = scan();
        Snapshot current = snapshot.get();
        boolean skillsChanged = current == null || !current.digest().equals(next.digest());
        boolean warningsChanged = current == null || !sameStrings(current.warnings(), next.warnings());
        if (!skillsChanged && !warningsChanged) {
            return new SkillReloadResult(false, List.of(), List.of(), List.of(), "");
        }
        // 快照总是换（诊断要新鲜）；但**只有有效技能集合变了**才通知模型
        //（新增一个坏技能不打扰模型——它压根不在清单里）。
        snapshot.set(next);
        if (!skillsChanged) {
            return new SkillReloadResult(false, List.of(), List.of(), List.of(), "");
        }
        return diff(current, next);
    }

    // ---- 内部实现 ----

    /** 扫一遍技能目录，产出一个新快照（全量，不复用上次的任何东西）。 */
    private Snapshot scan() {
        List<Skill> skills = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Skill> seen = new LinkedHashMap<>();
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (Skill skill : loadDir(dir, warnings)) {
                Skill previous = seen.get(skill.name());
                if (previous != null) {
                    warnings.add("技能 \"" + skill.name() + "\" 重名：保留 " + previous.dir()
                            + "，跳过 " + skill.dir() + "（先到先得）");
                    continue;
                }
                seen.put(skill.name(), skill);
                skills.add(skill);
            }
        }
        return new Snapshot(List.copyOf(skills), List.copyOf(warnings), digestOf(skills));
    }

    /**
     * 装配一个目录下的技能。
     *
     * <p>规则：目录自己含 {@code SKILL.md} → 它就是技能根（不再下钻）；否则它的每个直接
     * 子目录（含 {@code SKILL.md}）是一个技能；裸 {@code .md} 文件不算技能
     * （裸文件没有「技能目录」，资源基准无从谈起）。
     */
    private List<Skill> loadDir(String dir, List<String> warnings) {
        byte[] raw = readSkillFile(dir, warnings);
        if (raw != null) {
            Skill skill = buildSkill(dir, raw, warnings);
            return skill == null ? List.of() : List.of(skill);
        }
        List<SkillReader.Entry> entries;
        try {
            entries = reader.readDir(dir);
        } catch (IOException e) {
            warnings.add("技能目录 \"" + dir + "\" 读不动: " + e.getMessage());
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (SkillReader.Entry entry : entries) {
            if (entry.directory() && !entry.name().startsWith(".")) {
                names.add(entry.name());
            }
        }
        Collections.sort(names); // 确定性输出：与文件系统返回顺序无关
        List<Skill> out = new ArrayList<>();
        for (String name : names) {
            String sub = Path.of(dir, name).toString();
            byte[] subRaw = readSkillFile(sub, warnings);
            if (subRaw == null) {
                continue;
            }
            Skill skill = buildSkill(sub, subRaw, warnings);
            if (skill != null) {
                out.add(skill);
            }
        }
        return out;
    }

    /** 读技能目录下的 {@code SKILL.md}（「没有这个文件」是常态，不出声）。 */
    private byte[] readSkillFile(String dir, List<String> warnings) {
        String path = Path.of(dir, SKILL_FILE_NAME).toString();
        try {
            return reader.readFile(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            warnings.add("技能文件 \"" + path + "\" 读不动: " + e.getMessage());
            return null;
        }
    }

    /** 解析并校验一个技能文件；合法才返回技能。 */
    private Skill buildSkill(String dir, byte[] raw, List<String> warnings) {
        String path = Path.of(dir, SKILL_FILE_NAME).toString();
        SkillFrontmatter.Split split = SkillFrontmatter.split(raw);
        if (split.front() == null) {
            warnings.add("技能 \"" + path + "\" 缺少 frontmatter（文件须以 --- 开头）");
            return null;
        }
        SkillMetadata metadata;
        try {
            metadata = SkillFrontmatter.parse(split.front());
        } catch (SkillException e) {
            warnings.add("技能 \"" + path + "\" 的 frontmatter 解析失败: " + e.getMessage());
            return null;
        }
        if (metadata.name() == null || metadata.name().isEmpty()) {
            // name 缺失回退父目录名（PI 同款）
            String fallback = Path.of(dir).getFileName() == null
                    ? "" : Path.of(dir).getFileName().toString();
            metadata = new SkillMetadata(fallback, metadata.description(), metadata.whenToUse(),
                    metadata.allowedTools(), metadata.disableModelInvocation(), metadata.extra());
        }
        try {
            metadata.validate();
        } catch (SkillException e) {
            warnings.add("技能 \"" + path + "\" 不合法: " + e.getMessage());
            return null;
        }
        if (!metadata.allowedTools().isEmpty()) {
            warnings.add("技能 \"" + metadata.name() + "\" 声明了 allowed-tools，"
                    + "但该字段当前不生效（PI 有字段无实现、DSH 无此字段；技能级工具收窄不自创）"
                    + "——请用宿主策略实现");
        }
        return new Skill(metadata, dir, path);
    }

    /** 比对新旧快照，给出变化明细与通知文案。 */
    private SkillReloadResult diff(Snapshot old, Snapshot next) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        Map<String, Skill> oldByName = new LinkedHashMap<>();
        if (old != null) {
            for (Skill skill : old.skills()) {
                oldByName.put(skill.name(), skill);
            }
        }
        for (Skill skill : next.skills()) {
            Skill previous = oldByName.get(skill.name());
            if (previous != null) {
                if (!metaDigest(previous).equals(metaDigest(skill))) {
                    updated.add(skill.name());
                }
                continue;
            }
            added.add(skill.name());
        }
        if (old != null) {
            Set<String> nextNames = new HashSet<>();
            for (Skill skill : next.skills()) {
                nextNames.add(skill.name());
            }
            for (Skill skill : old.skills()) {
                if (!nextNames.contains(skill.name())) {
                    removed.add(skill.name());
                }
            }
        }
        Collections.sort(added);
        Collections.sort(removed);
        Collections.sort(updated);
        SkillReloadResult result = new SkillReloadResult(true,
                List.copyOf(added), List.copyOf(removed), List.copyOf(updated), "");
        return result.withNotice(renderNotice(result));
    }

    /**
     * 渲染给模型的 user 侧短消息（替换目录）。
     *
     * <p>内容是<b>当前完整清单</b>（不是增量）：模型据此把旧清单整体换成新的。
     * 与系统提示词里的清单同格式（同一个渲染器）——两处长得一样，模型不用认第二种格式。
     */
    private String renderNotice(SkillReloadResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("技能目录已更新");
        if (!result.added().isEmpty()) {
            sb.append("（新增 ").append(String.join("、", result.added())).append("）");
        }
        if (!result.removed().isEmpty()) {
            sb.append("（移除 ").append(String.join("、", result.removed())).append("）");
        }
        if (!result.updated().isEmpty()) {
            sb.append("（更新 ").append(String.join("、", result.updated())).append("）");
        }
        sb.append("。以下是目前可加载的技能（替换此前的清单）：\n\n");
        String text = render();
        sb.append(text.isEmpty() ? "（当前没有可加载的技能）" : text);
        return sb.toString();
    }

    /** 渲染清单正文。 */
    private static String renderCatalog(List<Skill> skills, boolean withDesc, int omitted) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(CATALOG_TAG).append(">\n");
        sb.append("以下技能可按需加载（用 ").append(SkillTool.NAME).append(" 工具按 name 取正文）：\n");
        for (Skill skill : skills) {
            sb.append("- ").append(skill.name());
            if (withDesc) {
                if (skill.description() != null && !skill.description().isEmpty()) {
                    sb.append(": ").append(skill.description());
                }
                if (skill.whenToUse() != null && !skill.whenToUse().isEmpty()) {
                    sb.append("（适用：").append(skill.whenToUse()).append('）');
                }
            }
            sb.append('\n');
        }
        if (omitted > 0) {
            sb.append("（另有 ").append(omitted).append(" 个技能因清单预算未列出）\n");
        }
        sb.append("</").append(CATALOG_TAG).append('>');
        return sb.toString();
    }

    /**
     * 转义正文里出现的框架标签序列。
     *
     * <p>技能正文不能自己开框或闭框：正文里若出现 {@code </skill_content>}，正文块会被
     * 提前结束，模型读到的就是被截断的指令——比不转义更糟。
     *
     * <p>只转义框架<b>自己的</b>标签名，正文其余内容（代码、HTML、尖括号）原样保留：
     * 技能正文常带代码示例，全局转义尖括号会把示例改坏。
     */
    private static String escapeTag(String body, String tag) {
        return body.replace("<" + tag, "&lt;" + tag).replace("</" + tag, "&lt;/" + tag);
    }

    /** 目录摘要（技能名 + 元数据的稳定指纹；<b>正文不进摘要</b>）。 */
    private static String digestOf(List<Skill> skills) {
        List<String> parts = new ArrayList<>();
        for (Skill skill : skills) {
            parts.add(metaDigest(skill));
        }
        Collections.sort(parts);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 单个技能的元数据指纹（正文不进摘要）。 */
    private static String metaDigest(Skill skill) {
        List<String> tools = new ArrayList<>(skill.allowedTools());
        Collections.sort(tools);
        return String.join("\0", skill.name(),
                skill.description() == null ? "" : skill.description(),
                skill.whenToUse() == null ? "" : skill.whenToUse(),
                Boolean.toString(skill.disableModelInvocation()),
                String.join(",", tools),
                skill.dir() == null ? "" : skill.dir());
    }

    private static boolean sameStrings(List<String> a, List<String> b) {
        return a.equals(b);
    }

    private static String stripLeading(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t'
                || text.charAt(i) == '\r' || text.charAt(i) == '\n')) {
            i++;
        }
        return text.substring(i);
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "SkillCatalog{技能 " + len() + " 个，模型可见 " + modelVisible().size() + " 个}";
    }
}
