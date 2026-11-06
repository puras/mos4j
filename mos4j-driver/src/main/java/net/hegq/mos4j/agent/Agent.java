package net.hegq.mos4j.agent;

import net.hegq.mos4j.context.PromptSections;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.driver.LoopDriver;
import net.hegq.mos4j.driver.Planner;
import net.hegq.mos4j.driver.ReActDriver;
import net.hegq.mos4j.driver.ReActPlanner;
import net.hegq.mos4j.driver.RunContext;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.StreamChunk;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.Skill;
import net.hegq.mos4j.tool.skill.SkillCatalog;
import net.hegq.mos4j.tool.skill.SkillReloadResult;
import net.hegq.mos4j.tool.skill.SkillTool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 智能体实例：装配（Config）+ 驱动（LoopDriver）的组合。
 *
 * <p>会话由调用方持有（可挂持久化后端）——智能体不缓存会话，
 * 这样同一实例可以服务多个会话。</p>
 */
public final class Agent {

    private final Config config;
    private final RunContext runContext;
    private final LoopDriver driver;
    private final SkillCatalog skills;
    private final SkillTool skillTool;
    private final ToolRegistry tools;

    private Agent(Config config, RunContext runContext, LoopDriver driver,
                  SkillCatalog skills, SkillTool skillTool, ToolRegistry tools) {
        this.config = config;
        this.runContext = runContext;
        this.driver = driver;
        this.skills = skills;
        this.skillTool = skillTool;
        this.tools = tools;
    }

    /**
     * 按配置装配智能体（默认 ReAct 驱动 + ReAct 决策器）。
     *
     * @param config 装配配置
     */
    public static Agent of(Config config) {
        return of(config, new ReActDriver());
    }

    /**
     * 按配置装配智能体（显式指定驱动）。
     *
     * @param config 装配配置
     * @param driver 循环驱动
     */
    public static Agent of(Config config, LoopDriver driver) {
        ChatModel model = config.model();
        if (model == null) {
            throw new IllegalArgumentException("agent: 未装配模型（fail-closed）");
        }
        Planner planner = new ReActPlanner(model);
        // 技能：条件注册（能力即证据）。只有存在模型可见技能时才给模型 `skill` 工具；
        // 一个都没有（或全部仅用户可调用）就不注册——空枪只会骗模型多试一次。
        SkillCatalog skills = config.skills();
        ToolRegistry tools = config.tools();
        SkillTool skillTool = null;
        if (skills != null && !skills.modelVisible().isEmpty()) {
            if (tools == null) {
                tools = new ToolRegistry();
            }
            skillTool = new SkillTool(skills);
            tools.register(skillTool);
            // allowed-tools 求交探针：每次激活现查（policy 热替换、MCP 换代后自然跟上）。
            skills.setToolProbe(tools::visibleNames);
        }
        // 技能清单进系统提示词（装配期渲染一次 → 静态；prompt cache 靠这个）。
        // 位置由中心顺序决定：项目上下文之后、工具指引之前。
        String systemPrompt = config.systemPrompt();
        if (skills != null) {
            String catalog = skills.render();
            if (!catalog.isEmpty()) {
                systemPrompt = PromptSections.insert(systemPrompt, "skills", catalog);
            }
        }
        RunContext runContext = RunContext.builder()
                .planner(planner)
                .tools(tools)
                .concurrency(config.concurrency())
                .maxDecisionRounds(config.maxDecisionRounds())
                .systemPrompt(systemPrompt)
                .stream(config.stream())
                .security(config.security())
                .compaction(config.compaction())
                .outputLimiter(config.outputLimiter())
                .skillTool(skillTool)
                .build();
        return new Agent(config, runContext, driver, skills, skillTool, tools);
    }

    /** 执行一次任务（一个 turn）。 */
    public String run(Session session, String input) throws IOException {
        return run(session, input, CancellationToken.none());
    }

    /**
     * 执行一次任务（一个 turn，带取消）。
     *
     * <p><b>取消语义</b>：协作式。令牌会传到驱动、决策器、模型调用与工具批次；
     * 已取消的轮次以 turn/end{interrupted} 收尾再抛 {@link java.util.concurrent.CancellationException}
     * （不留孤儿 turn）。
     *
     * @param cancellation 取消令牌（可空 = 不可取消）
     */
    public String run(Session session, String input, CancellationToken cancellation) throws IOException {
        if (session == null) {
            throw new IllegalArgumentException("agent: 会话未设置");
        }
        return driver.run(runContext, session, input, cancellation);
    }

    /** 运行上下文（供需要细粒度控制的宿主）。 */
    public RunContext runContext() {
        return runContext;
    }

    /**
     * 全部技能（含仅用户可调用的那些）。
     *
     * <p>未装配技能目录时返回空列表。
     *
     * @return 技能列表
     */
    public List<Skill> skills() {
        return skills == null ? List.of() : skills.all();
    }

    /**
     * 重扫技能目录（显式、幂等、全量）。
     *
     * <p><b>为何不自动重扫</b>：扫描要读盘，自动扫就把「读盘多少钱」藏进了每次调用的成本里；
     * 而且目录是宿主的（宿主知道什么时候会变）。
     *
     * <p><b>静态系统提示词不重渲染</b>（保 prompt cache）：变化只通过返回的
     * {@link SkillReloadResult#notice()} 给模型——宿主自己决定要不要把这条 user 侧短消息
     * 补进下一步的上下文。
     *
     * @return 重扫结果（没变化时 {@code changed=false}）
     */
    public SkillReloadResult reloadSkills() {
        return skills == null ? SkillReloadResult.unchanged() : skills.reload();
    }

