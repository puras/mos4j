package net.hegq.mos4j.execpath;

import net.hegq.mos4j.core.ApprovalAudit;
import net.hegq.mos4j.core.ApprovalPrior;
import net.hegq.mos4j.core.ApprovalRequest;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.core.Texts;
import net.hegq.mos4j.core.ToolContext;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;
import net.hegq.mos4j.hook.HookBus;
import net.hegq.mos4j.hook.HookEvent;
import net.hegq.mos4j.hook.PostHook;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolArgs;
import net.hegq.mos4j.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 工具调用的统一执行通道（全库唯一的「一批工具调用如何被裁决、如何被调度、
 * 结果如何回填」的语义所在）。
 *
 * <p><b>六条关键契约</b>（机制 M1/M2/M3 + 不变式 I1/I3/I5）：
 * <ol>
 *   <li><b>回填序 = 调用序</b>：返回结果与模型给出的调用严格同序——
 *       并发只影响完成顺序，不影响回填顺序。</li>
 *   <li><b>错误即结果</b>：工具报错、参数非法、工具未注册、被拒绝，都进结果
 *       （{@code isError=true}），<b>不抛异常</b>。回填给模型让它自我纠正；
 *       抛异常会中断整条 turn，模型看不到「哪一条失败、为什么」。</li>
 *   <li><b>逐条独立裁决</b>：拒绝一条不影响同批其他调用。</li>
 *   <li><b>双轨制</b>：守卫（安全边界）与钩子（扩展点）各走一条链，
 *       守卫只跑一次、判定的是<b>最终载荷</b>；钩子改写载荷 = 违约 = 拒绝
 *       （机制 M1）。</li>
 *   <li><b>错误只可加重不可减轻</b>：执行后钩子能把成功改失败，不能把失败洗成成功
 *       （机制 M3）。</li>
 *   <li><b>并发 fail-closed</b>：工具未显式声明可并发 → 一律独占（串行）。
 *       分类器只看本次调用的参数；出错也按独占处理。</li>
 * </ol>
 *
 * <p><b>裁决顺序</b>（每一步都可能拒绝这次调用）：
 * <ol>
 *   <li>注册表 / 工具是否存在；</li>
 *   <li><b>守卫</b>（串行、单调、首个拒绝即短路）：deny → 拒绝；cancel → 拒绝并终止本轮；
 *       ask → 记下待审批；</li>
 *   <li><b>钩子链</b>（waterfall）→ 得到最终载荷；</li>
 *   <li><b>载荷冻结检查</b>：最终载荷与原始调用逐字比对（name / args / id）；</li>
 *   <li><b>审批</b>（需要确认时）：没装配通道 = 拒绝（fail-closed）；批准只对本次调用生效；</li>
 *   <li>执行（执行前再查一次载荷冻结）；</li>
 *   <li><b>回填</b>（按调用序）+ 执行后钩子（只能加重）。</li>
 * </ol>
 *
 * <p><b>顺序为什么这样定</b>：守卫排在钩子前面——安全判定不应发生在扩展代码之后；
 * 已被拒的调用，扩展代码还没跑就不该有机会跑。
 * 审批排在冻结检查后面——不为「反正会被载荷冻结拒掉」的调用去打扰人。
 * 守卫判定的仍然就是最终载荷：任何改写都被冻结检查拒绝，
 * 能执行下去的载荷必然就是守卫看到的那一份（机制 M1）。
 */
public final class ExecPath {

    /** 并发上限的缺省值（只影响「已声明可并发」的那部分调用）。 */
    public static final int DEFAULT_CONCURRENCY = 4;

    private ExecPath() {
    }

    /**
     * 一次批量执行的可选能力（未填的项一律降级为不启用——零开销）。
     *
     * @param concurrency 并发上限（&lt;=0 取默认 4；1 = 串行）
     * @param turn        本次执行所属轮次（先验复用的匹配键之一；0 = 不知道）
     * @param prior       审批先验查询（可空 = 不查先验，每次都要问人）
     * @param context     工具执行上下文（轮次 / 会话 id / 取消令牌；可空 = 空上下文）
     */
    public record Options(int concurrency, int turn, ApprovalPrior prior, ToolContext context) {

        /** 只带并发上限（不启用先验复用）。 */
        public Options(int concurrency) {
            this(concurrency, 0, null, null);
        }

        /** 只带并发上限、轮次与先验（不带执行上下文）。 */
        public Options(int concurrency, int turn, ApprovalPrior prior) {
            this(concurrency, turn, prior, null);
        }

