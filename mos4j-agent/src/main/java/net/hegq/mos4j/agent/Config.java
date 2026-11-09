package net.hegq.mos4j.agent;

import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.core.DescribeContributor;
import net.hegq.mos4j.execpath.ExecPath;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.sandbox.SandboxBoundary;
import net.hegq.mos4j.tool.skill.SkillCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 装配配置（宿主侧一次装配，之后只读）。
 *
 * <p><b>为什么用显式配置而不是自动发现</b>：框架必须能回答「装了什么、没装什么」
 * （{@link Describe} 快照）。自动发现让这件事无法回答——「我到底装了几个工具」
 * 变成运行时才知道的事实，出问题时无法复现。</p>
 */
public final class Config {

    private final ChatModel model;
    private final net.hegq.mos4j.tool.ToolRegistry tools;
    private final String systemPrompt;
    private final net.hegq.mos4j.context.PromptSections.Builder prompt;
    private final int concurrency;
    private final int maxDecisionRounds;
    private final boolean stream;
    private final GuardBus guards;
    private final HookBus hooks;
    private final ApprovalChannel approvals;
    private final PostHook postHook;
    private final FileSystemBoundary fileSystem;
    private final net.hegq.mos4j.fs.RootSet roots;
    private final SandboxBoundary sandbox;
    private final CompactionService compaction;
    private final net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy;
    private final java.util.function.Consumer<Throwable> onCompactionFailure;
    private final ToolOutputLimiter outputLimiter;
    private final SkillCatalog skills;
    private final net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents;
    private final net.hegq.mos4j.tool.subagent.SubAgentRunner subAgentRunner;
    private final java.time.Duration subAgentTimeout;
    private final net.hegq.mos4j.agent.subagent.SubAgentSessionFactory subAgentSessions;
    private final String contextText;
    private final Integer defaultMaxTokens;
    private final java.util.function.Supplier<String> mcpInstructions;
    private final net.hegq.mos4j.policy.ToolNamePolicy policy;
    private final PolicySlot policySlot = new PolicySlot();
    private final ParamPolicySlot paramPolicySlot = new ParamPolicySlot();
    private final boolean paramPolicyInstalled;
    private final java.util.List<net.hegq.mos4j.model.ModelOption> models;
    private final String defaultModelId;
    private final ModelCatalog modelCatalog;
    private final java.util.List<DescribeContributor> describeContributors;
    private final java.util.List<AutoCloseable> closers;