    /**
     * 展开用户文本里的技能手势（{@code /name}）。
     *
     * <p><b>框架不自动展开</b>：误触发风险（用户只是在句子里提到 {@code "/deploy"} 就展开
     * 一大段正文），而且 SDK 没有用户命令面。宿主拿到用户输入时自己调一次即可。
     *
     * @param text 用户输入
     * @return 展开结果（未命中返回原文）
     */
    public SkillCatalog.Expansion expandSkill(String text) {
        return skills == null ? new SkillCatalog.Expansion(text, false) : skills.expandInvocation(text);
    }

    /** 装配快照：装了什么 + 没装什么。 */
    public Describe describe() {
        List<String> caveats = new ArrayList<>();
        caveats.add("取消是协作式的：取消令牌只发信号，可阻塞点必须显式检查，无编译期保证");
        caveats.add("工具 Schema 由反射生成：注册期生成并缓存；反射失败则注册直接失败（fail-closed）");
        caveats.add("工具并发是显式 opt-in：未声明可并发的调用一律独占（串行）");
        if (config.guards() == null || config.guards().size() == 0) {
            caveats.add("当前未装配守卫：工具调用不经安全策略判定（双轨制只有钩子一条轨）");
        }
        if (config.hooks() == null || config.hooks().size() == 0) {
            caveats.add("当前未装配钩子：扩展点链为空");
        }
        if (config.approvals() == null) {
            caveats.add("当前未装配审批通道：需要人工确认的调用会 fail-closed 拒绝");
        }
        if (config.tools() == null) {
            caveats.add("未装配工具注册表：模型若请求工具调用，本轮直接失败（fail-closed）");
        }
        List<String> features = new ArrayList<>();
        features.add("事件日志（append-only，投影派生）");
        features.add("工具执行通道（回填序 = 调用序）");
        features.add("ReAct 循环");
        if (config.tools() != null && !config.tools().deferredCatalog().isEmpty()) {
            features.add("工具面延后加载（tool_search + activate；激活下一轮生效，历史工具名自动并入）");
        }
        if (config.guards() != null && config.guards().size() > 0) {
            features.add("守卫总线（串行单调，首个拒绝即短路）");
        }
        if (config.hooks() != null && config.hooks().size() > 0) {
            features.add("钩子总线（waterfall，载荷冻结检查）");
        }
        if (config.approvals() != null) {
            features.add("人工审批通道（四档结局，审计全量落日志）");
        }
        if (config.fileSystem() != null) {
            features.addAll(config.fileSystem().features());
            caveats.addAll(config.fileSystem().caveats());
        } else {
            caveats.add("当前未装配文件 jail：文件工具不受路径边界约束（越界与符号链接逃逸都不会被拦）");
        }
        if (config.sandbox() != null) {
            features.addAll(config.sandbox().features());
            caveats.addAll(config.sandbox().caveats());
        } else {
            caveats.add("当前未装配命令沙箱：shell 工具没有超时/输出上限/环境裁剪，也没有进程组击杀");
        }
        if (config.outputLimiter() != null) {
            features.add("工具输出限流（行/字节双限截断，按工具定向保留）");
            if (config.outputLimiter().spillStore() == null) {
                caveats.add("未装配落盘通道：被截断的输出没有完整副本（完整内容真的丢了）");
            } else {
                features.add("截断落盘通道（完整内容写入 jail 内目录，模型可自行读取）");
            }
        } else {
            caveats.add("当前未装配输出限流：工具输出不截断，单个大输出就可能撑爆上下文");
        }
        if (config.compaction() != null) {
            features.add("上下文压缩（超预算自动摘要，旧历史只遮蔽不删日志）");
        } else {
            caveats.add("当前未装配上下文压缩：长会话不会自动压缩，窗口用满后只能由宿主自己处理");
        }
        if (skills != null) {
            int total = skills.len();
            int visible = skills.modelVisible().size();
            if (total > 0) {
                features.add("技能（渐进式披露：清单进系统提示词、正文按 name 加载；共 "
                        + total + " 个，模型可见 " + visible + " 个）");
            } else {
                caveats.add("已装配技能目录，但没加载到任何技能（目录为空、不存在或全部损坏）");
            }
            if (total > 0 && visible == 0) {
                caveats.add("技能全部标记为仅用户显式调用（disable-model-invocation）："
                        + "skill 工具未注册，模型看不到任何技能");
            }
            // 降级说明如实进快照：装不上的技能不能静默消失。
            for (String warning : skills.warnings()) {
                caveats.add("技能: " + warning);
            }
        }
        return new Describe(
                "MoS4J",
                version(),
                System.getProperty("java.version", "unknown"),
                driver.getClass().getSimpleName(),
                ReActPlanner.class.getSimpleName(),
                modelProvider(),
                modelName(),
                config.capabilities().stream().map(Enum::name).sorted().toList(),
                config.toolNames(),
                features,
                caveats);
    }

    private String modelProvider() {
        ChatModel model = config.model();
        return model == null ? null : model.getClass().getSimpleName();
    }

    private String modelName() {
        return null;
    }

    /** 框架版本（来自 classpath 元数据；取不到时写 unknown，不假装知道）。 */
    private static String version() {
        Package pkg = Agent.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