        /** 默认选项。 */
        public static Options defaults() {
            return new Options(DEFAULT_CONCURRENCY, 0, null, null);
        }

        /** 串行。 */
        public static Options serial() {
            return new Options(1, 0, null, null);
        }

        /**
         * 带上轮次与先验查询（同一轮内不重复问人）。
         *
         * @param turn  本次执行所属轮次
         * @param prior 先验查询（可空 = 不查）
         * @return 新选项
         */
        public Options withPrior(int turn, ApprovalPrior prior) {
            return new Options(concurrency, turn, prior, context);
        }

        /**
         * 带上工具执行上下文（工具据此知道轮次、会话与取消令牌）。
         *
         * @param context 执行上下文（可空 = 空上下文）
         * @return 新选项
         */
        public Options withContext(ToolContext context) {
            return new Options(concurrency, turn, prior, context);
        }

        /** 生效的并发上限。 */
        public int effectiveConcurrency() {
            return concurrency <= 0 ? DEFAULT_CONCURRENCY : concurrency;
        }
    }

    /**
     * 安全纵深装配（守卫 / 钩子 / 审批 / 审计 / 执行后复核）。
     *
     * <p>全部字段可空 = 未装配 = 对应能力不启用（零开销降级，不变式 I6）。
     *
     * @param guards    守卫总线（可空）
     * @param hooks     钩子总线（可空）
     * @param approvals 审批通道（可空 = 未装配 → 需要确认时按拒绝处理）
     * @param audit     审批审计出口（可空 = 不写审计事件）
     * @param postHook  执行后复核钩子（可空）
     */
    public record Security(GuardBus guards,
                           HookBus hooks,
                           ApprovalChannel approvals,
                           ApprovalAudit audit,
                           PostHook postHook) {

        /** 什么都不装配（等价于未启用安全纵深）。 */
        public static Security none() {
            return new Security(null, null, null, null, null);
        }

        /** 是否装了任何一项。 */
        public boolean isEmpty() {
            return guards == null && hooks == null && approvals == null && audit == null && postHook == null;
        }

        /**
         * 换一个审计出口（审计是<b>会话级</b>的：事件写进当前会话的日志，
         * 而会话每轮都可能不同，所以由驱动在每轮开始时套上）。
         *
         * @param newAudit 新的审计出口
         * @return 新装配
         */
        public Security withAudit(ApprovalAudit newAudit) {
            return new Security(guards, hooks, approvals, newAudit, postHook);
        }
    }

    /**
     * 执行一批工具调用，返回与 calls 严格同序的结果。
     *
     * @param registry 工具注册表（可空 = 整批判失败，<b>不 panic</b>）
     * @param calls    模型给出的调用（按模型序）
     * @param options  执行选项
     * @return 与 calls 同序的结果
     */
    public static List<Outcome> executeBatch(ToolRegistry registry, List<ToolCall> calls, Options options) {
        return executeBatch(registry, calls, options, Security.none());
    }

    /**
     * 执行一批工具调用（带安全纵深）。
     *
     * @param registry 工具注册表（可空 = 整批判失败，<b>不 panic</b>）
     * @param calls    模型给出的调用（按模型序）
     * @param options  执行选项
     * @param security 安全纵深装配（可空 = 等同 {@link Security#none()}）
     * @return 与 calls 同序的结果
     */
    public static List<Outcome> executeBatch(ToolRegistry registry,
                                             List<ToolCall> calls,
                                             Options options,
                                             Security security) {
        return executeBatch(registry, calls, options, security, null);
    }

