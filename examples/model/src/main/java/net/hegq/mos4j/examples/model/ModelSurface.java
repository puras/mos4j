package net.hegq.mos4j.examples.model;

import net.hegq.mos4j.model.catalog.ModelOption;
import net.hegq.mos4j.model.catalog.ModelRegistry;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.decorate.CallRecord;
import net.hegq.mos4j.model.decorate.CachingChatModel;
import net.hegq.mos4j.model.decorate.FallbackChatModel;
import net.hegq.mos4j.model.decorate.LoggingChatModel;
import net.hegq.mos4j.model.decorate.TimeoutChatModel;
import net.hegq.mos4j.model.protocol.anthropic.chat.AnthropicChatModel;
import net.hegq.mos4j.model.protocol.anthropic.chat.AnthropicConfig;
import net.hegq.mos4j.model.protocol.gemini.chat.GeminiChatModel;
import net.hegq.mos4j.model.protocol.gemini.chat.GeminiConfig;
import net.hegq.mos4j.model.protocol.openai.chat.OpenAiChatModel;
import net.hegq.mos4j.model.protocol.openai.chat.OpenAiConfig;
import net.hegq.mos4j.model.retry.RetryPolicy;
import net.hegq.mos4j.model.retry.RetryingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 模型模块的<b>装配面</b>：注册表、目录、装饰器叠加。
 *
 * <p>{@link ModelExample} 讲的是「调用面」（一个请求进、一个响应出），这个类讲的是
 * 调用面之外的<b>两件事</b>——它们是模型模块后加的能力，也是宿主真正要写的那部分代码：
 * <ol>
 *   <li><b>装配</b>：{@link ModelRegistry}（名字 → 工厂）+ {@link ModelOption}（目录项同时报
 *       kind 与句柄）——配置文件里写 {@code provider=openai}，代码里不写死协议；</li>
 *   <li><b>横切能力</b>：装饰器<b>显式叠加</b>（重试 / 超时 / 降级 / 观测），组合顺序即执行顺序。</li>
 * </ol>
 *
 * <p><b>为什么只有这两件</b>：chat 是同步的「一次请求换一次响应」。异步任务的
 * 生命周期（{@code TaskHandle} / {@code TaskStatus} / {@code TaskPoller}，提交 → 轮询 → 取产物）
 * 与产物（{@code Artifact}）服务的是图像 / 视频生成、文档解析这类 kind，<b>chat 不走这条路</b>。
 * 在这个 chat 示例里演一段假状态转移，就是「假装它们能跑」——那个场景随第一个非 chat kind
 * 落地时单独出示例（口径：刷新一次归框架、节奏与超时归宿主、产物不自己写文件）。
 * 今天那两类类型的语义由 mos4j-model 自己的 {@code TaskHandleTest} / {@code ArtifactTest} 守。
 */
public final class ModelSurface {

    /** 注册表里的配置键名（约定：{@code api_key} / {@code base_url} / {@code model}）。 */
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_MODEL = "model";

    private ModelSurface() {
    }

    // ------------------------------------------------------------------ 装配

    /**
     * 提供方注册表：名字 → 工厂。三个方言预注册。
     *
     * <p>注册表<b>只做映射</b>：它产出的是裸模型，不认识协议差异，也不装横切能力
     * （日志 / 超时 / 降级由 {@link #decorate} 显式叠加）。
     *
     * <p>未注册的名字 fail-closed 并列出已注册的名字——不静默挑一个默认协议，
     * 挑错了就是拿密钥打错的服务端。
     *
     * @return 注册了 openai / anthropic / gemini 的注册表
     */
    public static ModelRegistry registry() {
        ModelRegistry registry = new ModelRegistry();
        registry.register("openai", config -> new OpenAiChatModel(OpenAiConfig.of(
                config.get(KEY_API_KEY), config.get(KEY_BASE_URL),
                valueOr(config.get(KEY_MODEL), ModelConfig.DEFAULT_OPENAI_MODEL))));
        registry.register("anthropic", config -> new AnthropicChatModel(AnthropicConfig.of(
                config.get(KEY_API_KEY), config.get(KEY_BASE_URL),
                valueOr(config.get(KEY_MODEL), ModelConfig.DEFAULT_ANTHROPIC_MODEL))));
        registry.register("gemini", config -> new GeminiChatModel(GeminiConfig.of(
                config.get(KEY_API_KEY), config.get(KEY_BASE_URL),
                valueOr(config.get(KEY_MODEL), ModelConfig.DEFAULT_GEMINI_MODEL))));
        return registry;
    }

