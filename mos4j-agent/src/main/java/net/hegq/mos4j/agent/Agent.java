package net.hegq.mos4j.agent;

import net.hegq.mos4j.context.PromptSections;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.driver.LoopDriver;
import net.hegq.mos4j.driver.NothingToResumeException;
import net.hegq.mos4j.driver.Planner;
import net.hegq.mos4j.driver.ReActDriver;
import net.hegq.mos4j.driver.ReActPlanner;
import net.hegq.mos4j.driver.ResumeOptions;
import net.hegq.mos4j.driver.RunContext;
import net.hegq.mos4j.fs.FileSystemBoundary;
import net.hegq.mos4j.fs.Root;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.Skill;
import net.hegq.mos4j.tool.skill.SkillCatalog;
import net.hegq.mos4j.tool.skill.SkillReloadResult;
import net.hegq.mos4j.tool.skill.SkillTool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 智能体实例：装配（Config）+ 驱动（LoopDriver）的组合。
 *
 * <p>会话由调用方持有（可挂持久化后端）——智能体不缓存会话，
 * 这样同一实例可以服务多个会话。</p>
 */
public final class Agent implements AutoCloseable {

    private final Config config;
    private final RunContext runContext;
    private final LoopDriver driver;
    private final SkillCatalog skills;
    private final SkillTool skillTool;
    private final ToolRegistry tools;
    private final net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents;
    private final java.util.concurrent.atomic.AtomicInteger concurrency;
    private final java.util.concurrent.atomic.AtomicInteger maxDecisionRounds;
    private boolean closed;

