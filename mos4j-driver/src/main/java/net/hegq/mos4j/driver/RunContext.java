package net.hegq.mos4j.driver;

import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.execpath.ExecPath;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.SkillTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 驱动共享的运行上下文。
 *
 * <p>只装「装配期决定、运行期只读」的东西；运行期变化的状态一律进会话日志
 * （日志即事实）。</p>
 */
public final class RunContext {

    private final Planner planner;
    private final ToolRegistry tools;
    private final int concurrency;
    private final int maxDecisionRounds;
    private final String systemPrompt;
    private final boolean stream;
    private final Consumer<net.hegq.mos4j.llm.StreamChunk> onChunk;
    private final ExecPath.Security security;
    private final CompactionService compaction;
    private final ToolOutputLimiter outputLimiter;
    private final SkillTool skillTool;

    private RunContext(Builder builder) {
        this.planner = builder.planner;
        this.tools = builder.tools;
        this.concurrency = builder.concurrency;
        this.maxDecisionRounds = builder.maxDecisionRounds;
        this.systemPrompt = builder.systemPrompt;
        this.stream = builder.stream;
        this.onChunk = builder.onChunk;
        this.security = builder.security == null ? ExecPath.Security.none() : builder.security;
        this.compaction = builder.compaction;
        this.outputLimiter = builder.outputLimiter;
        this.skillTool = builder.skillTool;
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 决策器（必填）。 */
    public Planner planner() {
        return planner;
    }

    /** 工具注册表（可空 = 未装配工具）。 */
    public ToolRegistry tools() {
        return tools;
    }

    /** 同一批工具调用的并发上限（&lt;=0 取默认）。 */
    public int concurrency() {
        return concurrency;
    }

    /** 单轮内决策次数上限（&lt;=0 = 不限）。 */
    public int maxDecisionRounds() {
        return maxDecisionRounds;
    }

    /** 静态系统引导词（可空）。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** 是否走流式决策。 */
    public boolean stream() {
        return stream;
    }

    /** 流式文本增量回调（可空 = 丢弃增量）。 */
    public Consumer<net.hegq.mos4j.llm.StreamChunk> onChunk() {
        return onChunk;
    }

    /**
     * 安全纵深装配（守卫 / 钩子 / 审批 / 执行后复核）。
     *
     * <p>未装配 = 对应能力不启用（零开销降级）。审计出口是会话级的，
     * 由驱动在每轮开始时套上（见 {@code ExecPath.Security.withAudit}）。
     */
    public ExecPath.Security security() {
        return security;
    }

    /**
     * 上下文压缩服务（可空 = 未装配）。
     *
     * <p>未装配时上下文不会自动压缩——长会话会一直涨，
     * 直到服务商报超长。宿主自己控制上下文长度。
     */
    public CompactionService compaction() {
        return compaction;
    }

    /**
     * 工具输出限流器（可空 = 未装配）。
     *
     * <p>未装配时工具输出不截断：单个大输出就可能撑爆上下文。
     */
    public ToolOutputLimiter outputLimiter() {
        return outputLimiter;
    }

    /**
     * 技能工具（可空 = 未装配技能）。
     *
     * <p>驱动在<b>每轮开始</b>时给它装上本轮的状态与审计出口：激活状态是轮级的
     * （上一轮加载过什么影响不了这一轮），而审计出口是会话级的。
     *
     * @return 技能工具
     */
    public SkillTool skillTool() {
        return skillTool;
    }

    /**
     * 组装送给决策器的对话消息：静态系统引导（若有）+ 会话事件投影。
     *
     * <p>投影由 session 派生，驱动不自行维护历史——日志即事实。</p>
     */
    public List<Message> history(Session session) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.addAll(session.messages());
        return messages;
    }

    /** 校验运行上下文的必要能力（fail-closed：缺能力就拒绝，不降级放行）。 */
    public void validate() {
        if (planner == null) {
            throw new IllegalStateException("driver: RunContext.planner 未设置（fail-closed）");
        }
    }

    /** 运行上下文构造器。 */
    public static final class Builder {

        private Planner planner;
        private ToolRegistry tools;
        private int concurrency;
        private int maxDecisionRounds;
        private String systemPrompt;
        private boolean stream;
        private Consumer<net.hegq.mos4j.llm.StreamChunk> onChunk;
        private ExecPath.Security security;
        private CompactionService compaction;
        private ToolOutputLimiter outputLimiter;
        private SkillTool skillTool;

        public Builder planner(Planner planner) {
            this.planner = planner;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
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

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder onChunk(Consumer<net.hegq.mos4j.llm.StreamChunk> onChunk) {
            this.onChunk = onChunk;
            return this;
        }

        /**
         * 装安全纵深（守卫 / 钩子 / 审批 / 执行后复核）。
         *
         * @param security 装配（可空 = 不启用）
         * @return this
         */
        public Builder security(ExecPath.Security security) {
            this.security = security;
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
         * 装技能工具（驱动每轮给它换状态与审计出口）。
         *
         * @param skillTool 技能工具（可空 = 未装配）
         * @return this
         */
        public Builder skillTool(SkillTool skillTool) {
            this.skillTool = skillTool;
            return this;
        }

        public RunContext build() {
            return new RunContext(this);
        }
    }
}