    /**
     * 模型目录：宿主可切换的候选，每项<b>kind 与句柄一起报</b>。
     *
     * <p>今天只有 chat 这一 kind 有装配面（{@link ModelOption#chat} + 装饰器 + 协议包）；
     * embedding / rerank / 图像 / 视频等 kind 随 {@code protocol.<方言>.<kind>} 逐个落地，
     * 届时各自加一个工厂方法即可——「kind + 泛型句柄」这一层不用再动。
     *
     * @param openai    OpenAI 方言模型
     * @param anthropic Anthropic 方言模型
     * @param gemini    Gemini 方言模型
     * @return 目录（不可变）
     */
    public static List<ModelOption<ChatModel>> catalog(ChatModel openai, ChatModel anthropic,
                                                       ChatModel gemini) {
        return List.of(
                ModelOption.chat("openai", "OpenAI 方言", openai),
                ModelOption.chat("anthropic", "Anthropic 方言", anthropic),
                ModelOption.chat("gemini", "Gemini 方言", gemini));
    }

    // ------------------------------------------------------------ 横切能力（装饰器）

    /**
     * 装饰器叠加（缺省重试策略）。
     *
     * @param primary     主模型
     * @param backup      备用模型（可空 = 没有降级，不假装有）
     * @param timeout     每次尝试的超时
     * @param log         观测回调
     * @return 装好的模型
     */
    public static ChatModel decorate(ChatModel primary, ChatModel backup, Duration timeout,
                                     Consumer<CallRecord> log) {
        return decorate(primary, backup, timeout, RetryPolicy.ofDefaults(), log);
    }

    /**
     * 装饰器叠加：<b>组合顺序即执行顺序</b>（外层先执行）。
     *
     * <p>这个顺序不是随意排的，每一层的选择都有理由：
     * <pre>
     * 观测（最外层）      一次宿主调用只记一条——记的是「真的发生了一次调用」，不是记每次重试
     *   降级            主模型用尽重试后切备用模型
     *     重试          只重试瞬时错误（429 / 5xx / 408 / 网络 / 空响应）
     *       超时        每次尝试各自计时——重试不会共享一个总超时
     *         协议模型  裸模型，不带任何横切能力
     * </pre>
     *
     * <p><b>口径（不这么排会出什么事）</b>：超时若放在重试之外，一次超时会把整条重试链打死；
     * 观测若放在内层，一次重试就会在日志里留下多条记录——事后分不清「模型真的调了三次」
     * 与「框架重试了三次」（不变式 I2「日志即事实」）。
     *
     * @param primary 主模型
     * @param backup  备用模型（可空 = 不降级）
     * @param timeout 每次尝试的超时
     * @param retry   重试策略（可空 = 不重试）
     * @param log     观测回调（可空 = 不观测）
     * @return 装好的模型
     */
    public static ChatModel decorate(ChatModel primary, ChatModel backup, Duration timeout,
                                     RetryPolicy retry, Consumer<CallRecord> log) {
        ChatModel current = new TimeoutChatModel(primary, timeout);
        if (retry != null) {
            current = new RetryingChatModel(current, retry);
        }
        if (backup != null) {
            current = new FallbackChatModel(current, new TimeoutChatModel(backup, timeout));
        }
        return log == null ? current : new LoggingChatModel(current, log);
    }

    /**
     * 本地结果缓存（可选叠层）：<b>只缓非流式</b>，失败不缓存，取消优先于命中。
     *
     * <p>它与服务端 prompt cache 不是一件事：这边缓的是整次请求的结果对象，
     * 命中就完全不调服务端；服务端按前缀缓存输入 token，计费口径完全不同。
     *
     * <p>为什么不把它塞进 {@link #decorate} 的固定叠层：缓存改变了「未命中就真调」
     * 这件事（同样的请求第二次不再打服务端），是需要宿主明确决定的语义，不该默认开。
     *
     * @param model 被装饰的模型
     * @param ttl   存活时间（{@code <= 0} = 不过期）
     * @return 带缓存的模型
     */
    public static CachingChatModel withCache(ChatModel model, Duration ttl) {
        return new CachingChatModel(model, ttl);
    }

    // ------------------------------------------------------------------ 内部

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 注册表配置的键名一览（宿主拼配置时照抄，避免各处写错字符串）。 */
    public static Map<String, String> config(String apiKey, String baseUrl, String model) {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        putIfPresent(config, KEY_API_KEY, apiKey);
        putIfPresent(config, KEY_BASE_URL, baseUrl);
        putIfPresent(config, KEY_MODEL, model);
        return Map.copyOf(config);
    }

    private static void putIfPresent(Map<String, String> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }
}