    private Agent(Config config, RunContext runContext, LoopDriver driver,
                  SkillCatalog skills, SkillTool skillTool, ToolRegistry tools,
                  net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents,
                  java.util.concurrent.atomic.AtomicInteger concurrency,
                  java.util.concurrent.atomic.AtomicInteger maxDecisionRounds) {
        this.config = config;
        this.runContext = runContext;
        this.driver = driver;
        this.skills = skills;
        this.skillTool = skillTool;
        this.tools = tools;
        this.subAgents = subAgents;
        this.concurrency = concurrency;
        this.maxDecisionRounds = maxDecisionRounds;
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
        Planner planner = new ReActPlanner(model, config.defaultMaxTokens());
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
        //
        // 两条路：
        //   宿主注入了分节装配器 → 框架把清单装进同一套装配器（中心顺序保证位置）；
        //   宿主只给了整串 → 框架按中心顺序做字符串插入（老口径，位置同样受约束）。
        //
        // 子代理清单走同一套路（位置在 MCP 摘要之后、工具指引之前）。
        net.hegq.mos4j.tool.subagent.SubAgentCatalog subAgents = config.subAgents();
        // 子代理工具要进**同一张**注册表：先把表准备好（宿主的表优先，没有就新建一张）。
        // 不先建表的话，运行上下文里就“没装工具”，模型点名子代理会直接被判成能力缺口。
        boolean withSubAgents = subAgents != null && subAgents.len() > 0;
        if (tools == null && withSubAgents) {
            tools = new ToolRegistry();
        }
        String systemPrompt = config.systemPrompt();
        PromptSections.Builder promptBuilder = config.prompt();
        String projectContext = config.contextText() == null ? "" : config.contextText();
        if (promptBuilder != null) {
            // 在副本上装配：宿主手里那份保持原样（同一套装配器可复用于多个智能体）。
            PromptSections.Builder copy = promptBuilder.clone();
            String catalog = skills == null ? "" : skills.render();
            if (!catalog.isEmpty()) {
                if (copy.has("skills")) {
                    throw new IllegalStateException(
                            "agent: 提示词里已经有 skills 段（宿主已放了技能清单），框架不再重复装");
                }
                copy.add("skills", catalog);
            }
            String subAgentCatalog = subAgents == null ? "" : subAgents.render();
            if (!subAgentCatalog.isEmpty()) {
                if (copy.has("subagents")) {
                    throw new IllegalStateException(
                            "agent: 提示词里已经有 subagents 段（宿主已放了子代理清单），框架不再重复装");
                }
                copy.add("subagents", subAgentCatalog);
            }
            // 项目上下文：宿主自己放的优先（宿主知道哪份是准的）；没放就用装配层给的。
            if (projectContext.isEmpty()) {
                projectContext = copy.content("context") == null ? "" : copy.content("context");
            }
            systemPrompt = copy.build().render();
        } else {
            if (skills != null) {
                String catalog = skills.render();
                if (!catalog.isEmpty()) {
                    systemPrompt = PromptSections.insert(systemPrompt, "skills", catalog);
                }
            }
            if (subAgents != null) {
                String catalog = subAgents.render();
                if (!catalog.isEmpty()) {
                    systemPrompt = PromptSections.insert(systemPrompt, "subagents", catalog);
                }
            }
        }
        // 策略可见性：被拒的工具不进模型清单（与运行时守卫读同一份名单）。
        // 装在具名槽里：策略热替换只动自己那层，MCP 的 hidden 层不受影响。
        if (config.policy() != null && tools != null) {
            tools.setVisibilitySlot(PolicySlot.SLOT, config.policySlot()::allows);
        }
        // 运行期可调的旋钮：并发上限与决策轮数上限。
        // 驱动每轮现取一次（同一轮内不变），对齐 Go 的 atomic 读法。
        java.util.concurrent.atomic.AtomicInteger concurrency =
                new java.util.concurrent.atomic.AtomicInteger(config.concurrency());
        java.util.concurrent.atomic.AtomicInteger maxDecisionRounds =
                new java.util.concurrent.atomic.AtomicInteger(config.maxDecisionRounds());
        RunContext runContext = RunContext.builder()
                .planner(planner)
                .tools(tools)
                .concurrencySupplier(concurrency::get)
                .maxDecisionRoundsSupplier(maxDecisionRounds::get)
                .systemPrompt(systemPrompt)
                .stream(config.stream())
                .security(config.security())
                .compaction(config.compaction())
                .compactionFailurePolicy(config.compactionFailurePolicy())
                .onCompactionFailure(config.onCompactionFailure())
                .outputLimiter(config.outputLimiter())
                .skillTool(skillTool)
                .build();
        // 子代理：条件注册（能力即证据）。清单非空时，每个子代理一个普通工具
        // （subagent__<name>），走同一套注册表 / 守卫 / 策略 / 审批流水线。
        //
        // 执行体：宿主自备的优先；没自备就用缺省实现（独立子会话 + 白名单子注册表 +
        // 模型覆盖）。子代理内部仍然走父的守卫与策略——子代理不能绕过父的安全边界。
        if (withSubAgents) {
            net.hegq.mos4j.tool.subagent.SubAgentRunner runner = config.subAgentRunner();
            if (runner == null) {
                runner = net.hegq.mos4j.agent.subagent.DefaultSubAgentRunner.builder()
                        .parent(runContext)
                        .parentModel(model)
                        .modelCatalog(config.modelCatalog())
                        .driver(driver)
                        .sessions(config.subAgentSessions() == null
                                ? net.hegq.mos4j.agent.subagent.DefaultSubAgentRunner.defaultSessions(null)
                                : config.subAgentSessions())
                        .projectContext(projectContext)
                        .skillsText(skills == null ? "" : skills.render())
                        .maxTokens(config.defaultMaxTokens())
                        .build();
            }
            java.time.Duration timeout = config.subAgentTimeout() == null
                    ? net.hegq.mos4j.tool.subagent.SubAgentTool.DEFAULT_TIMEOUT
                    : config.subAgentTimeout();
            for (net.hegq.mos4j.tool.Tool tool : subAgents.tools(runner, timeout)) {
                tools.register(tool);
            }
        }
        return new Agent(config, runContext, driver, skills, skillTool, tools, subAgents,
                concurrency, maxDecisionRounds);
    }

