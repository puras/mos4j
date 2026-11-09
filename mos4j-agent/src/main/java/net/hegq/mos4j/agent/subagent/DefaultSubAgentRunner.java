package net.hegq.mos4j.agent.subagent;

import net.hegq.mos4j.agent.ModelCatalog;
import net.hegq.mos4j.core.ToolContext;
import net.hegq.mos4j.driver.LoopDriver;
import net.hegq.mos4j.driver.ReActPlanner;
import net.hegq.mos4j.driver.RunContext;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.subagent.SubAgentReceipt;
import net.hegq.mos4j.tool.subagent.SubAgentResult;
import net.hegq.mos4j.tool.subagent.SubAgentRunner;
import net.hegq.mos4j.tool.subagent.SubAgentSpec;
import net.hegq.mos4j.tool.subagent.SubAgentTools;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缺省的子代理执行体：跑一次<b>独立子会话</b>。
 *
 * <p>形态：
 * <ul>
 *   <li><b>独立子会话</b>：新的 {@link Session}（同一持久化后端）——子代理的中间推理与
 *       工具调用留在<b>子会话日志</b>里，不进父上下文；</li>
 *   <li><b>白名单子注册表</b>：子代理拿的是新注册表，只有「声明白名单 ∩ 父可见工具集」
 *       里的工具（deny 优先）；子代理工具与 {@code tool_search} <b>不进</b>子注册表
 *       （深度 0：不许再委派；工具面不经 search：不许自己扩容）；</li>
 *   <li><b>模型覆盖</b>：声明了 {@code model} 就用目录里那个，否则跟父的当前模型；</li>
 *   <li><b>同步</b>：父轮次等它跑完（没有运行期消息回注通道，异步要先造通道）。</li>
 * </ul>
 *
 * <p>子代理内部的工具调用仍走普通的执行通道（守卫 / 策略 / 审批 / 审计）——父的钩子与守卫
 * 原样继承，所以子代理不能绕过父的策略。
 */
public final class DefaultSubAgentRunner implements SubAgentRunner {

    /**
     * 子代理的<b>回执格式</b>约定（进子代理提示词）。
     *
     * <p>固定结构（父模型按它复核）：summary 是子代理自述，findings 是可核对的线索。
     */
    public static final String RECEIPT_INSTRUCTION = """
            完成之后只回答一段 JSON（不要加别的文字）：
            {"summary": "一句话结论（你的自述，调用方会复核）", "findings": [{"ref": "可核对的位置（文件:行、命令、标识符）", "summary": "这条线索说明什么"}]}
            findings 没有就写空数组。""";

    private final RunContext parent;
    private final ChatModel parentModel;
    private final ModelCatalog modelCatalog;
    private final LoopDriver driver;
    private final SubAgentSessionFactory sessions;
    private final String projectContext;
    private final String skillsText;
    private final Integer maxTokens;
    private final AtomicLong sequence = new AtomicLong();

