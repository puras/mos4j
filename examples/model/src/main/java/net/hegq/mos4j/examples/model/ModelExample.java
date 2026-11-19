package net.hegq.mos4j.examples.model;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.catalog.ModelOption;
import net.hegq.mos4j.model.catalog.ModelRegistry;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.chat.Message;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.chat.StreamEmitter;
import net.hegq.mos4j.model.chat.StreamTruncatedException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MoS4J 纯模型示例：直接对接 {@link ChatModel}，不过门面、没有工具与会话。
 *
 * <p><b>这个示例证明的事</b>：模型能力对接的最小面就是 {@code ChatModel} 的两个方法——
 * {@code chat}（非流式）与 {@code chatStream}（流式），外加 {@code capabilities()}。
 * 没有工具就没有工具调用循环，没有会话就没有事件日志：一个请求进，一个响应出，
 * 状态归宿主（想带历史就把上一轮的两条消息留着拼进下一次请求）。
 *
 * <p><b>循环对话</b>：{@link ModelChatLoop} 就是「状态归宿主」的可跑版本——历史是宿主的一个
 * 列表，每轮整段带上；退出、清空、看历史都是宿主的命令词。主程序加 {@code --loop} 进交互模式。
 *
 * <p><b>调用面之外还有装配面</b>：协议选择走 {@link ModelRegistry}（名字 → 工厂，
 * 不写死一家）、横切能力用装饰器<b>显式叠加</b>。这些在 {@link ModelSurface} 里，
 * 主程序把两边都跑一遍。
 *
 * <p><b>配置</b>：协议、密钥、基址、模型名、提示词、命令词全在 {@link ModelConfig} 里——
 * 常量优先，其次环境变量，最后协议缺省值。示例<b>不读 {@code .env}</b>。
 *
 * <p><b>分层口径</b>：{@code ChatModel} 协议无关——同一个调用面，换协议只是换装配
 * （OpenAI / Anthropic / Gemini 各自的 Config + ChatModel）。能力缺口 fail-closed：
 * 要流式就先问 {@code supports(Capability.STREAMING)}，不支持的协议直接不用，
 * 不「试试看能不能用」。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini          # 可省略，缺省看 ModelConfig
 *
 * # 一遍走完调用面与装配面（一个固定的问题）
 * java -cp "examples/model/target/classes:$(cat cp.txt)" \
 *      net.hegq.mos4j.examples.model.ModelExample "只回答：1+1=?"
 *
 * # 循环对话（/exit 或 /quit 退出，/reset 清历史，/history 看条数）
 * java -cp "examples/model/target/classes:$(cat cp.txt)" \
 *      net.hegq.mos4j.examples.model.ModelExample --loop
 * </pre>
 * 把 {@link ModelConfig#PROTOCOL} 改成 {@code anthropic} / {@code gemini} 即换协议
 * （分别要配 ANTHROPIC_API_KEY / GEMINI_API_KEY）。
 */
public final class ModelExample {

    /** 协议名（openai | anthropic | gemini；换协议只改 {@link ModelConfig#PROTOCOL}）。 */
    public static final String PROTOCOL = ModelConfig.PROTOCOL;

    /** 系统提示词（纯模型照样可以带：system 消息就是消息列表的第一条）。 */
    public static final String SYSTEM_PROMPT = ModelConfig.SYSTEM_PROMPT;

    /** 进交互模式的命令行开关。 */
    public static final String FLAG_LOOP = "--loop";

    private ModelExample() {
    }

    /**
     * 入口：按 {@link #PROTOCOL} 从注册表装模型。
     *
     * <p>没有 {@code --loop} 就是把调用面与装配面跑一遍（一个问题）；带 {@code --loop}
     * 进循环对话，{@code args} 里的其余参数忽略。
     *
     * @param args {@code --loop} 进交互模式；否则第一个参数是问题（缺省问 1+1）
     * @throws Exception 网络失败 / 协议错误（真实调用不做降级，失败就如实失败）
     */
    static void main(String[] args) throws Exception {
        if (hasLoopFlag(args)) {
            ChatModel model = assemble(PROTOCOL, "loop");
            printCapabilities(model);
            runLoop(model, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    System.out);
            return;
        }

        String question = args.length > 0 && !args[0].isBlank() ? args[0] : "只回答：1+1=?";
        ChatModel model = assemble(PROTOCOL, question);

        printCapabilities(model);

        System.out.println("[非流式] " + chatOnce(model, question));
        System.out.println("[流式]   " + chatStream(model, question));
        System.out.println("[多轮]   " + multiTurn(model, "我刚才问了你什么？"));
        printAssembly(model);
        System.out.println("[循环]   想连续对话加参数 " + FLAG_LOOP + "（"
                + ModelConfig.CMD_EXIT + " 退出）");
    }

    // ---------------------------------------------------------------- 循环对话

    /**
     * 交互式循环对话：<b>读一行、调一次、追加历史、再来一行</b>。
     *
     * <p>退出条件：读到 EOF（Ctrl+D）、{@link ModelConfig#CMD_EXIT}、{@link ModelConfig#CMD_QUIT}。
     * 另外两个命令词是 {@link ModelConfig#CMD_RESET}（清空历史）与 {@link ModelConfig#CMD_HISTORY}。
     *
     * <p><b>单轮失败不带走整个进程</b>：真实调用会失败（网络、限流、协议错），一轮失败就把
     * 对话打断，用户得从头再来。这里如实打印原因，历史不变，继续下一行。
     *
     * <p><b>流式开关</b>：{@link ModelConfig#STREAM_IN_LOOP} 决定用 {@code askStream}
     * 还是 {@code ask}；开了但模型不支持流式就退回非流式，并在开头那一行提示里说清楚——
     * 不静默换挡。
     *
     * @param model  模型
     * @param reader 输入（可注入：用例不开真终端）
     * @param out    输出（可注入）
     * @return 成功完成轮数
     * @throws IOException 读输入失败
     */
    public static int runLoop(ChatModel model, BufferedReader reader, PrintStream out)
            throws IOException {
        ModelChatLoop conversation = new ModelChatLoop(SYSTEM_PROMPT);
        boolean streaming = ModelConfig.STREAM_IN_LOOP && model.supports(Capability.STREAMING);
        out.println("[循环对话] 协议=" + PROTOCOL + "；" + ModelConfig.CMD_EXIT + " 退出，"
                + ModelConfig.CMD_RESET + " 清历史，" + ModelConfig.CMD_HISTORY + " 看条数");
        out.println("[模式] " + describeMode(streaming));
        int rounds = 0;
        while (true) {
            out.print(ModelConfig.PROMPT);
            out.flush();
            String line = reader.readLine();
            if (line == null) {
                out.println();
                break;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (isExit(input)) {
                break;
            }
            if (ModelConfig.CMD_RESET.equalsIgnoreCase(input)) {
                conversation.reset();
                out.println("[已清空] 回到 " + conversation.history().size() + " 条（系统提示词）");
                continue;
            }
            if (ModelConfig.CMD_HISTORY.equalsIgnoreCase(input)) {
                out.println("[历史] " + conversation.history().size() + " 条消息，"
                        + conversation.rounds() + " 轮问答");
                continue;
            }
            try {
                String answer = streaming
                        ? askStreaming(conversation, model, input, out)
                        : conversation.ask(model, input);
                if (streaming) {
                    // 增量已经把正文打完了，这里只收尾：补换行，不再重复正文。
                    out.println();
                } else {
                    out.println(answer);
                }
                rounds++;
            } catch (RuntimeException e) {
                out.println("[这一轮失败] " + describe(e)
                        + "（历史未改动，可以重问）");
            }
        }
        out.println("[结束] 本轮会话共 " + rounds + " 轮（历史在进程内存里，退出即丢）");
        return rounds;
    }

    /** 是不是退出命令词。 */
    private static boolean isExit(String input) {
        return ModelConfig.CMD_EXIT.equalsIgnoreCase(input)
                || ModelConfig.CMD_QUIT.equalsIgnoreCase(input);
    }

    /**
     * 流式问一轮：增量直接打到<b>注入的</b> {@code out}，返回值是聚合后的正文。
     *
     * <p><b>为什么不用 {@link #printingEmitter()}</b>：那个写到 {@code System.out}。
     * 循环的输出是可以被替换的（用例抓的是 {@code ByteArrayOutputStream}），
     * 增量跑到另一个流上就会出现「终端看着正常、用例里一个字都没有」。
     */
    private static String askStreaming(ModelChatLoop conversation, ChatModel model,
                                       String input, PrintStream out) {
        return conversation.askStream(model, input, chunk -> {
            if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                out.print(chunk.delta());
                out.flush();
            }
        });
    }

    /** 把运行模式说清楚：开了流式但模型不支持时，别让人以为是静默降级。 */
    private static String describeMode(boolean streaming) {
        if (streaming) {
            return "流式（增量实时打出）";
        }
        if (ModelConfig.STREAM_IN_LOOP) {
            return "非流式（模型不支持流式，已退回非流式；能力位见上面那行）";
        }
        return "非流式（ModelConfig.STREAM_IN_LOOP=false）";
    }

    private static boolean hasLoopFlag(String[] args) {
        for (String arg : args) {
            if (FLAG_LOOP.equalsIgnoreCase(arg) || "-i".equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    /** 把异常说成人能读的一句：带上根因，否则「请求失败」四个字排不了障。 */
    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.toString() : e.getMessage();
        Throwable cause = e.getCause();
        if (cause == null || cause == e) {
            return message;
        }
        String root = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message + "（根因 " + root + "）";
    }

    // ---------------------------------------------------------------- 装配

    /**
     * 按协议名从注册表装一个<b>真协议模型</b>（取值顺序见 {@link ModelConfig}）。
     *
     * <p>装的是裸模型：重试 / 超时 / 降级是宿主的决定（见 {@link ModelSurface#decorate}），
     * 示例不替宿主打开它们——打开了却不说，审计里就会出现「一次输入多次调用」。
     *
     * @param protocol 协议名（openai / anthropic / gemini）
     * @param question 仅用于报错时点明上下文
     * @return 协议模型
     * @throws Exception 装配失败
     */
    public static ChatModel assemble(String protocol, String question) throws Exception {
        ModelRegistry registry = ModelSurface.registry();
        if (!registry.contains(safeLower(protocol))) {
            throw new IllegalStateException("mos4j-example: 未支持的协议 " + protocol
                    + "（已注册: " + registry.names() + "）");
        }
        return registry.create(safeLower(protocol), envOf(protocol));
    }

    /**
     * 按协议取注册表要的连接配置（常量 → 环境变量 → 协议缺省）。
     *
     * @param protocol 协议名
     * @return 配置键值
     */
    static Map<String, String> envOf(String protocol) {
        ModelConfig.Provider provider = ModelConfig.provider(protocol);
        return ModelSurface.config(provider.apiKey(), provider.baseUrl(), provider.model());
    }

    /**
     * 装配面演示（不需要真实调用，主程序打一遍口径）：目录项同时报 kind 与句柄、
     * 装饰器叠加顺序。
     *
     * <p><b>刻意不演示异步任务与产物</b>：那两条生命周期（提交 → 轮询 → 取产物）服务的是
     * 图像 / 视频生成、文档解析这类 kind，而 chat 是同步的「一次请求换一次响应」——
     * 在 chat 示例里演一段假状态转移 + 一张编造的 PNG，就是「假装它们能跑」。
     * 真场景随第一个非 chat kind 落地时单独出示例。
     *
     * @param model 已装好的模型（当成主模型用）
     */
    static void printAssembly(ChatModel model) {
        List<ModelOption<ChatModel>> catalog =
                ModelSurface.catalog(model, model, model);
        System.out.println("[目录] " + catalog.stream()
                .map(option -> option.kind() + "/" + option.id())
                .toList());

        AtomicInteger calls = new AtomicInteger();
        ChatModel decorated = ModelSurface.decorate(model, null, ModelConfig.CALL_TIMEOUT,
                record -> calls.incrementAndGet());
        System.out.println("[装饰] 叠加后能力不变="
                + decorated.capabilities().equals(model.capabilities())
                + "（观测回调只在真实调用时触发，已触发 " + calls.get() + " 次）");
    }

    /** 能力快照：装了什么、支持什么，先看清楚再调用。 */
    private static void printCapabilities(ChatModel model) {
        System.out.println("[能力] " + model.capabilities());
    }

    // ---------------------------------------------------------------- 调用面

    /**
     * 非流式：一个请求进，一个响应出。
     *
     * <p>system 提示词与用户问题就是消息列表；参数不设就是 {@code null}（服务端默认）——
     * {@code temperature=0} 与 {@code temperature=null} 在协议层是两件事。
     *
     * @param model    模型
     * @param question 用户问题
     * @return 助手回答文本
     */
    public static String chatOnce(ChatModel model, String question) {
        Response response = model.chat(Request.of(List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user(question))));

        System.out.printf("[非流式] 结束原因=%s, 输入=%d token, 输出=%d token%n",
                response.finishReason(),
                response.usage().freshInput(),
                response.usage().completionTokens());
        return response.message().content();
    }

    /**
     * 流式：增量块边收边打，返回值是聚合后的完整响应。
     *
     * <p>两个截断口都如实处理：{@code emitter} 抛异常 = 宿主主动掐输出；
     * {@link StreamTruncatedException} = 流在结束信号前中断。都不静默吞。
     *
     * @param model    模型
     * @param question 用户问题
     * @return 聚合后的完整回答文本
     */
    public static String chatStream(ChatModel model, String question) {
        return chatStream(model, question, printingEmitter());
    }

    /**
     * 流式（宿主自带给增量块的去处：终端渲染、转发给前端都走这里）。
     *
     * @param model    模型
     * @param question 用户问题
     * @param emitter  增量块去处（可空 = 不处理增量，只要聚合结果）
     * @return 聚合后的完整回答文本
     */
    public static String chatStream(ChatModel model, String question, StreamEmitter emitter) {
        if (!model.supports(Capability.STREAMING)) {
            throw new IllegalStateException("mos4j-example: 该模型不支持流式（capabilities="
                    + model.capabilities() + "）——fail-closed，不试运气");
        }
        Response response = model.chatStream(Request.of(List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user(question))), emitter);
        System.out.println();
        return response.message().content();
    }

    /** 默认的增量去处：逐块打到标准输出（真实终端靠它实时出字）。 */
    public static StreamEmitter printingEmitter() {
        return chunk -> {
            if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                System.out.print(chunk.delta());
            }
        };
    }

    /**
     * 多轮（两轮的最小形状）：状态归宿主，上一轮的 user 与 assistant 消息留着拼进下一次请求。
     *
     * <p>连续多轮请看 {@link ModelChatLoop}（同一件事的循环版：历史是个列表，每轮整段带上）。
     *
     * @param model    模型
     * @param question 第二个问题（引用上一轮）
     * @return 助手回答文本
     */
    public static String multiTurn(ChatModel model, String question) {
        List<Message> history = List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user("只回答：1+1=?"));
        Response first = model.chat(Request.of(history));
        Response second = model.chat(Request.of(List.of(
                Message.system(SYSTEM_PROMPT),
                history.get(1),
                first.message(),
                Message.user(question))));
        return second.message().content();
    }

    /**
     * 统一调用面（用例走这条：同一份流程在三个协议替身上都能跑通）。
     *
     * @param protocol 协议名（openai / anthropic / gemini）
     * @param model    该协议的模型实例
     * @param question 问题
     * @return 助手回答文本
     */
    public static String run(String protocol, ChatModel model, String question) {
        return switch (safeLower(protocol)) {
            case "openai", "anthropic", "gemini" -> chatOnce(model, question);
            default -> throw new IllegalStateException("mos4j-example: 未支持的协议 " + protocol
                    + "（openai / anthropic / gemini）");
        };
    }

    /**
     * 期限取消（演示形状：计时器 → {@code cancel()} → 协作式检查点抛取消）。
     *
     * <p>宿主要限时就用一条后台线程计时，到点调 {@code token.cancel()}；
     * 收到 {@link java.util.concurrent.CancellationException} 时如实当「已取消」处理，
     * 不重试不静默。取消是协作式的：模型层只保证「检查点」响应（发送前 / 流式读中 / 聚合后），
     * 不许诺任意毫秒内的硬中断。
     *
     * @param model    模型
     * @param question 问题
     * @param token    宿主自己的取消令牌（何时取消归宿主；可空 = 不可取消）
     * @return 助手回答文本；被取消时抛 {@link java.util.concurrent.CancellationException}
     */
    public static String chatWithDeadline(ChatModel model, String question,
                                          CancellationToken token) {
        Response response = model.chat(Request.ofUser(question),
                token == null ? CancellationToken.none() : token);
        return response.message().content();
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