    /**
     * 追加一个可访问根（运行期扩权）。
     *
     * <p><b>这是收窄边界，不是放行任意路径</b>：加进来的目录确实进入文件 jail
     * （读与写都在其内），所以它扩大了可访问范围——宿主自己决定要不要加。
     *
     * <p>挂载名重复 → 报错（不静默覆盖：覆盖会让「模型以为访问的是 A 目录，实际是 B 目录」）。
     *
     * @param id  挂载名（不能与已有根重复；锚点根用空串）
     * @param dir 根目录（必须存在）
     * @throws IOException 根不存在或不可读
     */
    public void addRoot(String id, Path dir) throws IOException {
        managedRoots().add(id, dir);
    }

    /**
     * 追加一个**只读**根（能读不能写）。
     *
     * @param id  挂载名（不能与已有根重复）
     * @param dir 根目录（必须存在）
     * @throws IOException 根不存在或不可读
     */
    public void addReadRoot(String id, Path dir) throws IOException {
        managedRoots().addRead(id, dir);
    }

    /**
     * 追加一个**只写**根（能写不能读）。
     *
     * @param id  挂载名（不能与已有根重复）
     * @param dir 根目录（必须存在）
     * @throws IOException 根不存在或不可读
     */
    public void addWriteRoot(String id, Path dir) throws IOException {
        managedRoots().addWrite(id, dir);
    }

    /**
     * 移除一个根（收窄边界）。
     *
     * <p>锚点根不可移除；移除不存在的根 → 报错（不假装成功）。
     *
     * @param id 挂载名
     */
    public void removeRoot(String id) {
        managedRoots().remove(id);
    }

    /**
     * 当前全部根（快照）。
     *
     * @return 根列表
     */
    public List<Root> roots() {
        return managedRoots().roots();
    }

    /**
     * 可管理的根集合。
     *
     * <p>只有装配期建了 {@link RootSet} 才有；宿主自带文件边界（快照）时没有 →
     * <b>明确报错</b>（不假装能管根）。
     */
    private RootSet managedRoots() {
        RootSet roots = config.roots();
        if (roots == null) {
            throw new UnsupportedOperationException(
                    "agent: 未装配可管理的根集合（文件边界是宿主自带快照），没有可增删的根");
        }
        return roots;
    }

    /** 执行一次任务（一个 turn）。 */
    public String run(Session session, String input) throws IOException {
        return run(session, input, CancellationToken.none());
    }

