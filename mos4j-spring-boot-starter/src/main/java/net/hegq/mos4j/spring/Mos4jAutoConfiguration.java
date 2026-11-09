package net.hegq.mos4j.spring;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.context.ChatModelSummaryGenerator;
import net.hegq.mos4j.context.CompactionPolicy;
import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.SpillStore;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.context.Truncation;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.sandbox.PathScopeProbe;
import net.hegq.mos4j.session.JsonlPersistence;
import net.hegq.mos4j.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MoS4J 的 Spring Boot 自动装配。
 *
 * <p><b>装配什么</b>：{@link Mos4j} 门面、{@link Agent}、{@link JsonlPersistence}、
 * {@link Mos4jRunner}；并把容器里已有的 {@link ApprovalChannel} / {@link GuardBus} /
 * {@link HookBus} / {@link PostHook} / {@link ChatModel} / {@link ToolRegistry} 接进去。
 *
 * <p><b>不静默降级（fail-closed）</b>：装配期间发现「该有的没有」，一律启动失败并说清缺什么——
 * <ul>
 *   <li>启用但没有 API 密钥（属性与环境变量都没有）→ 启动失败，报出该看哪个变量
 *       （宿主自带 {@code ChatModel} Bean 时不适用：那个模型自己知道怎么认证）；</li>
 *   <li>{@code mos4j.approvals.required=true} 但容器里没有 {@code ApprovalChannel}
 *       → 启动失败（不装配审批通道 = 需要人确认的调用在运行期全被拒，那时已经晚了）。</li>
 * </ul>
 * 唯一允许的「不装配」是宿主显式 {@code mos4j.enabled=false}：那时一个 Bean 都不给，
 * 状态是可见的，不是半残的。
 *
 * <p><b>为什么不做「先给个能跑的默认」</b>：默认装配出来的 Agent 会真的发请求、真的动文件。
 * 一个「能跑但没钥匙 / 没审批」的 Agent，比没有 Agent 危险得多——
 * 宿主会以为安全链路已经在了。
 */
