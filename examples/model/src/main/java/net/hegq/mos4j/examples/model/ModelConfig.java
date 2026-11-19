package net.hegq.mos4j.examples.model;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 示例的<b>配置常量</b>：协议、连接信息、提示词、循环命令词都在这里改，代码里不写死字符串。
 *
 * <p><b>取值顺序</b>（每一档都是「上一步没给才往下走」，不是叠加）：
 * <ol>
 *   <li>本类的常量（非空即生效）——想钉死某个值就填这里；</li>
 *   <li>同名环境变量——示例<b>不读 {@code .env}</b>，但读宿主已经设好的环境变量；</li>
 *   <li>协议缺省值（只有模型名与基址有缺省）。</li>
 * </ol>
 *
 * <p><b>密钥常量默认留空，是刻意的</b>：把真密钥写进源码，它会跟着 git、jar、日志
 * 一起传播出去；而把「密钥从哪来」写成常量，宿主换来源只改一处。要临时钉死一个测试
 * 密钥就填 {@link #OPENAI_API_KEY} 这种常量，但别提交。
 *
 * <p><b>{@link Provider#toString()} 会给密钥打码</b>：示例代码里出现「一 print 就把密钥
 * 打进日志」的形状，是事故的起点。
 */
public final class ModelConfig {

    /** 选哪个方言（{@code openai} | {@code anthropic} | {@code gemini}）——换协议只改这一行。 */
    public static final String PROTOCOL = "openai";

    /** 系统提示词（纯模型照样可以带：system 消息就是消息列表的第一条）。 */
    public static final String SYSTEM_PROMPT = "你是 MoS4J 的纯模型示例助手。回答要短。";

    // ------------------------------------------------------------ 连接常量（OpenAI 方言）

    /** 密钥（留空 = 读 {@link #ENV_OPENAI_API_KEY}；本地 Ollama 这类无鉴权端点两边都留空）。 */
    public static final String OPENAI_API_KEY = "";

    /** 基地址（留空 = 读 {@link #ENV_OPENAI_BASE_URL}，再空 = 协议缺省）。 */
    public static final String OPENAI_BASE_URL = "";

    /** 模型名（留空 = 读 {@link #ENV_OPENAI_MODEL}，再空 = {@link #DEFAULT_OPENAI_MODEL}）。 */
    public static final String OPENAI_MODEL = "";

    /** OpenAI 方言的缺省模型名（常量与环境变量都没给时用它）。 */
    public static final String DEFAULT_OPENAI_MODEL = "qwen3.8-27b";

    // --------------------------------------------------------- 连接常量（Anthropic 方言）

    /** 密钥（留空 = 读 {@link #ENV_ANTHROPIC_API_KEY}）。 */
    public static final String ANTHROPIC_API_KEY = "";

    /** 基地址（留空 = 读 {@link #ENV_ANTHROPIC_BASE_URL}，再空 = 协议缺省）。 */
    public static final String ANTHROPIC_BASE_URL = "";

    /** 模型名（留空 = 读 {@link #ENV_ANTHROPIC_MODEL}，再空 = {@link #DEFAULT_ANTHROPIC_MODEL}）。 */
    public static final String ANTHROPIC_MODEL = "";

    /** Anthropic 方言的缺省模型名。 */
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5";

    // ------------------------------------------------------------ 连接常量（Gemini 方言）

    /** 密钥（留空 = 读 {@link #ENV_GEMINI_API_KEY}）。 */
    public static final String GEMINI_API_KEY = "";

    /** 基地址（留空 = 读 {@link #ENV_GEMINI_BASE_URL}，再空 = 协议缺省）。 */
    public static final String GEMINI_BASE_URL = "";

    /** 模型名（留空 = 读 {@link #ENV_GEMINI_MODEL}，再空 = {@link #DEFAULT_GEMINI_MODEL}）。 */
    public static final String GEMINI_MODEL = "";

    /** Gemini 方言的缺省模型名。 */
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";

    // ---------------------------------------------------------------- 环境变量名（唯一真源）

    /** OpenAI 密钥的环境变量名。 */
    public static final String ENV_OPENAI_API_KEY = "OPENAI_API_KEY";
    /** OpenAI 基地址的环境变量名。 */
    public static final String ENV_OPENAI_BASE_URL = "OPENAI_BASE_URL";
    /** OpenAI 模型名的环境变量名。 */
    public static final String ENV_OPENAI_MODEL = "OPENAI_MODEL";
    /** Anthropic 密钥的环境变量名。 */
    public static final String ENV_ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY";
    /** Anthropic 基地址的环境变量名。 */
    public static final String ENV_ANTHROPIC_BASE_URL = "ANTHROPIC_BASE_URL";
    /** Anthropic 模型名的环境变量名。 */
    public static final String ENV_ANTHROPIC_MODEL = "ANTHROPIC_MODEL";
    /** Gemini 密钥的环境变量名。 */
    public static final String ENV_GEMINI_API_KEY = "GEMINI_API_KEY";
    /** Gemini 基地址的环境变量名。 */
    public static final String ENV_GEMINI_BASE_URL = "GEMINI_BASE_URL";
    /** Gemini 模型名的环境变量名。 */
    public static final String ENV_GEMINI_MODEL = "GEMINI_MODEL";

    // ---------------------------------------------------------------------- 运行参数

    /** 单次请求超时（协议层的连接超时；装饰器演示里也用它）。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** 装饰器演示里「每次尝试」的超时（比 {@link #REQUEST_TIMEOUT} 短，故意让它先触发）。 */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    // ------------------------------------------------------------------ 循环对话命令词

    /** 退出循环（也可用 {@link #CMD_QUIT}，或按 Ctrl+D 送 EOF）。 */
    public static final String CMD_EXIT = "/exit";

    /** 退出循环的另一个写法。 */
    public static final String CMD_QUIT = "/quit";

    /** 清空历史（重新从系统提示词开始）。 */
    public static final String CMD_RESET = "/reset";

    /** 打印当前历史条数。 */
    public static final String CMD_HISTORY = "/history";

    /** 循环的输入提示符。 */
    public static final String PROMPT = "> ";

    // -------------------------------------------------------- 循环的运行方式（编译期开关）

    /**
     * 循环对话是否用流式（改完重编译即生效）。
     *
     * <p><b>不支持流式时自动退回非流式，而不是让整个循环不能用</b>：能力缺口在循环开始时问一次
     * {@code supports(Capability.STREAMING)}，并把答案写进开头那一行提示
     * （「流式」还是「非流式（该模型不支持流式）」）——不静默换挡，也不拿
     * 「试试看」代替判断。
     */
    public static final boolean STREAM_IN_LOOP = true;

    private ModelConfig() {
    }

    /** 支持的方言名（报错时照抄，避免各处自己拼清单）。 */
    public static final List<String> PROTOCOLS = List.of("openai", "anthropic", "gemini");

    /**
     * 解析成一份可直接交给注册表的连接配置（三档取值见类注释）。
     *
     * @param protocol 方言名（openai / anthropic / gemini）
     * @return 连接配置（密钥已打码的 {@code toString}）
     * @throws IllegalStateException 未知方言（fail-closed，并列出支持的名字）
     */
    public static Provider provider(String protocol) {
        return provider(protocol, System::getenv);
    }

    /**
     * 解析成一份连接配置（环境变量来源可注入：用例不开真环境也能验证取值顺序）。
     *
     * @param protocol 方言名
     * @param getenv   环境变量读取器（{@code System::getenv}；可空 = 当作「没有环境变量」）
     * @return 连接配置
     * @throws IllegalStateException 未知方言
     */
    static Provider provider(String protocol, Function<String, String> getenv) {
        Function<String, String> env = getenv == null ? name -> null : getenv;
        EnvNames names = envNames(protocol);
        return switch (safeLower(protocol)) {
            case "openai" -> resolve("openai", OPENAI_API_KEY, OPENAI_BASE_URL, OPENAI_MODEL,
                    DEFAULT_OPENAI_MODEL, names.apiKey(), names.baseUrl(), names.model(), env);
            case "anthropic" -> resolve("anthropic", ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL,
                    ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL, names.apiKey(), names.baseUrl(),
                    names.model(), env);
            case "gemini" -> resolve("gemini", GEMINI_API_KEY, GEMINI_BASE_URL, GEMINI_MODEL,
                    DEFAULT_GEMINI_MODEL, names.apiKey(), names.baseUrl(), names.model(), env);
            default -> throw new IllegalStateException("mos4j-example: 未支持的协议 " + protocol
                    + "（支持: " + String.join(" / ", PROTOCOLS) + "）");
        };
    }

    /**
     * 一个方言读哪三个环境变量名。
     *
     * @param apiKey  密钥的环境变量名
     * @param baseUrl 基地址的环境变量名
     * @param model   模型名的环境变量名
     */
    record EnvNames(String apiKey, String baseUrl, String model) {
    }

    /**
     * 协议名 → 环境变量名（三个方言各读各的，不串台）。
     *
     * <p>单独抽出来是为了可测：用例验证「哪家读哪些名字」，不需要碰常量的当前值。
     *
     * @param protocol 方言名
     * @return 该方言的三个环境变量名
     * @throws IllegalStateException 未知方言
     */
    static EnvNames envNames(String protocol) {
        return switch (safeLower(protocol)) {
            case "openai" -> new EnvNames(ENV_OPENAI_API_KEY, ENV_OPENAI_BASE_URL,
                    ENV_OPENAI_MODEL);
            case "anthropic" -> new EnvNames(ENV_ANTHROPIC_API_KEY, ENV_ANTHROPIC_BASE_URL,
                    ENV_ANTHROPIC_MODEL);
            case "gemini" -> new EnvNames(ENV_GEMINI_API_KEY, ENV_GEMINI_BASE_URL,
                    ENV_GEMINI_MODEL);
            default -> throw new IllegalStateException("mos4j-example: 未支持的协议 " + protocol
                    + "（支持: " + String.join(" / ", PROTOCOLS) + "）");
        };
    }

    /**
     * 一份方言连接配置。
     *
     * @param protocol 方言名
     * @param apiKey   密钥（可能为空：本地无鉴权端点）
     * @param baseUrl  基地址（可能为空：协议缺省）
     * @param model    模型名
     */
    public record Provider(String protocol, String apiKey, String baseUrl, String model) {

        /** 密钥打码：示例代码不该有「一 print 就把密钥打进日志」的形状。 */
        @Override
        public String toString() {
            return "Provider[" + protocol
                    + ", model=" + model
                    + ", baseUrl=" + (isBlank(baseUrl) ? "（协议缺省）" : baseUrl)
                    + ", apiKey=" + (isBlank(apiKey) ? "（未设置）" : "***") + "]";
        }
    }

    // ------------------------------------------------------------------------- 内部

    /**
     * 三档取值：常量 → 环境变量 → 缺省。
     *
     * <p>包级可见：用例直接测这段逻辑，而不是测「常量当前填了什么」——
     * 常量被宿主改了不该让用例变红。
     */
    static Provider resolve(String protocol, String apiKey, String baseUrl, String model,
                            String defaultModel, String apiKeyEnv, String baseUrlEnv,
                            String modelEnv, Function<String, String> getenv) {
        return new Provider(protocol,
                pick(apiKey, getenv.apply(apiKeyEnv), ""),
                pick(baseUrl, getenv.apply(baseUrlEnv), ""),
                pick(model, getenv.apply(modelEnv), defaultModel));
    }

    /** 三档取值：常量 → 环境变量 → 缺省。 */
    private static String pick(String constant, String fromEnv, String fallback) {
        if (!isBlank(constant)) {
            return constant.trim();
        }
        if (!isBlank(fromEnv)) {
            return fromEnv.trim();
        }
        return fallback;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