    private Config(Builder builder) {
        this.models = builder.models == null ? java.util.List.of() : java.util.List.copyOf(builder.models);
        this.defaultModelId = builder.defaultModelId;
        if (!this.models.isEmpty() && builder.model != null) {
            // 两个都给 = 不知道听谁的：不静默挑一个（fail-closed）。
            throw new IllegalArgumentException(
                    "mos4j: model 与 models 二选一（两个都给，不知道听谁的）");
        }
        if (!this.models.isEmpty()) {
            this.modelCatalog = new ModelCatalog(this.models, this.defaultModelId);
            this.model = this.modelCatalog.shell();
        } else {
            this.modelCatalog = null;
            this.model = builder.model;
        }
        this.tools = builder.tools;
        this.systemPrompt = builder.systemPrompt;
        this.prompt = builder.prompt;
        if (this.systemPrompt != null && !this.systemPrompt.isBlank() && this.prompt != null) {
            // 两个都给 = 不知道听谁的：不静默挑一个（fail-closed）。
            // 分节装配器与整串提示词都能当系统提示词用，但同时给就是歧义。
            // 空串按「没给」处理（与 Go 侧口径一致）。
            throw new IllegalArgumentException(
                    "mos4j: systemPrompt 与 prompt 二选一（两个都给，不知道听谁的）");
        }
        this.concurrency = builder.concurrency;
        this.maxDecisionRounds = builder.maxDecisionRounds;
        this.stream = builder.stream;
        this.guards = builder.guards;
        this.hooks = builder.hooks;
        this.approvals = builder.approvals;
        this.postHook = builder.postHook;
        this.fileSystem = builder.fileSystem;
        this.roots = builder.roots;
        this.sandbox = builder.sandbox;
        this.compaction = builder.compaction;
        this.compactionFailurePolicy = builder.compactionFailurePolicy == null
                ? net.hegq.mos4j.context.CompactionFailurePolicy.FAIL_CLOSED
                : builder.compactionFailurePolicy;
        this.onCompactionFailure = builder.onCompactionFailure;
        this.outputLimiter = builder.outputLimiter;
        this.skills = builder.skills;
        this.subAgents = builder.subAgents;
        this.subAgentRunner = builder.subAgentRunner;
        this.subAgentTimeout = builder.subAgentTimeout;
        this.subAgentSessions = builder.subAgentSessions;
        this.contextText = builder.contextText;
        this.defaultMaxTokens = builder.defaultMaxTokens;
        this.mcpInstructions = builder.mcpInstructions;
        this.policy = builder.policy;
        this.paramPolicyInstalled = builder.paramPolicy != null;
        if (builder.paramPolicy != null) {
            paramPolicySlot.install(builder.paramPolicy);
        }
        this.describeContributors = builder.describeContributors == null
                ? java.util.List.of() : java.util.List.copyOf(builder.describeContributors);
        this.closers = builder.closers == null ? java.util.List.of() : java.util.List.copyOf(builder.closers);
        if (policy != null) {
            policySlot.install(policy);
        }
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 模型（必填）。 */
    public ChatModel model() {
        return model;
    }

    /** 工具注册表（可空 = 未装配工具）。 */
    public net.hegq.mos4j.tool.ToolRegistry tools() {
        return tools;
    }

    /** 系统引导词（可空；与 {@link #prompt()} 二选一）。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 系统提示词的分节装配器（可空 = 用 {@link #systemPrompt()} 整串）。
     *
     * <p><b>为什么要注入装配器而不是拼好的一整串</b>：整串提示词只能整段替换，
     * 框架自己的分节（技能清单、MCP 摘要、子代理清单）只能靠字符串插入找位置；
     * 注入装配器之后，框架与宿主共用同一套<b>中心顺序</b>，
     * 分节位置由 {@link net.hegq.mos4j.context.PromptSections#CANONICAL_ORDER} 统一分配。
     *
     * @return 分节装配器（可空）
     */
    public net.hegq.mos4j.context.PromptSections.Builder prompt() {
        return prompt;
    }

    /** 并发上限。 */
    public int concurrency() {
        return concurrency;
    }

    /** 单轮决策次数上限。 */
    public int maxDecisionRounds() {
        return maxDecisionRounds;
    }

    /** 是否走流式决策。 */
    public boolean stream() {
        return stream;
    }

    /** 守卫总线（可空 = 未装配守卫）。 */
    public GuardBus guards() {
        return guards;
    }

    /** 钩子总线（可空 = 未装配钩子）。 */
    public HookBus hooks() {
        return hooks;
    }

    /** 审批通道（可空 = 未装配；需要人工确认的调用会 fail-closed 拒绝）。 */
    public ApprovalChannel approvals() {
        return approvals;
    }

    /** 执行后复核钩子（可空）。 */
    public PostHook postHook() {
        return postHook;
    }

    /** 文件边界快照（可空 = 未装配文件 jail）。 */
    public FileSystemBoundary fileSystem() {
        return fileSystem;
    }

    /**
     * 可管理的根集合（可空 = 未装配）。
     *
     * <p>有它才能在运行期增删可访问根（{@code Agent.addRoot/removeRoot}）；
     * 宿主自带文件边界快照时没有它——那时增删根会明确报错，不假装能管。
     */
    public net.hegq.mos4j.fs.RootSet roots() {
        return roots;
    }

    /** 沙箱边界快照（可空 = 未装配命令沙箱）。 */
    public SandboxBoundary sandbox() {
        return sandbox;
    }

    /** 上下文压缩服务（可空 = 未装配压缩）。 */
    public CompactionService compaction() {
        return compaction;
    }

    /**
     * 压缩失败策略（缺省 {@link net.hegq.mos4j.context.CompactionFailurePolicy#FAIL_CLOSED}）。
     *
     * <p>fail-closed：压缩失败 → 本轮以错误收尾（历史未被遮蔽）。
     */
    public net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy() {
        return compactionFailurePolicy;
    }

    /**
     * 「压缩失败但本轮继续」时的宿主通知（可空 = 不通知）。
     *
     * <p>只在跳过并继续的策略下被调用一次。
     */
    public java.util.function.Consumer<Throwable> onCompactionFailure() {
        return onCompactionFailure;
    }

    /** 工具输出限流器（可空 = 未装配限流）。 */
    public ToolOutputLimiter outputLimiter() {
        return outputLimiter;
    }

    /**
     * 技能清单（可空 = 未装配技能）。
     *
     * <p><b>装配期决定</b>：有模型可见技能时才注册 {@code skill} 工具（能力即证据——
     * 一条都装不了的技能目录不该在工具面上占位）。
     *
     * @return 技能清单
     */
    public SkillCatalog skills() {
        return skills;
    }

    /**
     * 子代理清单（可空 = 未装子代理能力）。
     *
     * <p><b>装配期决定</b>：清单非空时才给每个子代理注册一个 {@code subagent__<name>} 工具
     * （能力即证据——没有子代理就不给模型一把空枪）。
     *
     * @return 子代理清单
     */
    public net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents() {
        return subAgents;
    }

    /**
     * 宿主自备的子代理执行体（可空 = 用缺省实现）。
     *
     * @return 执行体
     */
    public net.hegq.mos4j.tool.subagent.SubAgentRunner subAgentRunner() {
        return subAgentRunner;
    }

    /**
     * 单次子代理调用的上限（可空 = 缺省 5 分钟）。
     *
     * @return 超时
     */
    public java.time.Duration subAgentTimeout() {
        return subAgentTimeout;
    }

    /**
     * 子会话工厂（可空 = 用父会话同一个持久化后端）。
     *
     * @return 子会话工厂
     */
    public net.hegq.mos4j.agent.subagent.SubAgentSessionFactory subAgentSessions() {
        return subAgentSessions;
    }

    /**
     * 项目上下文正文（可空 = 没有）。
     *
     * <p>子代理也在这个项目里干活，所以它的系统提示词里也要带这一段
     * （父的项目约定对子代理同样成立）。
     *
     * @return 项目上下文正文
     */
    public String contextText() {
        return contextText;
    }

    /**
     * 输出的 token 上限缺省（可空 = 用协议自己的缺省）。
     *
     * <p>模型级配置（对齐 Go 的 {@code Config["max_tokens"]}）：每个请求都带同一个上限。
     * Anthropic 的 {@code max_tokens} 是必填字段——不配就用协议侧的兜底值。
     *
     * @return token 上限
     */
    public Integer defaultMaxTokens() {
        return defaultMaxTokens;
    }

    /** 安全纵深装配（审计出口在每轮由驱动按会话补上）。 */
    public ExecPath.Security security() {
        GuardBus bus = guards;
        if (policy != null) {
            // 策略进守卫总线：可见性之外还要拦执行（模型幻觉出的名字、策略中途变更）。
            if (bus == null) {
                bus = new GuardBus();
            }
            bus.register(policySlot);
        }
        if (paramPolicyInstalled) {
            // 参数级规则进守卫总线（宿主显式装过才注册：不装 = 不裁决任何参数）。
            if (bus == null) {
                bus = new GuardBus();
            }
            bus.register(paramPolicySlot);
        }
        return new ExecPath.Security(bus, hooks, approvals, null, postHook);
    }

    /** 参数级规则槽（运行期热替换用）。 */
    public ParamPolicySlot paramPolicySlot() {
        return paramPolicySlot;
    }

    /** 是否装过参数级规则（没装 = 没有可热替换的参数规则槽）。 */
    public boolean paramPolicyInstalled() {
        return paramPolicyInstalled;
    }

    /** 配置构造器。 */
    public static final class Builder {

        private ChatModel model;
        private net.hegq.mos4j.tool.ToolRegistry tools;
        private String systemPrompt;
        private net.hegq.mos4j.context.PromptSections.Builder prompt;
        private int concurrency;
        private int maxDecisionRounds;
        private boolean stream;
        private GuardBus guards;
        private HookBus hooks;
        private ApprovalChannel approvals;
        private PostHook postHook;
        private FileSystemBoundary fileSystem;
        private net.hegq.mos4j.fs.RootSet roots;
        private SandboxBoundary sandbox;
        private CompactionService compaction;
        private net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy;
        private java.util.function.Consumer<Throwable> onCompactionFailure;
        private ToolOutputLimiter outputLimiter;
        private SkillCatalog skills;
        private net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents;
        private net.hegq.mos4j.tool.subagent.SubAgentRunner subAgentRunner;
        private java.time.Duration subAgentTimeout;
        private net.hegq.mos4j.agent.subagent.SubAgentSessionFactory subAgentSessions;
        private String contextText;
        private Integer defaultMaxTokens;
        private java.util.function.Supplier<String> mcpInstructions;
        private net.hegq.mos4j.policy.ToolNamePolicy policy;
        private net.hegq.mos4j.policy.ParamPolicy paramPolicy;
        private java.util.List<net.hegq.mos4j.model.ModelOption> models;
        private String defaultModelId;
        private java.util.List<DescribeContributor> describeContributors;
        private java.util.List<AutoCloseable> closers;

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder tools(net.hegq.mos4j.tool.ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 装系统提示词的<b>分节装配器</b>（与 {@link #systemPrompt(String)} 二选一）。
         *
         * <p>宿主用它把身份、约定、部署事实等分节放进提示词；框架自己的分节
         * （技能清单等）会装进<b>同一套装配器</b>，位置由中心顺序统一分配。
         *
         * <p>框架在<b>副本</b>上装配：宿主手里那份保持原样（同一个装配器可以复用于多个智能体）。
         *
         * @param prompt 分节装配器（可空 = 用整串系统提示词）
         * @return this
         */
        public Builder prompt(net.hegq.mos4j.context.PromptSections.Builder prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder maxDecisionRounds(int maxDecisionRounds) {
            this.maxDecisionRounds = maxDecisionRounds;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * 装守卫总线（安全边界；判定串行单调，首个拒绝即短路）。
         *
         * @param guards 守卫总线（可空）
         * @return this
         */
        public Builder guards(GuardBus guards) {
            this.guards = guards;
            return this;
        }

        /**
         * 装钩子总线（扩展点；waterfall）。
         *
         * @param hooks 钩子总线（可空）
         * @return this
         */
        public Builder hooks(HookBus hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * 装审批通道（人工确认）。
         *
         * @param approvals 审批通道（可空 = 未装配，需要确认的调用一律拒绝）
         * @return this
         */
        public Builder approvals(ApprovalChannel approvals) {
            this.approvals = approvals;
            return this;
        }

        /**
         * 装执行后复核钩子（只能把成功改失败，不能洗白失败）。
         *
         * @param postHook 复核钩子（可空）
         * @return this
         */
        public Builder postHook(PostHook postHook) {
            this.postHook = postHook;
            return this;
        }

        /**
         * 装文件边界快照（jail 根、写前观察门、版本令牌口径）。
         *
         * @param fileSystem 快照（可空 = 未装配）
         * @return this
         */
        public Builder fileSystem(FileSystemBoundary fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * 装可管理的根集合（运行期可增删可访问根）。
         *
         * @param roots 根集合（可空 = 不支持运行期增删根）
         * @return this
         */
        public Builder roots(net.hegq.mos4j.fs.RootSet roots) {
            this.roots = roots;
            return this;
        }

        /**
         * 装沙箱边界快照（工作目录、超时上界、击杀强度、资源上限、路径强制）。
         *
         * @param sandbox 快照（可空 = 未装配）
         * @return this
         */
        public Builder sandbox(SandboxBoundary sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        /**
         * 装上下文压缩（超预算时自动摘要并遮蔽旧历史）。
         *
         * @param compaction 压缩服务（可空 = 不压缩）
         * @return this
         */
        public Builder compaction(CompactionService compaction) {
            this.compaction = compaction;
            return this;
        }

        /**
         * 装压缩失败策略（缺省 fail-closed）。
         *
         * @param compactionFailurePolicy 策略（可空 = fail-closed）
         * @return this
         */
        public Builder compactionFailurePolicy(
                net.hegq.mos4j.context.CompactionFailurePolicy compactionFailurePolicy) {
            this.compactionFailurePolicy = compactionFailurePolicy;
            return this;
        }

        /**
         * 装「压缩失败但本轮继续」的宿主通知。
         *
         * @param onCompactionFailure 回调（可空 = 不通知）
         * @return this
         */
        public Builder onCompactionFailure(java.util.function.Consumer<Throwable> onCompactionFailure) {
            this.onCompactionFailure = onCompactionFailure;
            return this;
        }

        /**
         * 装工具输出限流（截断 + 落盘逃生通道）。
         *
         * @param outputLimiter 限流器（可空 = 不限流）
         * @return this
         */
        public Builder outputLimiter(ToolOutputLimiter outputLimiter) {
            this.outputLimiter = outputLimiter;
            return this;
        }

        /**
         * 装技能清单（目录发现由装配层做；这层只接已经扫好的快照）。
         *
         * <p><b>条件注册</b>：清单里有模型可见技能时，{@code Agent.of} 才注册 {@code skill}
         * 工具（没有可加载的技能就不给模型一把空枪）。
         *
         * @param skills 技能清单（可空 = 不装技能）
         * @return this
         */
        public Builder skills(SkillCatalog skills) {
            this.skills = skills;
            return this;
        }

        /**
         * 装子代理清单（目录发现由装配层做；这层只接已经扫好的快照）。
         *
         * <p><b>条件注册</b>：清单非空时，{@code Agent.of} 才给每个子代理注册一个
         * {@code subagent__<name>} 工具（没有子代理就不给模型一把空枪）。
         *
         * @param subAgents 子代理清单（可空 = 不装子代理）
         * @return this
         */
        public Builder subAgents(net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents) {
            this.subAgents = subAgents;
            return this;
        }

        /**
         * 装宿主自备的子代理执行体（可空 = 用缺省实现：独立子会话 + 白名单子注册表）。
         *
         * @param runner 执行体（可空 = 缺省）
         * @return this
         */
        public Builder subAgentRunner(net.hegq.mos4j.tool.subagent.SubAgentRunner runner) {
            this.subAgentRunner = runner;
            return this;
        }

        /**
         * 装单次子代理调用的上限（可空 = 缺省 5 分钟）。
         *
         * <p>超时不是父轮次的错误，而是一段 {@code status: timeout} 的正常回执。
         *
         * @param timeout 超时（不能为负）
         * @return this
         */
        public Builder subAgentTimeout(java.time.Duration timeout) {
            if (timeout != null && timeout.isNegative()) {
                throw new IllegalArgumentException("mos4j: 子代理超时不能为负");
            }
            this.subAgentTimeout = timeout;
            return this;
        }

        /**
         * 装子会话工厂（可空 = 用父会话同一个持久化后端）。
         *
         * @param sessions 工厂
         * @return this
         */
        public Builder subAgentSessions(net.hegq.mos4j.agent.subagent.SubAgentSessionFactory sessions) {
            this.subAgentSessions = sessions;
            return this;
        }

        /**
         * 装项目上下文正文（子代理的系统提示词也要带这一段）。
         *
         * @param contextText 项目上下文正文（可空 = 没有）
         * @return this
         */
        public Builder contextText(String contextText) {
            this.contextText = contextText;
            return this;
        }

        /**
         * 装输出的 token 上限缺省（可空 = 用协议自己的缺省）。
         *
         * @param maxTokens token 上限
         * @return this
         */
        public Builder maxTokens(Integer maxTokens) {
            this.defaultMaxTokens = maxTokens;
            return this;
        }

        /**
         * 装 MCP 服务器说明的提供器（每会话注入一次）。
         *
         * @param provider 提供器（可空 = 不注入）
         * @return this
         */
        public Builder mcpInstructions(java.util.function.Supplier<String> provider) {
            this.mcpInstructions = provider;
            return this;
        }

        /**
         * 装名字级策略（工具名 allow / deny 名单）。
         *
         * <p>装配后两处生效：模型清单里看不见被拒的工具，运行时也调不动。
         *
         * @param policy 策略（可空 = 不收紧）
         * @return this
         */
        public Builder policy(net.hegq.mos4j.policy.ToolNamePolicy policy) {
            this.policy = policy;
            return this;
        }

        /**
         * 装参数级规则（逐参数裁决：allow / ask / deny）。
         *
         * <p>与 {@link #policy} 的分工：{@code policy} 管<b>名字级</b>名单（模型可见性 +
         * 执行放行），本项管<b>参数级</b>规则（deny 档进守卫、ask 档转人工确认）。
         *
         * <p>装上之后可在<b>运行期热替换</b>（{@code Agent.setRules(...)}）——宿主临时
         * 收紧某个工具的某个参数时不必重建智能体。
         *
         * @param paramPolicy 规则（可空 = 不裁决任何参数）
         * @return this
         */
        public Builder paramPolicy(net.hegq.mos4j.policy.ParamPolicy paramPolicy) {
            this.paramPolicy = paramPolicy;
            return this;
        }

        /**
         * 装模型目录（可在运行期换模型；与 {@link #model(ChatModel)} <b>二选一</b>）。
         *
         * <p>目录里的每一项是一个候选模型；换模型在<b>下一轮</b>生效，不重建智能体。
         *
         * @param models 目录项（保序）
         * @return this
         */
        public Builder models(java.util.List<net.hegq.mos4j.model.ModelOption> models) {
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

        /**
         * 装快照补充者（按需模块把装配事实写进 Describe）。
         *
         * @param contributor 补充者
         * @return this
         */
        public Builder describeContributor(DescribeContributor contributor) {
            if (contributor == null) {
                throw new IllegalArgumentException("mos4j: 快照补充者不能为空");
            }
            if (this.describeContributors == null) {
                this.describeContributors = new java.util.ArrayList<>();
            }
            this.describeContributors.add(contributor);
            return this;
        }

        /**
         * 装关闭责任（Agent 关闭时依次关闭）。
         *
         * <p>典型用途：MCP 连接与 stdio 子进程——它们由宿主装配，就该由宿主负责关。
         *
         * @param closer 可关闭对象
         * @return this
         */
        public Builder closer(AutoCloseable closer) {
            if (closer == null) {
                throw new IllegalArgumentException("mos4j: 关闭责任不能为空");
            }
            if (this.closers == null) {
                this.closers = new java.util.ArrayList<>();
            }
            this.closers.add(closer);
            return this;
        }

        public Config build() {
            return new Config(this);
        }    }

    /** 模型能力位（供 Describe 快照）。 */
    public Set<Capability> capabilities() {
        if (model == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(model.capabilities());
    }

    /**
     * 快照补充者（按需模块往里写装配事实）。
     *
     * @return 补充者列表
     */
    public java.util.List<DescribeContributor> describeContributors() {
        return describeContributors;
    }

    /**
     * 关闭责任（Agent 关闭时依次关闭；幂等）。
     *
     * @return 可关闭对象列表
     */
    public java.util.List<AutoCloseable> closers() {
        return closers;
    }

    /**
     * 模型目录（可空 = 单模型模式）。
     *
     * @return 目录
     */
    public ModelCatalog modelCatalog() {
        return modelCatalog;
    }

    /**
     * 模型目录项（单模型模式 = 空列表）。
     *
     * @return 目录项（保序）
     */
    public List<net.hegq.mos4j.model.ModelOption> models() {
        return models;
    }

    /** 已注册工具名（供 Describe 快照）。 */
    public List<String> toolNames() {
        return tools == null ? List.of() : new ArrayList<>(tools.names());
    }

    /**
     * MCP 服务器说明的提供器（可空 = 未装配 MCP）。
     *
     * <p>每会话注入一次，注入时机在驱动跑之前，走 user 侧（不写进静态系统提示词）。
     * 用 {@link java.util.function.Supplier} 而不是固定字符串：连接可能掉线重连，
     * 说明可能变——每次会话取一次当时的，而不是装配期就拍死。
     *
     * @return 提供器（可空）
     */
    public java.util.function.Supplier<String> mcpInstructions() {
        return mcpInstructions;
    }

    /**
     * 名字级策略（可空 = 未装配策略）。
     *
     * <p>装配后**两处生效**：注册表可见性层（被拒的工具不进模型清单）
     * 与运行时守卫（点名调用也被拦下）。两者读同一份名单。
     *
     * @return 策略
     */
    public net.hegq.mos4j.policy.ToolNamePolicy policy() {
        return policy;
    }

    /**
     * 策略槽（可见性与运行时判定读同一份名单；支持热替换）。
     *
     * @return 策略槽
     */
    public PolicySlot policySlot() {
        return policySlot;
    }
}