    /**
     * MCP 服务器说明的会话级一次性标记。
     *
     * <p>与 Go 侧同名标记同口径：认领成功才注入，注入过就不再重复（避免刷屏）。
     */
    public static final String MCP_INSTRUCTIONS_CLAIM = "mcp/instructions";

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
        injectMcpInstructions(session);
        return driver.run(runContext, session, input, cancellation);
    }

    /**
     * 续跑：把被中断的那一轮里的悬空调用接着走完。
     *
     * <p><b>显式续跑</b>：框架不自动拾起挂起的轮次——宿主先用
     * {@code session.pendingApprovals()} / {@code session.hangingCalls()} 看清现场，
     * 再决定要不要续。
     *
     * <p><b>沿用原轮次号</b>（审批决定的复用键是 {@code (turn, callId)}），
     * 载荷从日志原样重放，走普通安全链，回填真实 {@code tool/result}，
     * 然后继续决策并正常收尾那一轮。
     *
     * <p><b>不注入 MCP 说明</b>：那一轮的用户输入已经在日志里，续跑不是新一轮。
     *
     * @param session 会话
     * @param options 续跑参数（可空 = 自动选最后一个被打断的轮次、续该轮全部悬空调用）
     * @return 本轮最终回复文本
     * @throws NothingToResumeException 没有可续的悬空调用（那一轮保持挂起）
     * @throws IOException 落盘失败
     */
    public String resume(Session session, ResumeOptions options) throws IOException, NothingToResumeException {
        return resume(session, options, CancellationToken.none());
    }

    /**
     * 续跑（带取消）。
     *
     * @param session     会话
     * @param options     续跑参数（可空 = 自动选最后一个被打断的轮次）
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @return 本轮最终回复文本
     * @throws NothingToResumeException 没有可续的悬空调用（那一轮保持挂起）
     * @throws IOException 落盘失败
     */
    public String resume(Session session, ResumeOptions options, CancellationToken cancellation)
            throws IOException, NothingToResumeException {
        if (session == null) {
            throw new IllegalArgumentException("agent: 会话未设置");
        }
        return driver.resume(runContext, session, options, cancellation);
    }

    /**
     * 注入 MCP 服务器说明（每会话一次，走 user 侧）。
     *
     * <p>走 user 侧而不是系统提示词：系统提示词是装配期渲染一次的静态内容
     *（保 prompt cache），而 MCP 连接可能掉线重连、说明可能变——写进静态提示词会失真。
     *
     * <p>注入一条 user 事件（进日志）：说明是会话里发生的事实，不是提示词拼装的一部分。
     */
    private void injectMcpInstructions(Session session) throws IOException {
        java.util.function.Supplier<String> provider = config.mcpInstructions();
        if (provider == null) {
            return;
        }
        String text = provider.get();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!session.claim(MCP_INSTRUCTIONS_CLAIM)) {
            return; // 本会话已注入过
        }
        session.append(Event.builder(EventKind.USER).text(text).build());
    }

    /**
     * 热替换名字级策略。
     *
     * <p>可见性与运行时判定读**同一份**名单（{@link PolicySlot}），
     * 所以换一份策略，模型清单与执行期同时跟随——不会出现「看得见却调不动」
     * 或「看不见却能调」。
     *
     * <p>不重建智能体：策略是装配期可看清全貌的那一层，运行期换名单是常见操作。
     *
     * @param policy 新策略（可空 = 不限制）
     */
    public void setPolicy(net.hegq.mos4j.policy.ToolNamePolicy policy) {
        config.policySlot().install(policy);
    }

    /**
     * 热替换**参数级**规则（逐参数裁决）。
     *
     * <p>对齐 Go 的 {@code Agent.SetRules}：宿主要临时收紧某个工具的某个参数
     * （比如把 {@code shell} 的某个参数改成必须人工确认），不必重建智能体。
     *
     * <p><b>未装参数级策略时直接报错</b>（不假装生效）：宿主没装过参数级规则，
     * 就没有对应的守卫槽，热替换写进去也不会被判定——那属于「以为收紧了、实际没收紧」。
     *
     * @param rules 新规则（可空 = 空规则，不裁决任何参数）
     */
    public void setRules(net.hegq.mos4j.policy.ParamPolicy rules) {
        if (!config.paramPolicyInstalled()) {
            throw new IllegalStateException(
                    "mos4j: 未装配参数级策略（Config.paramPolicy(...)），没有可热替换的参数规则槽");
        }
        config.paramPolicySlot().install(rules);
    }

    /** 当前参数级规则（未装配 = 空规则）。 */
    public net.hegq.mos4j.policy.ParamPolicy rules() {
        return config.paramPolicySlot().policy();
    }

    /**
     * 运行期调整同一批工具调用的并发上限（&lt;=0 取默认 4）。
     *
     * <p><b>下一轮生效</b>：正在跑的那一轮已经取过并发上限（同一轮内不变）——
     * 半路改并发会让同一批工具的并行度前后不一致，审计对不上。
     *
     * @param concurrency 并发上限
     */
    public void setConcurrency(int concurrency) {
        this.concurrency.set(concurrency);
    }

    /** 当前并发上限。 */
    public int concurrency() {
        return concurrency.get();
    }

    /**
     * 运行期调整单轮内的决策次数上限（&lt;=0 = 不限）。
     *
     * <p><b>下一轮生效</b>：同 {@link #setConcurrency(int)}。
     *
     * @param maxDecisionRounds 决策次数上限
     */
    public void setMaxDecisionRounds(int maxDecisionRounds) {
        this.maxDecisionRounds.set(maxDecisionRounds);
    }

    /** 当前决策次数上限。 */
    public int maxDecisionRounds() {
        return maxDecisionRounds.get();
    }

    /**
     * 运行期换模型（<b>下一轮生效</b>，正在跑的那一轮不受影响——它已经取过目标）。
     *
     * <p>目录里没有这个 ID 直接报错（不静默无效）。单模型模式没有目录可换 → 也报错
     * （宿主要用换模型，就该装目录）。
     *
     * @param modelId 模型 ID
     */
    public void setModel(String modelId) {
        ModelCatalog catalog = config.modelCatalog();
        if (catalog == null) {
            throw new IllegalStateException("mos4j: 未装配模型目录（单模型模式不能换模型）");
        }
        catalog.set(modelId);
    }

    /**
     * 当前模型 ID（单模型模式 = 空串）。
     *
     * @return 模型 ID
     */
    public String modelId() {
        ModelCatalog catalog = config.modelCatalog();
        return catalog == null ? "" : catalog.currentId();
    }

    /**
     * 模型目录（保序；单模型模式 = 空列表）。
     *
     * <p>对外只暴露 ID 与展示名——凭据留在模型对象里，不进快照与公开描述。
     *
     * @return 目录项
     */
    public List<net.hegq.mos4j.model.ModelOption> models() {
        ModelCatalog catalog = config.modelCatalog();
        return catalog == null ? List.of() : catalog.list();
    }

    /**
     * 关闭智能体持有的外部资源（幂等）。
     *
     * <p><b>关闭责任</b>：智能体本身不持有连接，但宿主装的东西（如 MCP 连接与 stdio
     * 子进程）由宿主装进来——那它就该由智能体一并关掉，否则进程退出会留下孤儿子进程。
     *
     * <p>关闭失败不抛：第一个失败会被记下来，其余继续关（不能因为一个关不掉就不关别的）。
     *
     * @throws Exception 存在关闭失败时，抛出第一个失败（包装后）
     */
    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        Exception first = null;
        for (AutoCloseable closer : config.closers()) {
            try {
                closer.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw new IllegalStateException("mos4j: 关闭装配的资源时失败：" + first.getMessage(), first);
        }
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
        if (config.modelCatalog() != null) {
            // 目录只报 ID 与展示名——凭据留在模型对象里，不进快照。
            StringBuilder catalog = new StringBuilder();
            for (net.hegq.mos4j.model.ModelOption option : config.modelCatalog().list()) {
                if (catalog.length() > 0) {
                    catalog.append("，");
                }
                catalog.append(option.id()).append("=").append(option.label());
            }
            features.add("模型目录（" + config.modelCatalog().list().size() + " 个候选，当前 "
                    + config.modelCatalog().currentId() + "，换模型下一轮生效）：" + catalog);
        }
        for (net.hegq.mos4j.core.DescribeContributor contributor : config.describeContributors()) {
            // 补充者只写事实；它自己报错也不能让快照整个拿不到。
            try {
                contributor.contribute(features, caveats);
            } catch (RuntimeException e) {
                caveats.add("快照补充者报错：" + e.getMessage());
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
        ModelCatalog catalog = config.modelCatalog();
        return catalog == null ? null : catalog.currentId();
    }

    /** 框架版本（来自 classpath 元数据；取不到时写 unknown，不假装知道）。 */
    private static String version() {
        Package pkg = Agent.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "unknown" : version;
    }
}
