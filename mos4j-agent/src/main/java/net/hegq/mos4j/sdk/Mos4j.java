package net.hegq.mos4j.sdk;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.ContextFile;
import net.hegq.mos4j.context.ContextFileReader;
import net.hegq.mos4j.context.ContextFiles;
import net.hegq.mos4j.context.LocalContextFileReader;
import net.hegq.mos4j.context.PromptSections;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.protocol.anthropic.AnthropicChatModel;
import net.hegq.mos4j.model.protocol.anthropic.AnthropicConfig;
import net.hegq.mos4j.model.protocol.gemini.GeminiChatModel;
import net.hegq.mos4j.model.protocol.gemini.GeminiConfig;
import net.hegq.mos4j.model.protocol.openai.OpenAiChatModel;
import net.hegq.mos4j.model.protocol.openai.OpenAiConfig;
import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.PathScope;
import net.hegq.mos4j.sandbox.PathScopeProbe;
import net.hegq.mos4j.sandbox.PathScopes;
import net.hegq.mos4j.sandbox.SandboxBoundary;
import net.hegq.mos4j.session.JsonlPersistence;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.SkillCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MoS4J SDK 门面（T6.10）：一次依赖拿到全部模块与缺省装配。
 *
 * <p><b>形态</b>：{@code Mos4j.builder()...build()} → {@link Mos4j}，
 * 再 {@code agent()} / {@code session()} 拿可跑对象。门面只做装配与默认值，
 * 不引入任何 SDK 之外的依赖（G6 依赖门守护）。</p>
 *
 * <p><b>为什么复用 {@link Config}/{@link Agent} 而不是另造一套</b>：
 * 门面只是装配糖——语义（裁决顺序、Describe 快照、不变式）全部还在 agent/driver 里；
 * 门面如果自己实现执行语义，就出现了第二份真相。</p>
 *
 * <p><b>默认装配口径</b>：协议缺省 OpenAI（{@code OPENAI_API_KEY} / {@code OPENAI_BASE_URL} /
 * {@code OPENAI_MODEL}）；Anthropic/Gemini 显式指定时用各自的密钥变量。
 * 工具缺省只装文件四件套（read/write/edit/shell），jail 锚在工作目录——
 * 工具面是宿主的决定，门面不替宿主扩大能力。
 *
 * <p><b>路径强制的缺省口径</b>：有<b>已验证</b>的平台后端时（当前 = macOS Seatbelt）自动装配，
 * 写根 = 工作目录，并与文件 jail <b>共用同一份根集合</b>（会话里授权了新目录，shell 跟着能写）。
 * 装配前会<b>探一次</b>这台机器能不能真的把沙箱用起来；探不通就<b>直接报错</b>（fail-closed），
 * 不会退化成「没隔离的假装隔离」。宿主确实不要这层保护时，显式
 * {@link Builder#noPathScope()}（Describe 会如实声明「shell 不受路径强制保护」）。</p>
 *
 * <p><b>可选能力（安全纵深 / 上下文工程）</b>：{@link Builder#approvals} /
 * {@link Builder#guards} / {@link Builder#hooks} / {@link Builder#postHook} /
 * {@link Builder#outputLimiter} / {@link Builder#compaction} 一律**显式可选**：
 * 不装就是不装（fail-closed——例如不装审批通道，需要人确认的调用一律拒绝）。
 * </p>
 */
public final class Mos4j {

    /** 支持的协议（缺省装配用）。 */
    public enum Protocol {
        /** OpenAI Chat Completions（缺省）。 */
        OPENAI,
        /** Anthropic Messages。 */
        ANTHROPIC,
        /** Gemini GenerateContent。 */
        GEMINI
    }

    private final Agent agent;
    private final JsonlPersistence persistence;
    private final Map<String, String> env;

    private Mos4j(Agent agent, JsonlPersistence persistence, Map<String, String> env) {
        this.agent = agent;
        this.persistence = persistence;
        this.env = env;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void requireKey(String envName, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "mos4j: 未配置 " + envName + "（用 apiKey(...) 显式给出，或设置环境变量）");
        }
    }

    /**
     * 缺省模型装配（协议 → 密钥 / 基址 / 模型名 → 官方地址）。
     *
     * <p><b>为什么公开</b>：Spring starter 需要<b>同一个模型实例</b>去做摘要（上下文压缩），
     * 而模型是门面 {@code build()} 之后才有的。把这段口径公开出来，宿主与 starter 共用一份真相，
     * 不必各写一遍「协议 → 环境变量 → 默认地址」——
     * 两份真相迟早会在某个协议上漂移。
     *
     * @param protocol 协议
     * @param apiKey 密钥（可空 = 从环境变量取）
     * @param baseUrl 基地址（可空 = 协议官方地址）
     * @param model 模型名（可空 = 环境变量，再退协议常用名）
     * @param env 环境快照（可空 = 空表）
     * @return 装配好的模型
     */
    public static ChatModel defaultModel(Protocol protocol, String apiKey, String baseUrl,
                                         String model, Map<String, String> env) {
        return defaultModel(protocol, apiKey, baseUrl, model, env, null);
    }

    /**
     * 缺省模型装配（带 Gemini 结束原因逃生阀）。
     *
     * @param protocol       协议
     * @param apiKey         密钥（可空 = 从环境变量取）
     * @param baseUrl        基地址（可空 = 协议官方地址）
     * @param model          模型名（可空 = 环境变量，再退协议常用名）
     * @param env            环境快照（可空 = 空表）
     * @param requireFinish  Gemini：是否要求结束原因（可空 = 要求；false = 逃生阀，给不报 finishReason 的网关用）
     * @return 装配好的模型
     */
    public static ChatModel defaultModel(Protocol protocol, String apiKey, String baseUrl,
                                         String model, Map<String, String> env, Boolean requireFinish) {
        Map<String, String> environment = env == null ? Map.of() : env;
        Protocol effective = protocol == null ? Protocol.OPENAI : protocol;
        if (requireFinish != null && effective != Protocol.GEMINI) {
            // 静默忽略比报错难查得多：宿主以为自己开了逃生阀，实际请求里没这个参数。
            throw new IllegalStateException(
                    "mos4j: requireFinish 只对 Gemini 生效（当前协议 " + effective.name() + "）。"
                            + "显式设置一个不生效的开关，比不设置更难查。");
        }
        return switch (effective) {
            case OPENAI -> {
                String key = firstNonNull(apiKey, environment.get("OPENAI_API_KEY"));
                requireKey("OPENAI_API_KEY", key);
                String base = firstNonNull(baseUrl, environment.get("OPENAI_BASE_URL"));
                String name = firstNonNull(model, environment.get("OPENAI_MODEL"), "gpt-4o-mini");
                yield new OpenAiChatModel(OpenAiConfig.of(key, base, name));
            }
            case ANTHROPIC -> {
                String key = firstNonNull(apiKey, environment.get("ANTHROPIC_API_KEY"));
                requireKey("ANTHROPIC_API_KEY", key);
                String base = firstNonNull(baseUrl, environment.get("ANTHROPIC_BASE_URL"));
                String name = firstNonNull(model, environment.get("ANTHROPIC_MODEL"), "claude-sonnet-4-5");
                yield new AnthropicChatModel(AnthropicConfig.of(key, base, name));
            }
            case GEMINI -> {
                String key = firstNonNull(apiKey, environment.get("GEMINI_API_KEY"));
                requireKey("GEMINI_API_KEY", key);
                String base = firstNonNull(baseUrl, environment.get("GEMINI_BASE_URL"));
                String name = firstNonNull(model, environment.get("GEMINI_MODEL"), "gemini-2.0-flash");
                // 缺省要求 finishReason；显式 false = 逃生阀（对齐 Go 的 Config["require_finish"]="false"）
                yield new GeminiChatModel(GeminiConfig.of(key, base, name),
                        requireFinish == null || requireFinish);
            }
        };
    }

    /** 开始装配。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 当前版本号（与 Maven 坐标同步）。
     *
     * <p>宿主想报「我跑的是哪一版」时的统一出口：写日志、贴 issue、发给服务端都用它，
     * 不要各处自己拼字符串。
     *
     * @return 版本号（如 {@code 0.1.0-SNAPSHOT}）
     */
    public static String version() {
        return net.hegq.mos4j.core.Version.version();
    }

    /** 装配好的 Agent（可跑）。 */
    public Agent agent() {
        return agent;
    }

    /** 会话持久化目录（会话按 id 存取）。 */
    public JsonlPersistence persistence() {
        return persistence;
    }

    /**
     * 打开（或恢复）一个会话。
     *
     * @param id 会话 id（同 id 再次打开 = 恢复历史）
     */
    public Session session(String id) throws IOException {
        return JsonlPersistence.restore(id, persistence);
    }

    /**
     * 跑一轮（同 id 自动续聊）。
     *
     * <p><b>为什么门面要有这个</b>：「开会话 + 跑一轮」是最常见的用法，
     * 每次都写两行容易漏掉「同 id 续聊」的语义。
     *
     * @param sessionId 会话 id
     * @param input 输入文本
     * @return 模型回答
     * @throws IOException 会话读写失败
     */
    public String run(String sessionId, String input) throws IOException {
        return run(sessionId, input, CancellationToken.none());
    }

    /**
     * 跑一轮（同 id 自动续聊，带取消）。
     *
     * @param sessionId 会话 id
     * @param input 输入文本
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @return 模型回答
     * @throws IOException 会话读写失败
     */
    public String run(String sessionId, String input, CancellationToken cancellation) throws IOException {
        return agent.run(session(sessionId), input, cancellation);
    }

    /**
     * 续跑：把被中断的那一轮里的悬空调用接着走完（同 id 自动恢复历史）。
     *
     * <p><b>显式续跑</b>：框架不自动拾起挂起的轮次。宿主先用
     * {@code persistence()} 恢复会话、看 {@code session.hangingCalls()} 确认现场，
     * 再决定续不续。
     *
     * @param sessionId 会话 id
     * @param options   续跑参数（可空 = 自动选最后一个被打断的轮次）
     * @return 本轮最终回复文本
     * @throws net.hegq.mos4j.driver.NothingToResumeException 没有可续的悬空调用
     * @throws IOException 会话读写失败
     */
    public String resume(String sessionId, net.hegq.mos4j.driver.ResumeOptions options)
            throws IOException, net.hegq.mos4j.driver.NothingToResumeException {
        return resume(sessionId, options, CancellationToken.none());
    }

    /**
     * 续跑（带取消）。
     *
     * @param sessionId   会话 id
     * @param options     续跑参数（可空 = 自动选最后一个被打断的轮次）
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @return 本轮最终回复文本
     * @throws net.hegq.mos4j.driver.NothingToResumeException 没有可续的悬空调用
     * @throws IOException 会话读写失败
     */
    public String resume(String sessionId, net.hegq.mos4j.driver.ResumeOptions options,
                         CancellationToken cancellation)
            throws IOException, net.hegq.mos4j.driver.NothingToResumeException {
        return agent.resume(session(sessionId), options, cancellation);
    }

    /** 环境快照（测试注入用；生产从系统环境读）。 */
    Map<String, String> env() {
        return env;
    }

    /** 装配器。 */
    public static final class Builder {

        private Protocol protocol = Protocol.OPENAI;
        private String apiKey;
        private String baseUrl;
        private String model;
        private String systemPrompt;
        private PromptSections.Builder prompt;
        private Path logDir;
        private Path workspace;
        private boolean fileTools = true;
        private Map<String, String> env;
        private ChatModel explicitModel;
        private ToolRegistry explicitTools;
        private FileSystemBoundary explicitFileSystem;
        private SandboxBoundary explicitSandbox;
        private GuardBus guards;
        private net.hegq.mos4j.policy.ToolNamePolicy policy;
        private net.hegq.mos4j.policy.ParamPolicy paramPolicy;
        private List<net.hegq.mos4j.model.ModelOption> models;
        private String defaultModelId;
        private HookBus hooks;
        private ApprovalChannel approvals;
        private PostHook postHook;
        private ToolOutputLimiter outputLimiter;
        private CompactionService compaction;
        private net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy;
        private java.util.function.Consumer<Throwable> onCompactionFailure;
        private Duration summaryTimeout;
        private boolean disableCompaction;
        private Path contextFileDir;
        private List<String> contextFileCandidates;
        private ContextFileReader contextFileReader;
        private int contextFileByteBudget = ContextFiles.DEFAULT_BYTE_BUDGET;
        private int contextFileMaxDepth = ContextFiles.DEFAULT_MAX_DEPTH;
        private List<String> contextFileGlobalDirs;
        private int concurrency = 0;
        private int maxDecisionRounds = 0;
        private boolean stream;
        private List<Path> skillDirs;
        private int skillMaxBodyBytes;
        private int skillMaxCatalogBytes;
        private List<String> subAgentDirs;
        private net.hegq.mos4j.tool.subagent.SubAgentReader subAgentReader;
        private int subAgentMaxCatalogBytes;
        private Duration subAgentTimeout;
        private net.hegq.mos4j.tool.subagent.SubAgentRunner subAgentRunner;
        private PathScope explicitPathScope;
        private boolean pathScopeDisabled;
        private PathScopeProbe pathScopeProbe;
        private String pathScopeAbsentReason;
        private Boolean requireFinish;
        private Integer maxTokens;

        private Builder() {
        }

        /** 协议（缺省 OpenAI）。 */
        public Builder protocol(Protocol protocol) {
            this.protocol = protocol == null ? Protocol.OPENAI : protocol;
            return this;
        }

        /** API 密钥（缺省读协议对应的环境变量）。 */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** 基地址（缺省用协议官方地址；兼容网关在这里给）。 */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** 模型名（缺省读 {@code <PROVIDER>_MODEL}，再退协议常用名）。 */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** 系统提示词。 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 装系统提示词的<b>分节装配器</b>（与 {@link #systemPrompt(String)} 二选一）。
         *
         * <p>宿主用它把身份、约定、部署事实分节放进提示词；上下文文件、技能清单、
         * MCP 摘要这些框架分节装进<b>同一套</b>，位置由中心顺序统一分配
         * （不会因为「走的是整串」而落到末尾）。
         *
         * <p>框架在副本上装配：宿主手里那份保持原样。
         *
         * @param prompt 分节装配器
         * @return this
         */
        public Builder prompt(PromptSections.Builder prompt) {
            this.prompt = prompt;
            return this;
        }

        /** 会话日志目录（缺省 {@code .mos4j/sessions}）。 */
        public Builder logDir(Path logDir) {
            this.logDir = logDir;
            return this;
        }

        /** 工作目录（文件工具的 jail 锚点；缺省当前目录）。 */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /** 是否装配文件工具四件套（缺省 true）。 */
        public Builder fileTools(boolean fileTools) {
            this.fileTools = fileTools;
            return this;
        }

        /** 显式给模型（绕过协议装配；高级用法）。 */
        public Builder model(ChatModel model) {
            this.explicitModel = model;
            return this;
        }

        /**
         * 显式给工具注册表（覆盖缺省文件四件套）。
         *
         * <p><b>为什么要显式覆盖</b>：宿主要装自己的工具时，面面的缺省四件套就不该再装一遍
         * （工具名撞车会让模型面对两份同名工具）。给了它就完全用它的工具面。
         */
        public Builder tools(ToolRegistry tools) {
            this.explicitTools = tools;
            return this;
        }

        /**
         * 显式给文件边界快照（覆盖缺省文件工具的边界）。
         *
         * <p><b>为什么可以覆盖</b>：宿主自带工具面时，边界由宿主自己定；
         * Describe 只能如实报告「装了什么」——所以快照必须由实际边界生成，而不是猜的。
         */
        public Builder fileSystem(FileSystemBoundary fileSystem) {
            this.explicitFileSystem = fileSystem;
            return this;
        }

        /**
         * 显式给沙箱边界快照（覆盖缺省 shell 工具的边界）。
         */
        public Builder sandbox(SandboxBoundary sandbox) {
            this.explicitSandbox = sandbox;
            return this;
        }

        /**
         * 装守卫总线（安全边界；串行单调，首个拒绝即短路）。
         *
         * <p>不装 = 工具调用不经安全策略判定（Describe 会如实声明）。
         */
        public Builder guards(GuardBus guards) {
            this.guards = guards;
            return this;
        }

        /**
         * 装名字级策略（工具名 allow / deny 名单）。
         *
         * <p>装后两处生效：被拒的工具不进模型清单（可见性），运行时也调不动（守卫）。
         * 两处读同一份名单，所以不会出现「看得见却调不动」。
         *
         * @param policy 策略（可空 = 不收紧）
         * @return this
         */
        public Builder policy(net.hegq.mos4j.policy.ToolNamePolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * 装参数级规则（逐参数裁决 allow / ask / deny）。
         *
         * <p>与 {@link #policy} 的分工：{@code policy} 管名字级名单，本项管参数级规则。
         * 装上之后可在<b>运行期热替换</b>（{@code Agent.setRules(...)}）。
         *
         * @param paramPolicy 规则（可空 = 不裁决任何参数）
         * @return this
         */
        public Builder paramPolicy(net.hegq.mos4j.policy.ParamPolicy paramPolicy) {
            this.paramPolicy = paramPolicy;
            return this;
        }

        /**
         * 装钩子总线（扩展点；waterfall，载荷冻结）。
         *
         * <p>不装 = 扩展点链为空。
         */
        public Builder hooks(HookBus hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * 装审批通道（人工确认）。
         *
         * <p>不装 = 需要人确认的调用一律 fail-closed 拒绝（不是「默认批准」）。
         */
        public Builder approvals(ApprovalChannel approvals) {
            this.approvals = approvals;
            return this;
        }

        /**
         * 装执行后复核钩子（只能把成功改失败，不能洗白失败）。
         */
        public Builder postHook(PostHook postHook) {
            this.postHook = postHook;
            return this;
        }

        /**
         * 装工具输出限流（截断 + 落盘逃生通道）。
         *
         * <p>不装 = 工具输出不截断，单个大输出就可能撑爆上下文。
         */
        public Builder outputLimiter(ToolOutputLimiter outputLimiter) {
            this.outputLimiter = outputLimiter;
            return this;
        }

        /**
         * 装上下文文件（项目自己的规矩：{@code AGENTS.md} 之类）。
         *
         * <p>发现到的文件内容会拼进**系统提示词**（包装成 {@code <project_instructions path="...">} 块，
         * 模型据此知道每条约定来自哪个文件）；发现过程中的降级说明（被裁剪、被丢弃、
         * 读失败）会写进 {@code Describe} 的 caveats——<b>不静默丢内容</b>。
         *
         * <p>缺省候选名、字节预算、向上递归规则见 {@link ContextFiles}。
         * 需要读虚拟目录（非本地文件）时用 {@link #contextFileReader(ContextFileReader)} 注入自己的读取器。
         */
        public Builder contextFiles(Path dir) {
            this.contextFileDir = dir;
            return this;
        }

        /**
         * 装上下文文件并指定候选名（按优先级）。
         *
         * @param candidates 候选名（可空 = 用缺省）
         * @param dir        起始目录
         * @return this
         */
        public Builder contextFiles(List<String> candidates, Path dir) {
            this.contextFileCandidates = candidates;
            this.contextFileDir = dir;
            return this;
        }

        /**
         * 装上下文文件的读取器（缺省 = 读本地文件系统）。
         */
        public Builder contextFileReader(ContextFileReader reader) {
            this.contextFileReader = reader;
            return this;
        }

        /**
         * 设上下文文件的字节预算（缺省 {@link ContextFiles#DEFAULT_BYTE_BUDGET}；负数 = 不限）。
         */
        public Builder contextFileByteBudget(int bytes) {
            this.contextFileByteBudget = bytes;
            return this;
        }

        /**
         * 设向上递归层数（缺省 {@link ContextFiles#DEFAULT_MAX_DEPTH} = 只看起始目录本身；
         * 负数 = 不限，走到顶）。
         *
         * <p><b>为什么不缺省递归</b>：向上递归会把仓库根、家目录里的约定一起吃进来，
         * 那取决于「从哪启动」，不是宿主显式要的。
         */
        public Builder contextFileMaxDepth(int depth) {
            this.contextFileMaxDepth = depth;
            return this;
        }

        /**
         * 设全局目录（用户级约定；缺省 {@code ~/.mos}）。
         *
         * <p>全局约定是「所有项目共享的那部分」，缺省该有；宿主显式给了就以宿主为准（不追加）。
         * 路径可以写成 {@code ~/.mos}（自动展开）。
         *
         * @param dirs 全局目录（可空 = 用缺省）
         */
        public Builder contextFileGlobalDirs(List<String> dirs) {
            this.contextFileGlobalDirs = dirs;
            return this;
        }

        /**
         * 装上下文压缩（超预算时自动摘要并遮蔽旧历史）。
         *
         * <p>不装 = 长会话不会自动压缩。
         */
        public Builder compaction(CompactionService compaction) {
            this.compaction = compaction;
            return this;
        }

        /**
         * 装压缩失败策略（缺省 {@link net.hegq.mos4j.context.CompactionFailurePolicy#FAIL_CLOSED}）。
         *
         * <p>fail-closed：压缩失败 → 本轮以错误收尾（历史未被遮蔽）。
         * 跳过并继续：压缩失败 → 通知宿主（{@link #onCompactionFailure}）→ 本轮继续，
         * 上下文可能已超预算，<b>风险由宿主自担</b>。
         */
        public Builder compactionFailurePolicy(
                net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy) {
            this.compactionFailurePolicy = compactionFailurePolicy;
            return this;
        }

        /**
         * 装「压缩失败但本轮继续」的宿主通知（缺省 = 不通知）。
         *
         * <p>只在 {@code SKIP_AND_CONTINUE} 策略下被调用一次。
         */
        public Builder onCompactionFailure(java.util.function.Consumer<Throwable> onCompactionFailure) {
            this.onCompactionFailure = onCompactionFailure;
            return this;
        }

        /**
         * 设摘要调用自己的超时（缺省 3 分钟，与 Go 参考版同口径）。
         *
         * <p>{@link Duration#ZERO} = <b>不限</b>（宿主自担挂住的风险）。
         * 需要先 {@link #compaction(CompactionService)} 装压缩服务，否则本旋钮无效。
         */
        public Builder summaryTimeout(Duration summaryTimeout) {
            if (summaryTimeout != null && summaryTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "mos4j: 摘要超时不能为负（不限时用 Duration.ZERO）: " + summaryTimeout);
            }
            this.summaryTimeout = summaryTimeout;
            return this;
        }

        /**
         * 明确禁用上下文压缩（缺省 false）。
         *
         * <p>Go 参考版的 {@code DisableCompaction} 是「装配期不装压缩器」；
         * Java 侧压缩服务本来就是<b>显式可选</b>（不装 = 不压缩），
         * 所以这里只做一件事：把已装的压缩服务摘掉。
         *
         * <p><b>与同时设置 {@link #compaction(CompactionService)} 冲突</b> → 构建时 fail-fast 报错：
         * 「装配了又被悄悄摘掉」会让宿主以为自己有压缩，实际没有。
         */
        public Builder disableCompaction(boolean disableCompaction) {
            this.disableCompaction = disableCompaction;
            return this;
        }

        /** 工具并发上限（缺省 0 = 沿用驱动缺省；并发始终是显式 opt-in）。 */
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        /** 单轮决策次数上限（缺省 0 = 不限）。 */
        public Builder maxDecisionRounds(int maxDecisionRounds) {
            this.maxDecisionRounds = maxDecisionRounds;
            return this;
        }

        /** 是否走流式决策（缺省 false）。 */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /** 环境快照（测试注入；生产从系统环境读）。 */
        Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        /**
         * 技能目录（按优先级从高到低；同名先到先得）。
         *
         * <p><b>装配期扫一次</b>：清单进系统提示词（静态，保 prompt cache），正文按需加载。
         * 目录变了要写出 host 显式调 {@code agent().reloadSkills()}——框架不盯盘。
         *
         * <p><b>目录体积是宿主的责任</b>：不在工作区内、指错地方也不会报错
         * （读不动就是「没这个技能」，如实进 Describe 的降级说明）。
         *
         * @param dirs 技能目录（可空 = 不装技能）
         * @return this
         */
        public Builder skills(Path... dirs) {
            this.skillDirs = dirs == null ? List.of() : List.of(dirs);
            return this;
        }

        /**
         * 技能预算（≤0 = 沿用缺省：正文 64 KiB / 清单 8 KiB）。
         *
         * <p><b>为什么可调</b>：不同宿主的上下文预算差得远（小窗口网关 vs 长上下文服务）；
         * 硬编码只会逼宿主去改提示词，而不是改这里。
         *
         * @param maxBodyBytes    单个技能正文的字节上限
         * @param maxCatalogBytes 技能清单的字节上限
         * @return this
         */
        public Builder skillLimits(int maxBodyBytes, int maxCatalogBytes) {
            this.skillMaxBodyBytes = maxBodyBytes;
            this.skillMaxCatalogBytes = maxCatalogBytes;
            return this;
        }

        /**
         * 装子代理目录（每个子代理一个 {@code subagent__<name>} 工具）。
         *
         * <p><b>目录形态</b>：目录自己含 {@code AGENT.md} → 它就是子代理根；
         * 否则它的每个直接子目录（含 {@code AGENT.md}）是一个子代理。
         *
         * <p><b>顺序即优先级</b>：同名子代理先到先得（后来的被遮蔽并进 Describe 的降级说明）。
         *
         * <p><b>目录列表就是授权边界</b>：框架只读这些目录里声明的东西。
         *
         * @param dirs 子代理目录（可空 = 不装子代理）
         * @return this
         */
        public Builder subAgents(Path... dirs) {
            this.subAgentDirs = dirs == null ? List.of() : List.of(dirs).stream()
                    .map(Path::toString).toList();
            return this;
        }

        /**
         * 装子代理目录（字符串形态；给不方便用 {@link Path} 的宿主）。
         *
         * @param dirs 子代理目录（可空 = 不装子代理）
         * @return this
         */
        public Builder subAgentDirs(List<String> dirs) {
            this.subAgentDirs = dirs == null ? List.of() : List.copyOf(dirs);
            return this;
        }

        /**
         * 装子代理目录的读法（可空 = 直接读本地文件）。
         *
         * <p><b>框架不碰文件系统</b>：目录列表本身就是宿主声明的授权范围；
         * 宿主要 jail 语义就注入自己的读法。
         *
         * @param reader 读法（可空 = 缺省）
         * @return this
         */
        public Builder subAgentReader(net.hegq.mos4j.tool.subagent.SubAgentReader reader) {
            this.subAgentReader = reader;
            return this;
        }

        /**
         * 子代理清单的字节上限（≤0 = 缺省 8 KiB）。
         *
         * @param maxCatalogBytes 清单字节上限
         * @return this
         */
        public Builder subAgentMaxCatalogBytes(int maxCatalogBytes) {
            this.subAgentMaxCatalogBytes = maxCatalogBytes;
            return this;
        }

        /**
         * 单次子代理调用的上限（缺省 5 分钟；{@link Duration#ZERO} = 不限）。
         *
         * <p>超时不是父轮次的错误，而是一段 {@code status: timeout} 的正常回执。
         *
         * @param timeout 超时（不能为负）
         * @return this
         */
        public Builder subAgentTimeout(Duration timeout) {
            if (timeout != null && timeout.isNegative()) {
                throw new IllegalArgumentException("mos4j: 子代理超时不能为负");
            }
            this.subAgentTimeout = timeout;
            return this;
        }

        /**
         * 装宿主自备的子代理执行体（可空 = 缺省：独立子会话 + 白名单子注册表）。
         *
         * @param runner 执行体（可空 = 缺省）
         * @return this
         */
        public Builder subAgentRunner(net.hegq.mos4j.tool.subagent.SubAgentRunner runner) {
            this.subAgentRunner = runner;
            return this;
        }

        /**
         * 显式给路径强制（覆盖缺省派生）。
         *
         * <p>缺省是从工作目录派生（写根 = 工作目录）并随文件 jail 的根集合动态变化；
         * 宿主想自己定义写根时用它。
         *
         * <p>仍然会<b>探一次可用性</b>：宿主明确要求了这层保护，而这台机器用不了时，
         * 应该在装配期就报错，而不是让每条命令运行期失败。
         *
         * @param pathScope 路径强制
         * @return this
         */
        public Builder pathScope(PathScope pathScope) {
            this.explicitPathScope = pathScope;
            this.pathScopeDisabled = false;
            return this;
        }

        /**
         * 显式不装路径强制（宿主自己知道这台机器没有沙箱可用）。
         *
         * <p>这不是静默降级：Describe 的 caveat 会写明「路径强制=未装配（原因：宿主显式关闭）」，
         * 宿主与模型都能看到「shell 命令不受路径强制保护」。
         *
         * @return this
         */
        public Builder noPathScope() {
            this.pathScopeDisabled = true;
            this.explicitPathScope = null;
            return this;
        }

        /**
         * 注入路径强制的可用性探针（缺省 = 真探一次）。
         *
         * <p>给两种场景：受管环境里 {@code sandbox-exec} 存在但不许 apply（真实探测会报不可用），
         * 以及测试（不想每次装配都起一个进程）。
         *
         * @param probe 探针
         * @return this
         */
        public Builder pathScopeProbe(PathScopeProbe probe) {
            this.pathScopeProbe = probe;
            return this;
        }

        /**
         * Gemini 结束原因逃生阀（可空 = 缺省，即要求结束原因）。
         *
         * <p>对齐 Go 的 {@code Config["require_finish"]="false"}：有些网关不报 {@code finishReason}，
         * 严格要求会把正常回答当成截断（假失败）。关掉它 = 「没结束原因就当结束」。
         *
         * <p><b>只对 Gemini 生效</b>：别的协议显式设置会直接报错（fail-fast）——
         * 静默忽略一个没生效的开关，比报错更难查。
         *
         * @param requireFinish 是否要求结束原因
         * @return this
         */
        public Builder requireFinish(Boolean requireFinish) {
            this.requireFinish = requireFinish;
            return this;
        }

        /**
         * 输出 token 上限（可空 = 用协议缺省）。
         *
         * <p>对齐 Go 的 {@code Config["max_tokens"]}（模型级配置）：Anthropic 的
         * {@code max_tokens} 是必填字段，Java 侧缺省用 4096 兜底；宿主想调就用这里。
         *
         * @param maxTokens token 上限
         * @return this
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 装模型目录（可在运行期换模型；与 {@link #model(ChatModel)} <b>二选一</b>）。
         *
         * <p>目录里的每一项是一个候选模型；换模型在<b>下一轮</b>生效，不重建智能体。
         * 凭据只留在模型对象里——Describe 快照里只出现 ID 与展示名。
         *
         * @param models 目录项（保序）
         * @return this
         */
        public Builder models(List<net.hegq.mos4j.model.ModelOption> models) {
            this.models = models;
            return this;
        }

        /**
         * 指定默认模型（必须在目录里；空 = 用第一项）。
         *
         * @param defaultModelId 模型 ID
         * @return this
         */
        public Builder defaultModelId(String defaultModelId) {
            this.defaultModelId = defaultModelId;
            return this;
        }

        /** 完成装配。 */
        public Mos4j build() throws IOException {
            Map<String, String> environment = env != null ? env : System.getenv();
            if (models != null && !models.isEmpty() && explicitModel != null) {
                throw new IllegalStateException(
                        "mos4j: model 与 models 二选一（两个都给，不知道听谁的）");
            }
            ChatModel model = null;
            if (models == null || models.isEmpty()) {
                if (explicitModel != null) {
                    if (requireFinish != null) {
                        throw new IllegalStateException(
                                "mos4j: requireFinish 只对门面自装的 Gemini 模型生效；"
                                        + "当前用的是宿主自带的模型（model(ChatModel)），它自己管这个开关。");
                    }
                    model = explicitModel;
                } else {
                    model = defaultModel(environment);
                }
            }
            JsonlPersistence persistence = new JsonlPersistence(
                    logDir != null ? logDir : Path.of(".mos4j", "sessions"));

            // 压缩三旋钮的装配口径：
            //  - disableCompaction 与 compaction 同时给 → fail-fast（不能「装了又悄悄摘掉」）；
            //  - summaryTimeout 只在装了压缩服务时生效（没压缩就没有摘要可限时）。
            CompactionService effectiveCompaction = compaction;
            if (disableCompaction) {
                if (compaction != null) {
                    throw new IllegalStateException(
                            "mos4j: disableCompaction(true) 与 compaction(...) 冲突："
                                    + "压缩服务已经装了又被禁用，不确定你要哪个（fail-closed）。");
                }
                effectiveCompaction = null;
            } else if (compaction != null && summaryTimeout != null) {
                effectiveCompaction = compaction.withSummaryTimeout(summaryTimeout);
            }

            // 上下文文件：装配期发现一次，内容拼进系统提示词，降级说明进 Describe caveats。
            // 发现不到任何文件不是故障（项目可能就没写约定文件）——但被裁剪/丢弃的必须写进 caveats，
            // 静默丢内容 = 模型以为项目没这条规矩。
            String effectiveSystemPrompt = systemPrompt;
            PromptSections.Builder effectivePrompt = prompt;
            if (systemPrompt != null && !systemPrompt.isBlank() && prompt != null) {
                // 两个都给 = 不知道听谁的（fail-closed）。空串按「没给」处理。
                throw new IllegalStateException(
                        "mos4j: systemPrompt 与 prompt 二选一（两个都给，不知道听谁的）");
            }
            List<String> contextFileWarnings = List.of();
            int contextFileCount = 0;
            int contextFileBytes = 0;
            String projectContext = "";
            if (contextFileDir != null) {
                ContextFileReader reader =
                        contextFileReader != null ? contextFileReader : new LocalContextFileReader();
                ContextFiles.Options options = new ContextFiles.Options()
                        .candidates(contextFileCandidates)
                        .byteBudget(contextFileByteBudget)
                        .maxDepth(contextFileMaxDepth)
                        // 宿主没显式给全局目录 = 用 MoS 自己的（~/.mos）：
                // 全局约定是「所有项目共享的那部分」，缺省该有。
                        .globalDirs(contextFileGlobalDirs != null
                                ? contextFileGlobalDirs
                                : List.of("~/" + ContextFiles.DEFAULT_GLOBAL_DIR_NAME));
                ContextFiles found =
                        ContextFiles.discover(contextFileDir.toString(), options, reader);
                String contextText = found.render();
                projectContext = contextText;
                if (effectivePrompt != null) {
                    // 有分节装配器：上下文文件进「context」段（中心顺序：约定之后、工具指引之前）。
                    PromptSections.Builder copy = effectivePrompt.clone();
                    if (!contextText.isEmpty()) {
                        if (copy.has("context")) {
                            throw new IllegalStateException(
                                    "mos4j: 提示词里已经有 context 段（宿主已放了项目上下文），框架不再重复装");
                        }
                        copy.add("context", contextText);
                    }
                    effectivePrompt = copy;
                } else {
                    effectiveSystemPrompt = appendSection(effectiveSystemPrompt, contextText);
                }
                contextFileWarnings = found.warnings();
                contextFileCount = found.files().size();
                contextFileBytes = found.textBytes();
            }
            final List<String> warnings = contextFileWarnings;
            final int fileCount = contextFileCount;
            final int byteCount = contextFileBytes;

            Config.Builder config = Config.builder()
                    .model(model)
                    .systemPrompt(effectiveSystemPrompt)
                    .prompt(effectivePrompt)
                    .concurrency(concurrency)
                    .maxDecisionRounds(maxDecisionRounds)
                    .stream(stream)
                    .guards(guards)
                    .hooks(hooks)
                    .approvals(approvals)
                    .postHook(postHook)
                    .outputLimiter(outputLimiter)
                    .compaction(effectiveCompaction)
                    .compactionFailurePolicy(compactionFailurePolicy)
                    .onCompactionFailure(onCompactionFailure)
                    .maxTokens(maxTokens)
                    .policy(policy)
                    .paramPolicy(paramPolicy)
                    .models(models)
                    .defaultModelId(defaultModelId)
                    .contextText(projectContext);
            if (contextFileDir != null) {
                config.describeContributor((features, caveats) -> {
                    for (String warning : warnings) {
                        caveats.add("上下文文件: " + warning);
                    }
                    if (fileCount > 0) {
                        features.add("上下文文件: " + fileCount + " 个（" + byteCount + " 字节）");
                    }
                });
            }
            // 技能：装配期扫一次，清单进系统提示词、工具条件注册（都在 Agent.of 里接）。
            // 一条都装不上时不拦启动：降级说明进 Describe（装不上的技能不静默消失）。
            SkillCatalog skills = null;
            if (skillDirs != null && !skillDirs.isEmpty()) {
                skills = SkillCatalog.load(SkillCatalog.Options
                        .of(skillDirs.stream().map(Path::toString).toList())
                        .limits(skillMaxBodyBytes, skillMaxCatalogBytes));
                config.skills(skills);
            }
            // 子代理：装配期扫一次（清单固定，不做运行期热加载）。
            // 模型覆盖的校验接**模型目录**：声明了却不在目录里 → 装配期报错
            // （声明了却没装 = 假能力，不静默降级到父模型）。
            if (subAgentDirs != null && !subAgentDirs.isEmpty()) {
                final List<net.hegq.mos4j.model.ModelOption> catalogModels =
                        models == null ? List.of() : List.copyOf(models);
                net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents =
                        net.hegq.mos4j.tool.subagent.SubAgentCatalog.load(
                                new net.hegq.mos4j.tool.subagent.SubAgentCatalog.Options(
                                        subAgentDirs, subAgentReader, subAgentMaxCatalogBytes,
                                        modelId -> catalogModels.stream()
                                                .anyMatch(option -> option.id().equals(modelId))));
                config.subAgents(subAgents);
                if (subAgentTimeout != null) {
                    config.subAgentTimeout(subAgentTimeout);
                }
                if (subAgentRunner != null) {
                    config.subAgentRunner(subAgentRunner);
                }
                // 子会话用**同一个 JSONL 后端**：子代理干了什么，宿主查得到。
                config.subAgentSessions(sessionId -> new net.hegq.mos4j.session.Session(sessionId, persistence));
                final List<String> subAgentWarnings = subAgents.warnings();
                config.describeContributor((features, caveats) -> {
                    features.add("子代理: " + subAgents.len() + " 个（" + String.join("、", subAgents.names()) + "）");
                    for (String warning : subAgentWarnings) {
                        caveats.add("子代理: " + warning);
                    }
                });
            }
            // 边界快照必须如实写进 Config：Describe 靠它回答「装了什么、没装什么」。
            // 不写的话，工具明明被 jail 着，Describe 却说「未装配文件 jail」——那是假话。
            FileSystemBoundary fileSystem = explicitFileSystem;
            SandboxBoundary sandbox = explicitSandbox;
            if (explicitTools != null) {
                config.tools(explicitTools);
            } else if (fileTools) {
                FileTools tools = fileTools();
                config.tools(tools.registry());
                // 根集合交给 Config：运行期增删可访问根（Agent.addRoot/removeRoot）靠它。
                config.roots(tools.roots());
                if (fileSystem == null) {
                    fileSystem = tools.fileSystem();
                }
                if (sandbox == null) {
                    sandbox = tools.sandbox();
                }
            }
            if (fileSystem != null) {
                config.fileSystem(fileSystem);
            }
            if (sandbox != null) {
                config.sandbox(sandbox);
            }
            return new Mos4j(Agent.of(config.build()), persistence,
                    Map.copyOf(environment.size() > 0 ? environment : Map.of()));
        }

        /** 缺省模型装配（协议 → 环境变量 → 官方地址）。 */
        private ChatModel defaultModel(Map<String, String> environment) {
            // 协议校验在 Mos4j.defaultModel(...) 里（唯一真源：starter 也走那条路）
            return Mos4j.defaultModel(protocol, apiKey, baseUrl, model, environment, requireFinish);
        }

        /** 缺省文件工具装配（注册表 + 两个边界快照，jail 锚在工作目录）。 */
        private FileTools fileTools() throws IOException {
            Path anchor = workspace != null ? workspace : Path.of("").toAbsolutePath();
            net.hegq.mos4j.fs.RootSet roots = RootSet.of(anchor);
            SandboxedFs fs = new SandboxedFs(roots);
            net.hegq.mos4j.fs.ObserveGate gate = new net.hegq.mos4j.fs.ObserveGate();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new net.hegq.mos4j.tool.builtin.ReadFileTool(fs, gate));
            registry.register(new net.hegq.mos4j.tool.builtin.WriteFileTool(fs, gate));
            registry.register(new net.hegq.mos4j.tool.builtin.EditFileTool(fs, gate));
            LocalRunner.Builder runnerBuilder = LocalRunner.builder(anchor);
            // 路径强制：有**已验证**的平台后端就默认生效（写根 = 工作目录），
            // 不是「宿主可选打开的功能」；装之前先探一次这台机器能不能真用起来。
            PathScope scope = resolvePathScope(anchor);
            if (scope != null) {
                if (explicitPathScope == null) {
                    // 缺省派生时，写根跟着**文件 jail 的活根集合**走（对齐 Go 的 Local.Scope）：
                    // 会话里授权了新目录，shell 跟着能写——不出现「工具能写、命令写不了」的错位。
                    // 只给 supplier、不给静态 scope：静态 scope 的写根是拍死的，
                    // 快照（Describe）会照着拍死的那份说，跟真实边界对不上。
                    // 只送**可写**根：只读根（能读不能写）进了写范围，
                    // 等于 shell 获得了文件工具明文拒绝的写权限——两个边界对不上。
                    runnerBuilder.pathScopeSupplier(() -> roots.roots().stream()
                            .filter(net.hegq.mos4j.fs.Root::writable)
                            .map(net.hegq.mos4j.fs.Root::path).toList());
                } else {
                    runnerBuilder.pathScope(scope);
                }
            } else {
                runnerBuilder.noPathScope(pathScopeAbsentReason);
            }
            LocalRunner runner = runnerBuilder.build();
            registry.register(new net.hegq.mos4j.tool.builtin.ShellTool(runner));
            return new FileTools(registry, FileSystemBoundary.of(fs), SandboxBoundary.of(runner), roots);
        }

        /**
         * 解析要装的路径强制（null = 不装）。
         *
         * <p>三条分支：宿主显式给（用它）、宿主显式不要（不装，并写明原因）、
         * 缺省（平台有**已验证**的后端就装，并先探一次可用性）。
         *
         * @param anchor 工作目录（缺省派生的基准）
         * @return 路径强制（null = 不装）
         */
        private PathScope resolvePathScope(Path anchor) {
            if (pathScopeDisabled) {
                pathScopeAbsentReason = "宿主显式关闭（Mos4j.builder().noPathScope()）";
                return null;
            }
            if (explicitPathScope != null) {
                requirePathScopeUsable();
                return explicitPathScope;
            }
            if (!PathScopes.supported()) {
                // 平台没有（已验证的）后端：caveat 里另有「平台后端状态」说明
                pathScopeAbsentReason = null;
                return null;
            }
            requirePathScopeUsable();
            return PathScopes.derive(anchor);
        }

        /**
         * 探一次路径强制能不能真的用起来；不通就装配期报错（fail-closed）。
         *
         * <p><b>为什么在装配期就报</b>：运行期才发现的话，模型在第 N 轮说「命令失败了」，
         * 宿主拿到的是一次异常回执，而不是「这台机器不允许应用沙箱」这件可以立刻处理的事。
         */
        private void requirePathScopeUsable() {
            PathScopeProbe probe = pathScopeProbe != null ? pathScopeProbe : PathScopes::probe;
            PathScopeProbe.Result result = probe.probe();
            if (!result.available()) {
                throw new IllegalStateException(
                        "mos4j: 路径强制不可用：" + result.detail()
                                + "。两条出路：①换一台允许应用沙箱的 macOS；"
                                + "②显式 Mos4j.builder().noPathScope()（此时 shell 命令不受路径强制保护，"
                                + "Describe 会如实声明）。不会退化成「没隔离的假装隔离」。");
            }
        }

        /**
         * 把一段内容追加到系统提示词后面（空内容不动）。
         *
         * <p>分隔符是空行：项目约定与宿主自己的提示词之间要能一眼分开，
         * 否则模型会把两者当成一段连续的话读。
         */
        private static String appendSection(String systemPrompt, String section) {
            if (section == null || section.isEmpty()) {
                return systemPrompt;
            }
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                return section;
            }
            return systemPrompt + "\n\n" + section;
        }

        /** 缺省文件工具的装配结果（注册表 + 两个边界快照 + 根集合）。 */
        private record FileTools(ToolRegistry registry,
                                 FileSystemBoundary fileSystem,
                                 SandboxBoundary sandbox,
                                 RootSet roots) {
        }
    }
}
