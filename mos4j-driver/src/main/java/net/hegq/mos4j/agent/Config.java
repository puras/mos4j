package net.hegq.mos4j.agent;

import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.execpath.ExecPath;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
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
    private final int concurrency;
    private final int maxDecisionRounds;
    private final boolean stream;
    private final GuardBus guards;
    private final HookBus hooks;
    private final ApprovalChannel approvals;
    private final PostHook postHook;
    private final FileSystemBoundary fileSystem;
    private final SandboxBoundary sandbox;
    private final CompactionService compaction;
    private final ToolOutputLimiter outputLimiter;
    private final SkillCatalog skills;

    private Config(Builder builder) {
        this.model = builder.model;
        this.tools = builder.tools;
        this.systemPrompt = builder.systemPrompt;
        this.concurrency = builder.concurrency;
        this.maxDecisionRounds = builder.maxDecisionRounds;
        this.stream = builder.stream;
        this.guards = builder.guards;
        this.hooks = builder.hooks;
        this.approvals = builder.approvals;
        this.postHook = builder.postHook;
        this.fileSystem = builder.fileSystem;
        this.sandbox = builder.sandbox;
        this.compaction = builder.compaction;
        this.outputLimiter = builder.outputLimiter;
        this.skills = builder.skills;
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

    /** 系统引导词（可空）。 */
    public String systemPrompt() {
        return systemPrompt;
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

    /** 沙箱边界快照（可空 = 未装配命令沙箱）。 */
    public SandboxBoundary sandbox() {
        return sandbox;
    }

    /** 上下文压缩服务（可空 = 未装配压缩）。 */
    public CompactionService compaction() {
        return compaction;
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

    /** 安全纵深装配（审计出口在每轮由驱动按会话补上）。 */
    public ExecPath.Security security() {
        return new ExecPath.Security(guards, hooks, approvals, null, postHook);
    }

    /** 配置构造器。 */
    public static final class Builder {

        private ChatModel model;
        private net.hegq.mos4j.tool.ToolRegistry tools;
        private String systemPrompt;
        private int concurrency;
        private int maxDecisionRounds;
        private boolean stream;
        private GuardBus guards;
        private HookBus hooks;
        private ApprovalChannel approvals;
        private PostHook postHook;
        private FileSystemBoundary fileSystem;
        private SandboxBoundary sandbox;
        private CompactionService compaction;
        private ToolOutputLimiter outputLimiter;
        private SkillCatalog skills;

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

    /** 已注册工具名（供 Describe 快照）。 */
    public List<String> toolNames() {
        return tools == null ? List.of() : new ArrayList<>(tools.names());
    }
}
