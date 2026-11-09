package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.core.ToolContext;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 「一个子代理 = 一个工具」。
 *
 * <p>它是<b>普通工具</b>：走同一套注册表、守卫、策略、审批与 {@code tool/call} /
 * {@code tool/result} 流水线——子代理不另开一条道。
 *
 * <p><b>独占</b>（不实现 {@code concurrencySafe}）：子代理内部要发模型请求，父轮次等它跑完
 * （同步交付），并发跑多个子代理会同时抢模型配额。
 *
 * <p><b>错误分层</b>：
 * <ul>
 *   <li><b>框架层错误</b>（抛异常 → 工具失败）：参数不是 JSON、{@code task} 为空、
 *       定义文件读不出来、没有可用的执行体——这次调用压根没跑起来；</li>
 *   <li><b>子代理层失败</b>（回一段正常回执）：子代理跑挂了、超时了、回执不合法
 *       ——父轮次<b>不</b>因此失败，父模型拿到 {@code status: error/timeout} + 原因说明，
 *       可以改小任务重试或换个子代理。</li>
 * </ul>
 */
public final class SubAgentTool implements Tool {

    /** 工具名前缀（工具名 = 前缀 + 子代理名）。 */
    public static final String PREFIX = SubAgentCatalog.TOOL_PREFIX;

    /** 缺省的单次子代理调用上限（5 分钟）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 工具参数（模型看到的 schema）。
     *
     * <p>只有两个参数：<b>要干的活</b>（必填）+ <b>附加上下文</b>（可选）。刻意不给
     * 「用哪个子代理」这种参数——一个子代理就是一个工具，模型点名调用哪个工具就派哪个子代理。
     *
     * @param task    要交代给子代理的任务
     * @param context 附加上下文（文件路径、代码片段、报错信息等，可空）
     */
    public record Params(
            @ToolParam(desc = "要交给子代理的任务（说清目标、范围与验收标准）", required = true) String task,
            @ToolParam(desc = "附加上下文（文件路径、代码片段、报错信息等，可空）") String context) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final SubAgentDefinition definition;
    private final SubAgentCatalog catalog;
    private final SubAgentRunner runner;
    private final Duration timeout;

    /**
     * 构造器。
     *
     * @param definition 子代理声明
     * @param catalog    清单（提供指令正文的现读）
     * @param runner     执行体（可空 = 没有可用执行体，调用时报错）
     * @param timeout    单次调用上限（零或负 = 不限）
     */
    public SubAgentTool(SubAgentDefinition definition, SubAgentCatalog catalog,
                        SubAgentRunner runner, Duration timeout) {
        if (definition == null) {
            throw new IllegalArgumentException("tool: 子代理工具需要子代理声明");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("tool: 子代理工具需要子代理清单");
        }
        this.definition = definition;
        this.catalog = catalog;
        this.runner = runner;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return PREFIX + definition.name();
    }

    @Override
    public String description() {
        String desc = definition.description().strip();
        if (desc.isEmpty()) {
            desc = "子代理 " + definition.name();
        }
        return desc + "（子代理；回执 {status, summary, findings}：summary 是子代理自述、"
                + "findings 是待复核的线索，结论由你判断）";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        return execute(argsJson, ToolContext.none());
    }

    @Override
    public Object execute(String argsJson, ToolContext context) throws Exception {
        Params params = parse(argsJson);
        String task = params.task() == null ? "" : params.task().strip();
        if (task.isEmpty()) {
            throw new ToolException("subagent 失败：task 不能为空");
        }
        if (runner == null) {
            throw new ToolException("subagent 失败：没有可用的执行体（Runner）");
        }
        SubAgentSpec spec;
        try {
            spec = catalog.buildSpec(definition, task, params.context());
        } catch (SubAgentException e) {
            throw new ToolException("subagent 失败：读取定义失败: " + e.getMessage(), e);
        }
        ToolContext effective = context == null ? ToolContext.none() : context;
        return run(spec, effective);
    }

    /**
     * 跑一次子代理，把结果投影成<b>正常工具结果</b>。
     *
     * <p>超时：执行体在后台虚拟线程里跑，工具不等它；到点返回 {@code status: timeout}
     * 的回执（父轮次不失败）。取消：父轮次的取消令牌透传给执行体，并中断等待。
     */
    private Object run(SubAgentSpec spec, ToolContext context) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        // 用 Future（不是 CompletableFuture）：Future.cancel(true) 真的中断工作线程，
        // 子代理执行体才停得下来。
        Future<SubAgentResult> future = executor.submit(() -> {
            try {
                return runner.run(spec, context);
            } catch (Exception e) {
                throw new SubAgentRunFailure(e);
            }
        });
        net.hegq.mos4j.core.CancellationToken token = context.cancellation();
        net.hegq.mos4j.core.CancellationToken.Registration registration = token == null
                ? net.hegq.mos4j.core.CancellationToken.Registration.NOOP
                : token.onCancel(() -> future.cancel(true));
        try {
            SubAgentResult result;
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                result = future.get();
            } else {
                result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            return verify(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return SubAgentResult.timeout("子代理超时（上限 " + timeout + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return SubAgentResult.error("子代理已取消");
        } catch (CancellationException e) {
            return SubAgentResult.error("子代理已取消");
        } catch (ExecutionException e) {
            return failure(e.getCause() == null ? e : e.getCause());
        } finally {
            registration.close();
            executor.shutdownNow();
        }
    }

    /**
     * 校验回执形状（fail-closed）。
     *
     * <p>回执不合法也投影成正常回执（{@code status=error}）：父轮次不失败，但父模型
     * 看得见「这个子代理回了个坏东西」。
     */
    private static SubAgentResult verify(SubAgentResult result) {
        if (result == null) {
            return SubAgentResult.error("子代理回执不合法: 没有回执");
        }
        String problem = result.validate();
        if (problem != null) {
            return new SubAgentResult(SubAgentStatus.ERROR, result.summary(), result.findings(),
                    "子代理回执不合法: " + problem);
        }
        return result;
    }

    /** 把执行错误投影成正常回执（父轮次不该因子代理失败而失败）。 */
    private static SubAgentResult failure(Throwable error) {
        Throwable cause = error instanceof SubAgentRunFailure && error.getCause() != null
                ? error.getCause() : error;
        if (cause instanceof java.util.concurrent.CancellationException) {
            return SubAgentResult.error("子代理已取消");
        }
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return SubAgentResult.error("子代理执行失败: " + message);
    }

    /** 解析参数。 */
    private static Params parse(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            throw new ToolException("subagent 失败：参数为空");
        }
        try {
            Params params = MAPPER.readValue(argsJson, Params.class);
            if (params == null) {
                throw new ToolException("subagent 失败：参数为空");
            }
            return params;
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("subagent 失败：解析参数失败: " + e.getMessage(), e);
        }
    }

    /** 执行体抛出的异常（区分于「回执不合法」这类正常回执）。 */
    private static final class SubAgentRunFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SubAgentRunFailure(Throwable cause) {
            super(cause);
        }
    }
}