    /**
     * 执行一批工具调用（带安全纵深与取消令牌，T6.4 取消贯通）。
     *
     * <p><b>取消语义</b>：令牌取消后，<b>尚未开始</b>的调用不再执行
     * （回填「已取消，未执行」的错误结果，保持回填序与 assistant↔tool/result 配对）；
     * <b>正在执行</b>的调用不中断（工具各自响应令牌/中断——协作式取消，诚实声明）。
     * 无令牌（null）零开销。</p>
     *
     * @param registry 工具注册表（可空 = 整批判失败，<b>不 panic</b>）
     * @param calls    模型给出的调用（按模型序）
     * @param options  执行选项
     * @param security 安全纵深装配（可空 = 等同 {@link Security#none()}）
     * @param token    取消令牌（可空 = 不可取消）
     * @return 与 calls 同序的结果
     */
    public static List<Outcome> executeBatch(ToolRegistry registry,
                                             List<ToolCall> calls,
                                             Options options,
                                             Security security,
                                             net.hegq.mos4j.core.CancellationToken token) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        Options opts = options == null ? Options.defaults() : options;
        Security sec = security == null ? Security.none() : security;
        List<Plan> plans = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            // 已取消 → 剩余全部回填「未执行」（不再走裁决：取消是终止性的）
            if (token != null && token.isCancelled()) {
                Plan skipped = new Plan(call);
                skipped.error = "调用已取消，未执行";
                plans.add(skipped);
                continue;
            }
            plans.add(preflight(registry, call, sec, opts));
        }
        dispatch(registry, plans, opts, token);
        return finalize(plans, sec);
    }

    /** 阶段①：逐条裁决（串行，按模型调用序）。 */
    private static Plan preflight(ToolRegistry registry, ToolCall call, Security security, Options options) {
        Plan plan = new Plan(call);
        plan.context = options.context();
        if (registry == null) {
            // 注册表缺失 → 整批判失败（不 panic：不这么做会 NPE 崩掉整轮）
            plan.error = "工具注册表未装配，本次调用未执行";
            return plan;
        }
        if (registry.get(call.name()).isEmpty()) {
            plan.error = "工具未注册: " + call.name();
            return plan;
        }
        // ① 守卫（安全边界）：判定串行、单调，首个拒绝即短路。
        //    守卫先于钩子：安全判定不应发生在扩展代码之后。
        GuardDecision decision = checkGuards(call, security);
        if (decision != null) {
            switch (decision.verdict()) {
                case DENY -> {
                    plan.error = Texts.guardRejected(decision.reason());
                    return plan;
                }
                case CANCEL -> {
                    plan.error = Texts.guardRejected(decision.reason());
                    plan.terminate = true;
                    return plan;
                }
                case ASK -> plan.needsApproval = true;
                case ALLOW -> {
                    // 放行
                }
            }
        }
        // ② 钩子链（waterfall）：得到最终载荷
        HookRun hookRun = runHooks(call, security);
        if (hookRun.error() != null) {
            plan.error = hookRun.error();
            return plan;
        }
        HookEvent finalEvent = hookRun.event();
        plan.finalEvent = finalEvent;
        // ③ 载荷冻结：最终载荷必须与原始调用逐字一致
        String violation = freezeViolation(call, finalEvent);
        if (violation != null) {
            plan.error = violation;
            return plan;
        }
        // ④ 审批（需要确认时）：放在冻结检查之后——不为反正会被拒的调用打扰人
        if (plan.needsApproval) {
            String approvalError = approve(call, decision.reason(), security, options);
            if (approvalError != null) {
                plan.error = approvalError;
                return plan;
            }
        }
        plan.pending = true;
        return plan;
    }

    /** 跑钩子链的结果：要么给出最终载荷，要么给出拒绝理由。 */
    private record HookRun(HookEvent event, String error) {
    }

    /** 跑钩子链；被拒绝时给出理由。 */
    private static HookRun runHooks(ToolCall call, Security security) {
        HookBus hooks = security.hooks();
        HookEvent original = new HookEvent(call.name(), call.args(), call.id());
        if (hooks == null || hooks.size() == 0) {
            return new HookRun(original, null);
        }
        try {
            return new HookRun(hooks.run(original), null);
        } catch (HookBus.NilHookEventException e) {
            // 钩子契约破坏（返回 null）：显式报错，不让链崩在下游
            return new HookRun(null, Texts.hookRejected(e.getMessage() == null ? "钩子返回了 null 事件" : e.getMessage()));
        } catch (net.hegq.mos4j.hook.HookRejectedException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage();
            return new HookRun(null, Texts.hookRejected(reason));
        }
    }

    /** 载荷冻结检查：返回违约说明（null = 没违约）。 */
    private static String freezeViolation(ToolCall call, HookEvent event) {
        if (event == null) {
            return Texts.PAYLOAD_FROZEN;
        }
        if (!call.name().equals(event.toolName())
                || !call.args().equals(event.args())
                || !call.id().equals(event.callId())) {
            return Texts.PAYLOAD_FROZEN;
        }
        return null;
    }

    /** 守卫判定；返回 null = 放行（没有守卫或守卫放行）。 */
    private static GuardDecision checkGuards(ToolCall call, Security security) {
        GuardBus guards = security.guards();
        if (guards == null || guards.size() == 0) {
            return null;
        }
        GuardDecision decision = guards.check(new GuardEvent(call.name(), call.args(), call.id()));
        if (decision == null || decision.verdict() == GuardDecision.Verdict.ALLOW) {
            return null;
        }
        return decision;
    }

    /**
     * 审批：返回 null = 放行；返回非 null = 拒绝文案。
     *
     * <p><b>先验复用</b>：同一轮、同一次调用上已经有人批过、且那次调用仍悬空
     * → 直接复用那个决定，**不再问人**（续跑时最常见：人被问过一次，
     * 进程却被硬杀在了执行中途）。
     *
     * <p><b>未装配通道仍然 fail-closed</b>：先验命中也在通道之后查——
     * 没有问人的能力，就不能说「上次批过」算数。
     *
     * <p>审计写失败会抛异常中止整批（审计是法律事实，写不进去就不能继续做）。
     */
    private static String approve(ToolCall call, String reason, Security security, Options options) {
        ApprovalChannel channel = security.approvals();
        if (channel == null) {
            // 未装配审批通道 → 拒绝（fail-closed）。绝不「先执行再问」。
            return Texts.askWithoutChannel(reason);
        }
        // 先验复用：命中且可复用（批准 / 拒绝）就不问人。
        ApprovalPrior prior = options.prior();
        if (prior != null) {
            Optional<ApprovalPrior.Decision> hit =
                    prior.lookup(options.turn(), call.name(), call.id());
            if (hit.isPresent() && hit.get().reusable()) {
                return reusePrior(hit.get(), call, reason, security);
            }
        }
        ApprovalRequest request = new ApprovalRequest(
                "ap_" + UUID.randomUUID(), call.name(), call.id(), reason);
        ApprovalAudit audit = security.audit();
        if (audit != null) {
            audit.asked(request);
        }
        ApprovalResult result;
        try {
            result = channel.request(request);
        } catch (Exception e) {
            // 通道报错 ≠ 人工拒绝：归一到 unavailable，并且照样落一条 decided 审计
            // （否则日志里会留下「问了但没有结论」的洞）。
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            result = ApprovalResult.unavailable("审批通道异常: " + message);
        }
        if (result == null) {
            result = ApprovalResult.unavailable("无人应答");
        }
        if (audit != null) {
            audit.decided(request, result.outcome());
        }
        if (result.permits()) {
            return null;
        }
        String detail = result.reason().isEmpty() ? reason : result.reason();
        return switch (result.outcome()) {
            case APPROVED -> null;
            case REJECTED, CANCELLED -> Texts.approvalRejected(detail);
            case UNAVAILABLE -> Texts.approvalUnavailable(detail);
        };
    }

    /**
     * 复用先验决定：不问人，按被复用的结局放行或拒绝。
     *
     * <p><b>审计照样记一对 asked + decided</b>（新 id）：保持「每个决定都有配对的
     * asked」这条不变式，宿主按事件流对账不会看到孤儿。
     *
     * <p><b>「这次没重新问人」的线索写在哪</b>：写进 asked 事件的 {@code reason}
     * （事件字段清单已冻结，里面没有单独的 reusedFrom 字段——不擅自新增字段）。
     */
    private static String reusePrior(ApprovalPrior.Decision decision, ToolCall call, String reason,
                                     Security security) {
        ApprovalRequest request = new ApprovalRequest(
                "ap_" + UUID.randomUUID(), call.name(), call.id(),
                Texts.priorReused(decision.reusedFrom(), reason));
        ApprovalAudit audit = security.audit();
        if (audit != null) {
            audit.asked(request);
            audit.decided(request, decision.outcome());
        }
        if (decision.outcome().permits()) {
            return null;
        }
        // 被复用的是「拒绝」：这次仍然拒绝（不许绕的半句照旧）。
        return Texts.approvalRejected(reason);
    }

    /**
     * 阶段②：按屏障分组执行（组内有界并发）。
     *
     * <p>分组规则：连续「可并发」的调用成组；
     * 独占调用自成屏障</p>，组与组之间顺序执行。
     */
    private static void dispatch(ToolRegistry registry, List<Plan> plans, Options options,
                                 net.hegq.mos4j.core.CancellationToken token) {
        int limit = options.effectiveConcurrency();
        for (List<Integer> group : planGroups(registry, plans)) {
            if (token != null && token.isCancelled()) {
                // 整组剩余全部跳过（保持 Plan 的「未执行」标记，回填阶段统一出错误结果）
                for (Integer index : group) {
                    Plan plan = plans.get(index);
                    if (plan.pending) {
                        plan.pending = false;
                        plan.error = "调用已取消，未执行";
                    }
                }
                continue;
            }
            runGroup(registry, plans, group, limit, token);
        }
    }

    /** 按模型序切分执行组。 */
    private static List<List<Integer>> planGroups(ToolRegistry registry, List<Plan> plans) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            if (!plan.pending) {
                continue;
            }
            if (concurrencySafe(registry, plan.call)) {
                current.add(i);
                continue;
            }
            if (!current.isEmpty()) {
                groups.add(new ArrayList<>(current));
                current.clear();
            }
            groups.add(List.of(i)); // 独占 = 屏障
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** 工具是否声明本次调用可并发（默认 false = 独占，fail-closed）。 */
    private static boolean concurrencySafe(ToolRegistry registry, ToolCall call) {
        Tool tool = registry.get(call.name()).orElse(null);
        if (tool == null) {
            return false;
        }
        try {
            return tool.concurrencySafe(ToolArgs.normalize(call.args()));
        } catch (Exception e) {
            // 分类器出错 → 独占（fail-closed）
            return false;
        }
    }

    /** 执行一个组：滚动池最多 limit 个并发，组结束才进入下一组。 */
    private static void runGroup(ToolRegistry registry, List<Plan> plans, List<Integer> group, int limit,
                                 net.hegq.mos4j.core.CancellationToken token) {
        if (group.size() == 1) {
            Plan plan = plans.get(group.get(0));
            if (token != null) {
                try {
                    token.throwIfCancelled();
                } catch (java.util.concurrent.CancellationException e) {
                    plan.pending = false;
                    plan.error = "调用已取消，未执行";
                    return;
                }
            }
            execute(registry, plan);
            return;
        }
        Semaphore semaphore = new Semaphore(limit);
        List<Thread> threads = new ArrayList<>(group.size());
        for (Integer index : group) {
            Plan plan = plans.get(index);
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    if (token != null) {
                        // 取消检查在 acquire 之前：取消后不排队、不占许可
                        token.throwIfCancelled();
                    }
                    semaphore.acquire();
                    try {
                        if (token != null && token.isCancelled()) {
                            plan.pending = false;
                            plan.error = "调用已取消，未执行";
                            return;
                        }
                        execute(registry, plan);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    plan.error = "调用在派发前被中断";
                } catch (java.util.concurrent.CancellationException e) {
                    plan.pending = false;
                    plan.error = "调用已取消，未执行";
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 执行单条调用（失败也进结果，不抛异常）。 */
    private static void execute(ToolRegistry registry, Plan plan) {
        // 执行前再查一次载荷冻结（机制 M1：preflight + execute 两处校验）
        String violation = freezeViolation(plan.call, plan.finalEvent);
        if (violation != null) {
            plan.error = violation;
            return;
        }
        try {
            Object raw = registry.call(plan.call.name(), plan.call.args(), plan.context);
            plan.content = render(raw);
        } catch (Exception e) {
            plan.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** 结果渲染：字符串原样，其余按 JSON。 */
    private static String render(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String text) {
            return text;
        }
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            return String.valueOf(raw);
        }
    }

    /** 阶段③：按模型序回填 + 执行后钩子（只能加重）。 */
    private static List<Outcome> finalize(List<Plan> plans, Security security) {
        List<Outcome> outcomes = new ArrayList<>(plans.size());
        for (Plan plan : plans) {
            outcomes.add(toOutcome(plan, security));
        }
        return outcomes;
    }

    /** 单条结果 + 执行后复核（机制 M3：单向加重）。 */
    private static Outcome toOutcome(Plan plan, Security security) {
        if (plan.error != null) {
            // 已经是失败 → 保持失败（执行后钩子无权洗白）
            return new Outcome(plan.call, plan.error, true, plan.terminate);
        }
        String content = plan.content == null ? "" : plan.content;
        PostHook post = security.postHook();
        if (post != null) {
            String extra;
            try {
                extra = post.afterToolResult(plan.finalEvent, content, false);
            } catch (Exception e) {
                extra = "执行后复核异常: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            if (extra != null && !extra.isEmpty()) {
                // 成功 → 失败（加重），反向不可能
                return new Outcome(plan.call, extra, true, plan.terminate);
            }
        }
        return new Outcome(plan.call, content, false, plan.terminate);
    }

    /** 一次调用的内部计划。 */
    private static final class Plan {
        private final ToolCall call;
        private boolean pending;
        private boolean needsApproval;
        private String content;
        private String error;
        private boolean terminate;
        private HookEvent finalEvent;
        /** 工具执行上下文（轮次 / 会话 id / 取消令牌；可空 = 空上下文）。 */
        private ToolContext context;

        private Plan(ToolCall call) {
            this.call = call;
        }
    }
}