    private DefaultSubAgentRunner(Builder builder) {
        this.parent = builder.parent;
        this.parentModel = builder.parentModel;
        this.modelCatalog = builder.modelCatalog;
        this.driver = builder.driver;
        this.sessions = builder.sessions;
        this.projectContext = builder.projectContext == null ? "" : builder.projectContext;
        this.skillsText = builder.skillsText == null ? "" : builder.skillsText;
        this.maxTokens = builder.maxTokens;
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SubAgentResult run(SubAgentSpec spec, ToolContext context) throws Exception {
        ToolContext effective = context == null ? ToolContext.none() : context;
        // 子注册表：白名单 ∩ 父可见集，扣掉子代理工具与 tool_search。
        SubAgentTools.Child child = SubAgentTools.child(parent.tools(), spec.tools());
        ToolRegistry childRegistry = new ToolRegistry();
        for (Tool tool : child.tools()) {
            childRegistry.register(tool);
        }
        ChatModel model = resolveModel(spec);
        Session childSession = sessions.open(childSessionId(effective, spec.name()));
        RunContext childContext = RunContext.builder()
                .planner(new ReActPlanner(model, maxTokens))
                .tools(childRegistry)
                .concurrency(parent.concurrency())
                .maxDecisionRounds(parent.maxDecisionRounds())
                .systemPrompt(systemPrompt(spec, childRegistry))
                .stream(false)
                .security(parent.security())
                .compaction(parent.compaction())
                .compactionFailurePolicy(parent.compactionFailurePolicy())
                .onCompactionFailure(parent.onCompactionFailure())
                .outputLimiter(parent.outputLimiter())
                .skillTool(parent.skillTool())
                .build();
        String out;
        try {
            out = driver.run(childContext, childSession, task(spec), effective.cancellation());
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            // 子代理跑挂了 → 回一段错误回执（父轮次不因此失败）。
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return SubAgentResult.error("子代理执行失败: " + message);
        }
        if (!child.dropped().isEmpty()) {
            // 被挡掉的声明项照实说（父策略挡掉的工具，子代理拿不到）。
            out = out == null ? "" : out.strip();
            out += "\n\n[注意] 声明里这些工具本次不可用（被父策略挡掉或不允许子代理继承）："
                    + String.join("、", child.dropped());
        }
        return SubAgentReceipt.parse(out);
    }

    /**
     * 取子代理该用的模型。
     *
     * <p>声明了覆盖但目录里没有 → 报错（装配期已查过一次，这里是运行期兜底：模型目录
     * 可能在装配后被换掉）。没声明 → 跟父的当前模型。
     */
    private ChatModel resolveModel(SubAgentSpec spec) {
        if (!spec.hasModel()) {
            if (parentModel == null) {
                throw new IllegalStateException("mos4j: 子代理没有可用模型");
            }
            return parentModel;
        }
        if (modelCatalog == null) {
            throw new IllegalStateException("mos4j: 子代理声明了模型 \"" + spec.model()
                    + "\"，但宿主是单模型模式（没有模型目录）");
        }
        return modelCatalog.chat(spec.model());
    }

    /**
     * 装配子代理的系统提示词。
     *
     * <p>三段：人设（{@code AGENT.md} 正文，现读）→ 项目约定（父的项目上下文，子代理
     * 也在这个项目里干活）→ 回执格式（固定结构，父模型按它复核）。
     *
     * <p><b>不复用父的提示词</b>：父的提示词里有技能清单、MCP 服务器摘要——子代理的工具面里
     * 没有那些工具，列出来只会骗模型。技能清单在子代理确实带 skill 工具时单独补上。
     */
    private String systemPrompt(SubAgentSpec spec, ToolRegistry childRegistry) {
        StringBuilder sb = new StringBuilder();
        String instructions = spec.instructions() == null ? "" : spec.instructions().strip();
        sb.append(instructions.isEmpty() ? "你是子代理 " + spec.name() + "。" : instructions);
        if (!projectContext.isBlank()) {
            sb.append("\n\n").append(projectContext);
        }
        if (!skillsText.isBlank() && childRegistry.get(net.hegq.mos4j.tool.skill.SkillTool.NAME).isPresent()) {
            sb.append("\n\n").append(skillsText);
        }
        sb.append("\n\n").append(RECEIPT_INSTRUCTION);
        return sb.toString();
    }

    /** 拼子代理这一轮的输入（任务 + 附加上下文）。 */
    private static String task(SubAgentSpec spec) {
        String task = spec.task() == null ? "" : spec.task().strip();
        String extra = spec.context() == null ? "" : spec.context().strip();
        if (!extra.isEmpty()) {
            return task + "\n\n[附加上下文]\n" + extra;
        }
        return task;
    }

    /**
     * 派生子会话 id（父会话 id + 子代理名 + 序号）。
     *
     * <p>宿主查子会话日志时看得出它属于哪个父会话、哪个子代理。
     */
    private String childSessionId(ToolContext context, String name) {
        long seq = sequence.incrementAndGet();
        String parentId = context.sessionId() == null ? "" : context.sessionId();
        if (parentId.isEmpty()) {
            return "sub-" + name + "-" + seq;
        }
        return parentId + "-sub-" + name + "-" + seq;
    }

    /** 缺省执行体的构造器。 */
    public static final class Builder {

        private RunContext parent;
        private ChatModel parentModel;
        private ModelCatalog modelCatalog;
        private LoopDriver driver;
        private SubAgentSessionFactory sessions;
        private String projectContext;
        private String skillsText;
        private Integer maxTokens;

        /** 装父运行上下文（工具面 / 安全纵深 / 压缩都从它继承）。 */
        public Builder parent(RunContext parent) {
            this.parent = parent;
            return this;
        }

        /** 装父模型（子代理没声明覆盖时跟它）。 */
        public Builder parentModel(ChatModel parentModel) {
            this.parentModel = parentModel;
            return this;
        }

        /** 装模型目录（子代理声明模型覆盖时从它取）。 */
        public Builder modelCatalog(ModelCatalog modelCatalog) {
            this.modelCatalog = modelCatalog;
            return this;
        }

        /** 装驱动（子会话用它跑）。 */
        public Builder driver(LoopDriver driver) {
            this.driver = driver;
            return this;
        }

        /** 装子会话工厂。 */
        public Builder sessions(SubAgentSessionFactory sessions) {
            this.sessions = sessions;
            return this;
        }

        /** 装项目上下文正文（子代理也在这个项目里干活）。 */
        public Builder projectContext(String projectContext) {
            this.projectContext = projectContext;
            return this;
        }

        /** 装技能清单正文（子代理带 skill 工具时才注入）。 */
        public Builder skillsText(String skillsText) {
            this.skillsText = skillsText;
            return this;
        }

        /** 装 token 上限缺省。 */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public DefaultSubAgentRunner build() {
            if (parent == null) {
                throw new IllegalArgumentException("agent: 子代理执行体需要父运行上下文");
            }
            if (driver == null) {
                throw new IllegalArgumentException("agent: 子代理执行体需要驱动");
            }
            if (sessions == null) {
                throw new IllegalArgumentException("agent: 子代理执行体需要子会话工厂");
            }
            return new DefaultSubAgentRunner(this);
        }
    }

    /** 缺省的子会话工厂：用父会话同一个持久化后端开子会话。 */
    public static SubAgentSessionFactory defaultSessions(net.hegq.mos4j.session.Persistence persistence) {
        return sessionId -> new Session(sessionId, persistence);
    }
}
