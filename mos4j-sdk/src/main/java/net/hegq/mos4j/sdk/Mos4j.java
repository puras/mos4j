package net.hegq.mos4j.sdk;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.protocol.anthropic.AnthropicChatModel;
import net.hegq.mos4j.llm.protocol.anthropic.AnthropicConfig;
import net.hegq.mos4j.llm.protocol.gemini.GeminiChatModel;
import net.hegq.mos4j.llm.protocol.gemini.GeminiConfig;
import net.hegq.mos4j.llm.protocol.openai.OpenAiChatModel;
import net.hegq.mos4j.llm.protocol.openai.OpenAiConfig;
import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.SandboxBoundary;
import net.hegq.mos4j.session.JsonlPersistence;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.SkillCatalog;

import java.io.IOException;
import java.nio.file.Path;
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
 * 工具面是宿主的决定，门面不替宿主扩大能力。</p>
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
        Map<String, String> environment = env == null ? Map.of() : env;
        Protocol effective = protocol == null ? Protocol.OPENAI : protocol;
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
                yield new GeminiChatModel(GeminiConfig.of(key, base, name));
            }
        };
    }

    /** 开始装配。 */
    public static Builder builder() {
        return new Builder();
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
        private Path logDir;
        private Path workspace;
        private boolean fileTools = true;
        private Map<String, String> env;
        private ChatModel explicitModel;
        private ToolRegistry explicitTools;
        private FileSystemBoundary explicitFileSystem;
        private SandboxBoundary explicitSandbox;
        private GuardBus guards;
        private HookBus hooks;
        private ApprovalChannel approvals;
        private PostHook postHook;
        private ToolOutputLimiter outputLimiter;
        private CompactionService compaction;
        private int concurrency = 0;
        private int maxDecisionRounds = 0;
        private boolean stream;
        private List<Path> skillDirs;
        private int skillMaxBodyBytes;
        private int skillMaxCatalogBytes;

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
         * 装上下文压缩（超预算时自动摘要并遮蔽旧历史）。
         *
         * <p>不装 = 长会话不会自动压缩。
         */
        public Builder compaction(CompactionService compaction) {
            this.compaction = compaction;
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

        /** 完成装配。 */
        public Mos4j build() throws IOException {
            Map<String, String> environment = env != null ? env : System.getenv();
            ChatModel model = explicitModel != null ? explicitModel
                    : defaultModel(environment);
            JsonlPersistence persistence = new JsonlPersistence(
                    logDir != null ? logDir : Path.of(".mos4j", "sessions"));

            Config.Builder config = Config.builder()
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .concurrency(concurrency)
                    .maxDecisionRounds(maxDecisionRounds)
                    .stream(stream)
                    .guards(guards)
                    .hooks(hooks)
                    .approvals(approvals)
                    .postHook(postHook)
                    .outputLimiter(outputLimiter)
                    .compaction(compaction);
            // 技能：装配期扫一次，清单进系统提示词、工具条件注册（都在 Agent.of 里接）。
            // 一条都装不上时不拦启动：降级说明进 Describe（装不上的技能不静默消失）。
            SkillCatalog skills = null;
            if (skillDirs != null && !skillDirs.isEmpty()) {
                skills = SkillCatalog.load(SkillCatalog.Options
                        .of(skillDirs.stream().map(Path::toString).toList())
                        .limits(skillMaxBodyBytes, skillMaxCatalogBytes));
                config.skills(skills);
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
            return Mos4j.defaultModel(protocol, apiKey, baseUrl, model, environment);
        }

        /** 缺省文件工具装配（注册表 + 两个边界快照，jail 锚在工作目录）。 */
        private FileTools fileTools() throws IOException {
            Path anchor = workspace != null ? workspace : Path.of("").toAbsolutePath();
            SandboxedFs fs = new SandboxedFs(RootSet.of(anchor));
            net.hegq.mos4j.fs.ObserveGate gate = new net.hegq.mos4j.fs.ObserveGate();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new net.hegq.mos4j.tool.builtin.ReadFileTool(fs, gate));
            registry.register(new net.hegq.mos4j.tool.builtin.WriteFileTool(fs, gate));
            registry.register(new net.hegq.mos4j.tool.builtin.EditFileTool(fs, gate));
            LocalRunner.Builder runnerBuilder = LocalRunner.builder(anchor);
            // 路径强制：与 Go 对齐——有后端就默认生效（写根 = 工作目录），
            // 不是「宿主可选打开的功能」；无后端的平台如实不装配（Describe 会写明）
            if (net.hegq.mos4j.sandbox.PathScopes.supported()) {
                runnerBuilder.pathScope(net.hegq.mos4j.sandbox.PathScopes.derive(anchor));
            }
            LocalRunner runner = runnerBuilder.build();
            registry.register(new net.hegq.mos4j.tool.builtin.ShellTool(runner));
            return new FileTools(registry, FileSystemBoundary.of(fs), SandboxBoundary.of(runner));
        }

        /** 缺省文件工具的装配结果（注册表 + 两个边界快照）。 */
        private record FileTools(ToolRegistry registry,
                                 FileSystemBoundary fileSystem,
                                 SandboxBoundary sandbox) {
        }
    }
}