@AutoConfiguration
@EnableConfigurationProperties(Mos4jProperties.class)
@ConditionalOnProperty(prefix = "mos4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Mos4jAutoConfiguration {

    /**
     * 装配 SDK 门面。
     *
     * @param properties 配置属性
     * @param model 宿主自带的模型（可空 = 按属性装配）
     * @param tools 宿主自带的工具面（可空 = 缺省文件四件套）
     * @param approvals 审批通道（可空 = 未装配）
     * @param guards 守卫总线（可空 = 未装配）
     * @param hooks 钩子总线（可空 = 未装配）
     * @param postHook 执行后复核钩子（可空 = 未装配）
     * @param pathScopeProbe 路径强制可用性探针（可空 = 真探一次）
     * @param environment Spring 环境（读密钥/模型名等环境变量口径；测试可注入）
     */
    @Bean
    @ConditionalOnMissingBean
    public Mos4j mos4j(Mos4jProperties properties,
                        ObjectProvider<ChatModel> model,
                        ObjectProvider<ToolRegistry> tools,
                        ObjectProvider<ApprovalChannel> approvals,
                        ObjectProvider<GuardBus> guards,
                        ObjectProvider<HookBus> hooks,
                        ObjectProvider<PostHook> postHook,
                        ObjectProvider<PathScopeProbe> pathScopeProbe,
                        Environment environment) throws IOException {
        ApprovalChannel approvalChannel = approvals.getIfAvailable();
        if (properties.getApprovals().isRequired() && approvalChannel == null) {
            throw new IllegalStateException(
                    "mos4j: 要求人工审批（mos4j.approvals.required=true），但容器里没有 ApprovalChannel Bean。"
                            + "不装配审批通道 = 需要人确认的调用一律 fail-closed 拒绝（运行期才炸就晚了）。"
                            + "请提供一个 ApprovalChannel Bean，或把 mos4j.approvals.required 设为 false。");
        }

        Map<String, String> environmentSnapshot = environmentSnapshot(environment);

        // 模型：宿主自带优先。只有「我们自己造模型」时才需要密钥——
        // 宿主自带的模型（本地模型 / 自带凭据的网关）自己知道怎么认证，
        // 为它要一把用不上的密钥只是噪音（要它却不用 = 配置在骗人）。
        ChatModel chatModel = model.getIfAvailable();
        if (chatModel == null) {
            String apiKey = resolveApiKey(properties, environmentSnapshot);
            chatModel = Mos4j.defaultModel(properties.getProtocol(), apiKey,
                    properties.getBaseUrl(), properties.getModel(), environmentSnapshot,
                    properties.getRequireFinish());
        } else if (properties.getRequireFinish() != null) {
            throw new IllegalStateException(
                    "mos4j: 配了 mos4j.require-finish，但模型是宿主自带的 ChatModel Bean——"
                            + "这个开关只对 starter 自己装配的 Gemini 模型生效。"
                            + "要控制它，请让 starter 装配模型（不提供 ChatModel Bean）。");
        }

        // 门面接的是**已经装配好的模型**：所以这里不再传 api-key / model 名，
        // 免得门面按自己的缺省口径再造一个模型（两份真相 = 迟早漂移）。
        Mos4j.Builder builder = Mos4j.builder()
                .model(chatModel)
                .protocol(properties.getProtocol())
                .systemPrompt(properties.getSystemPrompt())
                .concurrency(properties.getConcurrency())
                .maxDecisionRounds(properties.getMaxDecisionRounds())
                .stream(properties.isStream())
                .guards(guards.getIfAvailable())
                .hooks(hooks.getIfAvailable())
                .approvals(approvalChannel)
                .postHook(postHook.getIfAvailable());
        if (properties.getWorkspace() != null && !properties.getWorkspace().isBlank()) {
            builder.workspace(workspaceOf(properties));
        }
        if (properties.getLogDir() != null && !properties.getLogDir().isBlank()) {
            builder.logDir(Path.of(properties.getLogDir()));
        }
        ToolRegistry registry = tools.getIfAvailable();
        if (registry != null) {
            builder.tools(registry);
        }

        // 路径强制：缺省 = 探一次能不能真用起来（不通就启动失败，fail-closed）；
        // 宿主显式 disabled 时不装（Describe 会说清「shell 不受路径强制保护」）。
        // 宿主可以提供一个 PathScopeProbe Bean 覆盖探测（例如受管环境里已知 apply 被拒）。
        //
        // 顺序要紧：先决定装不装，再装文件工具——fileTools() 会**立刻**装配 shell 工具，
        // 那时路径强制的开关已经生效，晚了就变成「先按缺省探、再想关」。
        if (properties.getPathScope() == Mos4jProperties.PathScopeMode.DISABLED) {
            builder.noPathScope();
        } else {
            PathScopeProbe probe = pathScopeProbe.getIfAvailable();
            if (probe != null) {
                builder.pathScopeProbe(probe);
            }
        }
        builder.fileTools(properties.isFileTools());

        // 输出上限（模型级配置；配了就真的进每个请求）。
        if (properties.getMaxTokens() != null) {
            builder.maxTokens(properties.getMaxTokens());
        }

        // 输出限流：默认装上（上下文是稀缺资源；不限流时一个大输出就能撑爆窗口）。
        Mos4jProperties.Output output = properties.getOutput();
        if (output.isEnabled()) {
            builder.outputLimiter(outputLimiter(output, properties, spillReadable(registry, properties)));
        }

        // 压缩：未配置 contextWindow 就不装（窗口只有服务商知道，猜了就是用错误的事实做决定）。
        Mos4jProperties.Compaction compaction = properties.getCompaction();
        if (compaction.getContextWindow() > 0) {
            builder.compaction(new CompactionService(
                    CompactionPolicy.of(compaction.getContextWindow(),
                            compaction.getReserveTokens(),
                            compaction.getKeepRecentTokens()),
                    new ChatModelSummaryGenerator(chatModel)));
        }

        // 技能：目录是宿主的拓扑（不配就不装）。
        // 两条纪律都在这里体现：相对路径按 workspace 解析（不是 CWD）；
        // 目录不存在不报错——读不动就是「没这个技能」，降级说明进 Describe。
        Mos4jProperties.Skills skills = properties.getSkills();
        if (skills.getDirs() != null && !skills.getDirs().isEmpty()) {
            builder.skills(skillDirs(skills.getDirs(), workspaceOf(properties)));
            builder.skillLimits(skills.getMaxBodyBytes(), skills.getMaxCatalogBytes());
        }
        // 上下文文件：不配就不装（自动扫工作区会让每次启动的提示词都可能不一样）。
        // 相对路径按 workspace 解析；读不到文件不报错——那是「项目没写约定文件」，
        // 但被裁剪/丢弃的会进 Describe caveats（静默丢内容 = 模型以为项目没这条规矩）。
        Mos4jProperties.ContextFiles contextFiles = properties.getContextFiles();
        if (contextFiles.getDir() != null && !contextFiles.getDir().isBlank()) {
            Path dir = Path.of(contextFiles.getDir()).isAbsolute()
                    ? Path.of(contextFiles.getDir()).normalize()
                    : workspaceOf(properties).resolve(contextFiles.getDir()).normalize();
            if (contextFiles.getCandidates().isEmpty()) {
                builder.contextFiles(dir);
            } else {
                builder.contextFiles(contextFiles.getCandidates(), dir);
            }
            if (contextFiles.getByteBudget() != null) {
                builder.contextFileByteBudget(contextFiles.getByteBudget());
            }
            if (contextFiles.getMaxDepth() != null) {
                builder.contextFileMaxDepth(contextFiles.getMaxDepth());
            }
            if (contextFiles.getGlobalDirs() != null) {
                builder.contextFileGlobalDirs(contextFiles.getGlobalDirs());
            }
        }
        return builder.build();
    }

    /**
     * 技能目录解析（相对路径按 workspace，绝对路径直接用）。
     *
     * <p><b>为何不以 CWD 为锚</b>：同一份配置换个目录启动就扫到另一套技能，
     * 而配置看上去没变——这类问题只能靠约定防住。
     */
    private static Path[] skillDirs(List<String> configured, Path workspace) {
        return configured.stream()
                .filter(dir -> dir != null && !dir.isBlank())
                .map(dir -> Path.of(dir).isAbsolute()
                        ? Path.of(dir).normalize()
                        : workspace.resolve(dir).normalize())
                .toArray(Path[]::new);
    }

    /**
     * 装配 Agent（等价于 {@code mos4j.agent()}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public Agent mos4jAgent(Mos4j mos4j) {
        return mos4j.agent();
    }

    /**
     * 装配会话持久化（按 id 存取会话）。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonlPersistence mos4jPersistence(Mos4j mos4j) {
        return mos4j.persistence();
    }

    /**
     * 装配一次性执行器（跑一轮 turn；容器关闭时取消在途请求）。
     */
    @Bean
    @ConditionalOnMissingBean
    public Mos4jRunner mos4jRunner(Mos4j mos4j) {
        return new Mos4jRunner(mos4j);
    }

    /**
     * 解析 API 密钥：属性优先，其次协议对应的环境变量。
     *
     * <p><b>为什么在这里就报错</b>：等到第一次调模型才发现没钥匙，错误会出现在业务请求里，
     * 看起来像「服务商故障」；在启动时失败，错误信息直接指向配置。
     *
     * <p><b>什么时候才需要它</b>：只在 starter 自己造模型时。
     * 宿主自带 {@code ChatModel} Bean 时不要求密钥（那个模型自己知道怎么认证）。
     */
    private static String resolveApiKey(Mos4jProperties properties, Map<String, String> environment) {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            return properties.getApiKey();
        }
        String envName = apiKeyEnvNames().get(properties.getProtocol());
        String fromEnv = environment.get(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        throw new IllegalStateException(
                "mos4j: 未配置 API 密钥。请设置属性 mos4j.api-key，或环境变量 " + envName
                        + "（当前协议 " + properties.getProtocol().name().toLowerCase(Locale.ROOT) + "）。");
    }

    /**
     * 从 Spring 环境里取协议相关的环境变量快照（只取用得上的那几个）。
     *
     * <p><b>为什么不把整个 Environment 塞进去</b>：环境里有成千上万个属性，
     * 全量拷贝进门面既浪费又容易误用（把不相关的配置当成模型参数）。
     */
    private static Map<String, String> environmentSnapshot(Environment environment) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String name : List.of("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_MODEL",
                "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "ANTHROPIC_MODEL",
                "GEMINI_API_KEY", "GEMINI_BASE_URL", "GEMINI_MODEL")) {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) {
                snapshot.put(name, value);
            }
        }
        return snapshot;
    }

    /**
     * 落盘逃生通道能不能用：模型得有办法把那个文件读回来。
     *
     * <p><b>为什么这么判</b>：落盘通道写进去的路径，只有模型能读到才有意义；
     * 读不到时上下文里那句「完整内容在 X」就是骗模型。
     * starter 自己装文件四件套时，jail 锚在 workspace，模型读得到；
     * 宿主自带工具面（或干脆关了文件工具）时，starter 不知道模型能读哪些目录——
     * 那就不装，而不是装一个可能读不到的东西。
     */
    private static boolean spillReadable(ToolRegistry hostRegistry, Mos4jProperties properties) {
        return hostRegistry == null && properties.isFileTools();
    }

    /**
     * 装配输出限流器（截断 + 可选的落盘逃生通道）。
     *
     * <p>落盘目录必须落在文件 jail 可读根内——越界在构造期就失败，不等到运行期。
     */
    private static ToolOutputLimiter outputLimiter(Mos4jProperties.Output output,
                                                    Mos4jProperties properties,
                                                    boolean spillReadable) throws IOException {
        Truncation truncation = Truncation.builder()
                .maxLines(output.getMaxLines())
                .maxBytes(output.getMaxBytes())
                .build();
        SpillStore spillStore = null;
        String spillDir = output.getSpillDir();
        if (spillDir != null && !spillDir.isBlank()) {
            if (!spillReadable) {
                throw new IllegalStateException(
                        "mos4j: 配了 mos4j.output.spill-dir，但当前没有可读回它的文件工具"
                                + "（宿主自带 ToolRegistry，或 mos4j.file-tools=false）。"
                                + "落盘逃生通道写进去的路径模型读不到 = 上下文里那句话是骗模型。"
                                + "请去掉该配置，或由宿主自己装配落盘通道。");
            }
            Path workspace = workspaceOf(properties);
            // 相对路径按 jail 锚点（workspace）解析；绝对路径必须在可读根内（越界 = 构造期失败）
            spillStore = SpillStore.of(new SandboxedFs(RootSet.of(workspace)), spillDir);
        }
        return ToolOutputLimiter.of(truncation, spillStore);
    }

    /**
     * 工作目录（绝对化）。
     *
     * <p><b>为什么要绝对化</b>：jail 锚点用相对路径时，解析结果取决于进程 CWD——
     * 换个目录启动，同一份配置就指向不同的 jail。绝对化后配置与实际边界一一对应。
     */
    private static Path workspaceOf(Mos4jProperties properties) {
        String configured = properties.getWorkspace();
        if (configured == null || configured.isBlank()) {
            return Path.of("").toAbsolutePath();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    /** 属性前缀（文档与 IDE 提示共用一份）。 */
    public static final String PREFIX = "mos4j";

    /** 协议 → API 密钥环境变量名（缺省兜底用；显式属性优先）。 */
    public static Map<Mos4j.Protocol, String> apiKeyEnvNames() {
        return Map.of(
                Mos4j.Protocol.OPENAI, "OPENAI_API_KEY",
                Mos4j.Protocol.ANTHROPIC, "ANTHROPIC_API_KEY",
                Mos4j.Protocol.GEMINI, "GEMINI_API_KEY");
    }
}
