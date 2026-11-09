package net.hegq.mos4j.spring;

import net.hegq.mos4j.sdk.Mos4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MoS4J 的 Spring Boot 配置属性（前缀 {@code mos4j}）。
 *
 * <p><b>形态</b>：属性只描述「宿主要什么」，不描述「框架怎么做」——
 * 装配语义仍然全部在 {@link net.hegq.mos4j.agent.Agent} / 驱动里（门面只做装配）。
 *
 * <p><b>为什么用属性而不是让宿主手写 Bean</b>：真实 Spring 宿主要的是「加依赖 + 写配置」，
 * 不是「写一段装配代码」。但**自动装配不能悄悄降级**——
 * 见 {@link Mos4jAutoConfiguration} 的 fail-fast 口径。
 *
 * <p><b>示例</b>：
 * <pre>
 * mos4j:
 *   enabled: true
 *   protocol: openai
 *   api-key: ${OPENAI_API_KEY}
 *   model: gpt-4o-mini
 *   workspace: /srv/app
 *   approvals:
 *     required: true
 * </pre>
 */
@ConfigurationProperties(prefix = "mos4j")
public class Mos4jProperties {

    /**
     * 是否自动装配（缺省 true）。
     *
     * <p><b>为什么不缺省 false</b>：加了 starter 却拿不到任何 Bean，是最难查的一类问题
     * （「我明明加依赖了」）。缺省装配 + 缺配置时启动失败并说清缺什么，比静默无 Bean 友好。
     * 确实只想把依赖放在 classpath 上（例如只在某个 profile 里用）时，
     * 显式设 {@code mos4j.enabled=false}。
     */
    private boolean enabled = true;

    /** 协议（缺省 OpenAI；对应各自的环境变量兜底）。 */
    private Mos4j.Protocol protocol = Mos4j.Protocol.OPENAI;

    /** API 密钥（缺省读协议对应的环境变量）。 */
    private String apiKey;

    /** 兼容网关地址（缺省用协议官方地址）。 */
    private String baseUrl;

    /** 模型名（缺省读环境变量，再退协议常用名）。 */
    private String model;

    /** 系统提示词。 */
    private String systemPrompt;

    /** 工作目录（文件工具的 jail 锚点；缺省当前目录）。 */
    private String workspace;

    /** 会话日志目录（缺省 {@code .mos4j/sessions}）。 */
    private String logDir;

    /** 是否装配文件工具四件套（缺省 true）。 */
    private boolean fileTools = true;

    /** 工具并发上限（缺省 0 = 沿用驱动缺省；并发始终是显式 opt-in）。 */
    private int concurrency = 0;

    /** 单轮决策次数上限（缺省 0 = 不限）。 */
    private int maxDecisionRounds = 0;

    /** 是否走流式决策（缺省 false）。 */
    private boolean stream = false;

    /** 审批通道相关配置。 */
    private final Approvals approvals = new Approvals();

    /** 工具输出限流配置。 */
    private final Output output = new Output();

    /** 上下文压缩配置。 */
    private final Compaction compaction = new Compaction();

    /** 技能配置。 */
    private final Skills skills = new Skills();

    /** 上下文文件配置（项目约定文件，如 AGENTS.md）。 */
    private final ContextFiles contextFiles = new ContextFiles();

    /**
     * 路径强制模式。
     *
     * <p><b>为什么是显式开关而不是「自动降级」</b>：路径强制装不上时，
     * 框架会 fail-closed（启动失败并说清两条出路）——「静默退化成没隔离的假装隔离」
     * 是最坏的一种降级。确实没有沙箱可用的机器，宿主显式声明 {@code disabled}。
     */
    private PathScopeMode pathScope = PathScopeMode.AUTO;

    /**
     * Gemini 结束原因逃生阀（可空 = 缺省，即要求结束原因）。
     *
     * <p>只对 Gemini 生效；用在别的协议上会在启动时报错（不静默忽略）。
     */
    private Boolean requireFinish;

    /** 输出的 token 上限（可空 = 用协议缺省）。 */
    private Integer maxTokens;

    /**
     * 路径强制模式。
     */
    public enum PathScopeMode {

        /** 缺省：探一次能不能真用起来；能用就装，不能用就启动失败并说清出路。 */
        AUTO,

        /** 显式不装（宿主知道这台机器没有沙箱可用；Describe 会如实声明）。 */
        DISABLED
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mos4j.Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Mos4j.Protocol protocol) {
        this.protocol = protocol == null ? Mos4j.Protocol.OPENAI : protocol;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public boolean isFileTools() {
        return fileTools;
    }

    public void setFileTools(boolean fileTools) {
        this.fileTools = fileTools;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxDecisionRounds() {
        return maxDecisionRounds;
    }

    public void setMaxDecisionRounds(int maxDecisionRounds) {
        this.maxDecisionRounds = maxDecisionRounds;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Approvals getApprovals() {
        return approvals;
    }

    public Output getOutput() {
        return output;
    }

    public Compaction getCompaction() {
        return compaction;
    }

    public Skills getSkills() {
        return skills;
    }

    public ContextFiles getContextFiles() {
        return contextFiles;
    }

    public PathScopeMode getPathScope() {
        return pathScope;
    }

    public void setPathScope(PathScopeMode pathScope) {
        this.pathScope = pathScope == null ? PathScopeMode.AUTO : pathScope;
    }

    public Boolean getRequireFinish() {
        return requireFinish;
    }

    public void setRequireFinish(Boolean requireFinish) {
        this.requireFinish = requireFinish;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    /** 审批通道配置。 */
    public static class Approvals {

        /**
         * 是否要求人工审批（缺省 false）。
         *
         * <p><b>为什么要有这个开关</b>：不装审批通道时，需要人确认的调用一律 fail-closed 拒绝——
         * 这在安全上是对的，但宿主可能以为「配了审批就该走审批」。显式要求后，
         * 容器里没有 {@code ApprovalChannel} Bean 就直接启动失败，
         * 而不是让所有 ask 类调用在运行期被拒（那时已经晚了）。
         */
        private boolean required = false;

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    /** 工具输出限流配置（上下文是稀缺资源）。 */
    public static class Output {

        /** 是否限流（缺省 true：不限流时单个大输出就可能撑爆上下文）。 */
        private boolean enabled = true;

        /** 行数上限（缺省 2000）。 */
        private int maxLines = 2000;

        /** 字节上限（缺省 50KB，含截断标记本身）。 */
        private int maxBytes = 50 * 1024;

        /**
         * 截断内容的落盘目录（可空）。
         *
         * <p><b>为什么可空</b>：落盘目录必须落在文件 jail 可读根内（越界即构造期失败），
         * 宿主没准备这个目录时就不落盘——但要说清「完整内容真的丢了」，
         * 而不是假装保存了（Describe 快照会如实声明）。
         */
        private String spillDir;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }

        public int getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        public String getSpillDir() {
            return spillDir;
        }

        public void setSpillDir(String spillDir) {
            this.spillDir = spillDir;
        }
    }

    /** 上下文压缩配置。 */
    public static class Compaction {

        /**
         * 上下文窗口（token；0 = 不配置 = 不压缩）。
         *
         * <p><b>为什么不猜</b>：窗口只有服务商知道，拿猜的窗口做压缩决定
         * = 用错误的事实做决定。未配置就不压缩，是诚实的缺省。
         */
        private long contextWindow = 0;

        /** 预留 token（缺省 1024：给回答留余量）。 */
        private int reserveTokens = 1024;

        /** 保留最近多少 token（缺省 4096）。 */
        private int keepRecentTokens = 4096;

        public long getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(long contextWindow) {
            this.contextWindow = contextWindow;
        }

        public int getReserveTokens() {
            return reserveTokens;
        }

        public void setReserveTokens(int reserveTokens) {
            this.reserveTokens = reserveTokens;
        }

        public int getKeepRecentTokens() {
            return keepRecentTokens;
        }

        public void setKeepRecentTokens(int keepRecentTokens) {
            this.keepRecentTokens = keepRecentTokens;
        }
    }

    /**
     * 技能配置。
     *
     * <p><b>不配就不装技能</b>：技能目录是宿主的拓扑（不同环境不一样），
     * 猜一个只会扫出奇怪东西。一个技能都没装上也<b>不让启动失败</b>——
     * 目录不存在等同于「没这个技能」，降级说明进 Describe 快照（装不上的不静默消失）。
     */
    public static class Skills {

        /**
         * 技能目录（按优先级从高到低；同名先到先得）。
         *
         * <p>绝对路径直接用；相对路径按 {@code mos4j.workspace} 解析
         * （不是按进程 CWD——换目录启动不应该换技能集合）。
         */
        private List<String> dirs = new ArrayList<>();

        /** 单个技能正文的字节上限（0 = 缺省 64 KiB）。 */
        private int maxBodyBytes = 0;

        /** 技能清单的字节上限（0 = 缺省 8 KiB）。 */
        private int maxCatalogBytes = 0;

        public List<String> getDirs() {
            return dirs;
        }

        public void setDirs(List<String> dirs) {
            this.dirs = dirs == null ? new ArrayList<>() : dirs;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getMaxCatalogBytes() {
            return maxCatalogBytes;
        }

        public void setMaxCatalogBytes(int maxCatalogBytes) {
            this.maxCatalogBytes = maxCatalogBytes;
        }
    }

    /**
     * 上下文文件配置（项目约定文件，如 {@code AGENTS.md}）。
     *
     * <p><b>为什么不缺省自动扫工作区</b>：自动扫 = 每次启动都可能多出一段没人预期的系统提示词，
     * 而且「扫到了什么」取决于宿主放了哪些文件。显式配目录 = 宿主知道自己在装什么。
     */
    public static class ContextFiles {

        /**
         * 上下文文件所在目录（可空 = 不装配）。
         *
         * <p>绝对路径直接用；相对路径按 {@code mos4j.workspace} 解析（不是进程 CWD）。
         */
        private String dir;

        /**
         * 候选文件名（缺省 = 框架缺省候选：{@code AGENTS.md} / {@code MOS.md} / {@code .mos/AGENTS.md}）。
         *
         * <p>同一目录下多个候选同时存在时，按本列表的顺序取第一个。
         */
        private List<String> candidates = new ArrayList<>();

        /** 总字节预算（可空 = 框架缺省；超出部分被裁掉，裁掉事实进 Describe caveats）。 */
        private Integer byteBudget;

        /** 向上递归层数（可空 = 框架缺省 0 = 只看本目录；负数 = 不限）。 */
        private Integer maxDepth;

        /**
         * 全局目录（用户级约定；可空 = 用框架缺省 {@code ~/.mos}）。
         *
         * <p>给空列表 = 不读全局目录（测试与「只要项目约定」的宿主用）。
         */
        private List<String> globalDirs;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public List<String> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<String> candidates) {
            this.candidates = candidates == null ? new ArrayList<>() : candidates;
        }

        public Integer getByteBudget() {
            return byteBudget;
        }

        public void setByteBudget(Integer byteBudget) {
            this.byteBudget = byteBudget;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
        }

        public List<String> getGlobalDirs() {
            return globalDirs;
        }

        public void setGlobalDirs(List<String> globalDirs) {
            this.globalDirs = globalDirs;
        }
    }
}
