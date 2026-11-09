package net.hegq.mos4j.driver;

import net.hegq.mos4j.context.CompactionFailurePolicy;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.core.ToolContext;
import net.hegq.mos4j.execpath.ExecPath;
import net.hegq.mos4j.execpath.Outcome;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.session.SessionApprovalAudit;
import net.hegq.mos4j.session.SessionApprovalPrior;
import net.hegq.mos4j.session.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * ReAct 循环驱动：无状态，可并发复用。
 *
 * <p><b>事件序列（冻结）</b>：</p>
 * <pre>
 * turn/start → user → (assistant → tool/call* → tool/result*)* → assistant → turn/end
 * </pre>
 *
 * <p><b>关键约定</b>：</p>
 * <ul>
 *   <li><b>先把 tool/call 按模型顺序全部落日志，再执行、再按序回填 tool/result</b>——
 *       seq 连续可重放；反过来（先执行后记调用）会让日志与实际执行对不上。</li>
 *   <li><b>截断的工具调用 fail-closed 拒绝执行</b>：参数可能是半截 JSON。</li>
 *   <li><b>能力缺口 fail-closed 且不写 assistant 事件</b>：模型要调工具但没装注册表时，
 *       先收尾再报错——否则日志里会留下带 ToolCalls 却无 tool/result 的非法历史。</li>
 *   <li><b>连续相同调用只提醒不拦</b>（advisory）：空转靠提醒模型改变策略。</li>
 *   <li><b>取消不留孤儿 turn</b>：被取消的轮次以 turn/end{interrupted} 收尾，
 *       取消先于落盘检查（已取消的调用不应在日志里留下半个 turn）。</li>
 * </ul>
 */
public final class ReActDriver implements LoopDriver {

    /** 模型请求工具调用但未装配工具注册表。 */
    public static final String ERR_TOOLS_UNSUPPORTED = "react: 模型请求工具调用，但 RunContext.tools 未装配";

    /** 单轮决策次数超出上限。 */
    public static final String ERR_MAX_DECISION_ROUNDS = "react: 单轮决策次数超出上限（工具可能在空转）";

    /** 连续相同调用的提醒节奏：第 3 次起提醒，之后每 3 次再提一次。 */
    private static final int REPEAT_REMIND_AT = 3;
    private static final int REPEAT_REMIND_EVERY = 3;

    @Override
    public String run(RunContext rt, Session session, String input) throws IOException {
        return run(rt, session, input, CancellationToken.none());
    }

    /**
     * 执行一轮（带取消）。
     *
     * <p><b>取消语义</b>：协作式。令牌在四个点上生效——轮次开始前（不再调模型）、
     * 决策器调用（令牌直传到模型，可真中止 HTTP）、工具批次（未开始的调用不执行，
     * 回填「已取消，未执行」保持配对）、收尾前（<b>取消优先于完成</b>：宿主叫停后
     * 本轮不产出完成结果，答案仍落日志）。<b>正在执行的工具不中断</b>（诚实声明）。
     *
     * @param cancellation 取消令牌（可空 = 不可取消）
     * @throws CancellationException 已取消（先写 turn/end{interrupted} 平衡日志再抛）
     */
    @Override
    public String run(RunContext rt, Session session, String input, CancellationToken cancellation) throws IOException {
        rt.validate();
        // 取消先于落盘：已经取消的调用不应该在日志里留下半个 turn。
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }
        int turn = session.nextTurn();
        prepareSkill(rt, session, turn);

        session.append(Event.builder(EventKind.TURN_START).turn(turn).build());
        session.append(Event.builder(EventKind.USER).turn(turn).text(input).build());

        return loop(rt, session, turn, cancellation);
    }

    /**
     * 续跑：沿用原轮次号，把那一轮的悬空调用接着走完。
     *
     * <p><b>前提：宿主显式指示</b>。框架不自动拾起挂起的轮次——没人指示就没人续。
     *
     * <p><b>四条语义</b>：
     * <ul>
     *   <li><b>沿用原轮次号</b>：审批决定的复用键是 {@code (turn, callId)}，轮次号一变就匹配不上；</li>
     *   <li><b>载荷原样重放</b>：调用 id / 工具名 / 参数都来自日志，不重写（载荷冻结契约）；</li>
     *   <li><b>走普通安全链</b>：守卫裁决 / 审批 / 调度语义与正常轮一致——续跑不是特权路径；</li>
     *   <li><b>回填真实结果</b>：写回真的 {@code tool/result}（不是投影里那种合成失败回执）。</li>
     * </ul>
     *
     * <p><b>没有可续的就不收尾那一轮</b>：挂起的事实还在，宿主稍后续得上。
     *
     * @param options 续跑参数（轮次 / 要续的调用 id；可空 = 自动选最后一个被打断的轮次）
     */
    @Override
    public String resume(RunContext rt, Session session, ResumeOptions options, CancellationToken cancellation)
            throws IOException, NothingToResumeException {
        rt.validate();
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }
        ResumeOptions opts = options == null ? ResumeOptions.latest() : options;
        int turn = opts.turn() > 0 ? opts.turn() : session.interruptedTurn();
        if (turn <= 0) {
            throw new NothingToResumeException("会话里没有被打断的轮次，没有可续跑的悬空调用");
        }
        prepareSkill(rt, session, turn);

        // 悬空调用：载荷来自日志（id / 名字 / 参数原样重放）。
        List<ToolCall> calls = hangingCalls(session, turn, opts.calls());
        if (calls.isEmpty()) {
            // 那一轮保持挂起：不收尾（收尾等于把「还欠一次调用」的事实抹掉）。
            throw new NothingToResumeException("第 " + turn + " 轮没有悬空调用可续（都已回填）");
        }
        List<Outcome> outcomes;
        try {
            outcomes = ExecPath.executeBatch(rt.tools(), calls, execOptions(rt, session, turn, cancellation),
                    security(rt, session, turn), cancellation);
        } catch (RuntimeException e) {
            // 审计写失败等异常 → 以 turn/end{error} 收尾再抛（不留孤儿 turn）
            return abort(session, turn, e);
        }
        for (Outcome outcome : outcomes) {
            session.append(Event.builder(EventKind.TOOL_RESULT).turn(turn)
                    .toolResult(new ToolResult(outcome.call().id(), outcome.content(), outcome.isError()))
                    .build());
        }
        // 续跑完成后继续决策 → 那一轮正常收尾。
        return loop(rt, session, turn, cancellation);
    }

    /**
     * 决策循环：决策 → 产出作答则收尾，否则执行工具回填 → 再决策。
     *
     * <p>正常轮与续跑轮共用这一段：续跑不是另一条循环，只是前面多了「把悬空调用补完」。
     */
    private String loop(RunContext rt, Session session, int turn, CancellationToken cancellation) throws IOException {
        String lastCallKey = null;
        int repeats = 0;

        int maxRounds = rt.maxDecisionRounds();
        for (int round = 0; maxRounds <= 0 || round < maxRounds; round++) {
            Action action;
            try {
                // 取消检查：每轮决策前。漏检的后果是「已取消的轮次继续调模型」——
                // 宿主以为停了，其实还在烧 token。
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                // 上下文压缩：超预算时先摘要并遮蔽旧历史，模型看到的是压缩后的对话。
                // 压缩只改投影，不改日志；摘要失败则本轮以错误收尾（历史没被动过）——
                // 除非宿主选了「跳过压缩继续」（失败策略由 RunContext 定）。
                if (rt.compaction() != null) {
                    compact(session, rt);
                }
                // 工具清单每轮决策前重新组装：上一轮激活的 deferred 工具本轮进入清单
                // （「激活下一轮生效」的落点）；schema 取注册时缓存，重组装不走反射。
                List<net.hegq.mos4j.model.Tool> defs =
                        rt.tools() == null ? List.of() : rt.tools().list();
                action = decide(rt, session, defs, cancellation);
            } catch (CancellationException ignored) {
                // 取消不是错误：用 interrupted 收尾（不新增 reason 取值），再抛给宿主。
                return abortCancelled(session, turn, cancellation);
            } catch (RuntimeException e) {
                // 决策器报错（如服务端 500、超时）→ 以 turn/end{error} 收尾再抛。
                // 不收尾就会留下孤儿 turn（turn/start 无 turn/end），恢复时只能合成 interrupted，
                // 丢掉真实原因。
                return abort(session, turn, e);
            } catch (IOException e) {
                // 压缩落盘失败等 I/O 异常 → 同样先收尾再抛
                return abort(session, turn, new IllegalStateException(
                        "react: 上下文压缩失败（历史未被遮蔽）: " + e.getMessage(), e));
            }

            // 兑底：历史里调用过的 deferred 工具并入激活集（会话恢复后激活集是空的）。
            // 在执行前并入：本 batch 里模型调的工具下一轮就会出现在清单里。
            if (rt.tools() != null && action.hasToolCalls()) {
                rt.tools().admitHistoricalTools(
                        action.toolCalls().stream().map(ToolCall::name).toList());
            }

            // 装配缺口：要求了工具却没装注册表。先收尾再报错，且不写 assistant——
            // 否则日志里会留下带 ToolCalls 却无 tool/result 的非法历史。
            if (action.hasToolCalls() && rt.tools() == null) {
                return abort(session, turn,
                        new IllegalStateException(ERR_TOOLS_UNSUPPORTED + ": " + action.toolCalls().get(0).name()));
            }

            appendAssistant(session, turn, action);

            // 无工具调用 = 产出最终作答，收尾
            if (!action.hasToolCalls()) {
                // 取消优先于「完成」：宿主叫停后，本轮不再产出完成结果。
                // 答案已经落在日志里（事实不丢），但宿主拿到的是取消异常而不是答案——
                // 否则「叫停」会被静默吞掉（宿主以为停了，其实拿回了旧结果）。
                if (cancellation != null && cancellation.isCancelled()) {
                    return abortCancelled(session, turn, cancellation);
                }
                session.append(Event.builder(EventKind.TURN_END).turn(turn).reason("completed").build());
                return action.content();
            }

            // 先把 tool/call 按模型顺序全部落日志，再执行、再按序回填 tool/result
            for (ToolCall call : action.toolCalls()) {
                session.append(Event.builder(EventKind.TOOL_CALL).turn(turn).toolCall(call).build());
            }

            // 截断的输出带的工具调用不可信：参数可能是半截 JSON。fail-closed：
            // 不执行，逐条回填错误结果（保持 assistant↔tool/result 配对合法）。
            if (action.truncated()) {
                for (ToolCall call : action.toolCalls()) {
                    session.append(Event.builder(EventKind.TOOL_RESULT).turn(turn)
                            .toolResult(new ToolResult(call.id(),
                                    "模型输出被截断（finish_reason=" + action.finishReason()
                                            + "），此工具调用的参数可能不完整，已拒绝执行。请重新发起该调用",
                                    true))
                            .build());
                }
                continue;
            }

            List<Outcome> outcomes;
            try {
                outcomes = ExecPath.executeBatch(rt.tools(), action.toolCalls(),
                        execOptions(rt, session, turn, cancellation), security(rt, session, turn), cancellation);
            } catch (RuntimeException e) {
                // 审计写失败等异常 → 以 turn/end{error} 收尾再抛（不留孤儿 turn）
                return abort(session, turn, e);
            }
            boolean terminated = false;
            for (Outcome outcome : outcomes) {
                String content = outcome.content();
                // 连续相同调用：计数并在阈值时附提醒（advisory，不拦）。
                String key = outcome.call().name() + "\u0000" + outcome.call().args();
                if (key.equals(lastCallKey)) {
                    repeats++;
                } else {
                    lastCallKey = key;
                    repeats = 1;
                }
                if (repeats >= REPEAT_REMIND_AT && (repeats - REPEAT_REMIND_AT) % REPEAT_REMIND_EVERY == 0) {
                    content += "\n\n[提醒] 这已是连续第 " + repeats + " 次发起完全相同的调用。"
                            + "若它一直失败或结果不符预期，请改变策略（换方法/先收集更多信息），"
                            + "或直接向用户说明现状。";
                }
                // 输出限流：先截断再落盘，日志里记的是「模型实际看到的内容 + 完整内容在哪」。
                // 不限流时（未装配限流器）内容原样进日志。
                if (rt.outputLimiter() != null) {
                    ToolOutputLimiter.LimitedOutput limited =
                            rt.outputLimiter().limit(outcome.call().name(), outcome.call().id(), content);
                    content = limited.content();
                }
                session.append(Event.builder(EventKind.TOOL_RESULT).turn(turn)
                        .toolResult(new ToolResult(outcome.call().id(), content, outcome.isError()))
                        .build());
                if (outcome.terminate()) {
                    terminated = true;
                }
            }
            if (terminated) {
                session.append(Event.builder(EventKind.TURN_END).turn(turn)
                        .reason("terminated")
                        .error(terminateDetail(outcomes))
                        .build());
                return action.content();
            }
        }
        // 循环退出：取消优先于「轮次用尽」的诊断（取消是宿主的决定，不是模型的失败）。
        if (cancellation != null && cancellation.isCancelled()) {
            return abortCancelled(session, turn, cancellation);
        }
        return abort(session, turn, new IllegalStateException(ERR_MAX_DECISION_ROUNDS));
    }

    /**
     * 技能是轮级状态：每轮装一份新的激活状态，并把本轮会话的审计出口接上。
     *
     * <p>不换状态：「首次激活」会跳轮泄漏（上一轮加载过的技能这一轮不算新激活）；
     * 不换审计：技能事件会写进别的会话（工具实例是长生命的，会话不是）。
     */
    private static void prepareSkill(RunContext rt, Session session, int turn) {
        if (rt.skillTool() != null) {
            rt.skillTool().setState(new net.hegq.mos4j.tool.skill.SkillState());
            rt.skillTool().setAudit(new net.hegq.mos4j.session.SessionSkillAudit(session, turn));
        }
    }

    /**
     * 执行选项：带上轮次、先验查询与工具执行上下文。
     *
     * <p>先验查询从会话日志派生（{@code (turn, callId)} 上已有决定 + 那次调用仍悬空），
     * 命中就复用那个决定、不再问人。
     *
     * <p>工具执行上下文：工具据此知道「自己是在哪一轮、哪个会话里被调用的」，
     * 并继承本轮的取消令牌（子代理靠它派生子会话、随父轮次一起停）。
     */
    private static ExecPath.Options execOptions(RunContext rt, Session session, int turn,
                                                CancellationToken cancellation) {
        ToolContext context = ToolContext.of(turn, session.id()).withCancellation(cancellation);
        return new ExecPath.Options(rt.concurrency(), turn, new SessionApprovalPrior(session), context);
    }

    /** 审计出口是会话级的：每轮套上当前会话，审批事件写进本会话日志。 */
    private static ExecPath.Security security(RunContext rt, Session session, int turn) {
        return rt.security().withAudit(new SessionApprovalAudit(session, turn));
    }

    /**
     * 要续的悬空调用（按日志序）：载荷原样重放。
     *
     * @param ids 要续的调用 id（空 = 该轮全部悬空调用）
     */
    private static List<ToolCall> hangingCalls(Session session, int turn, List<String> ids) {
        java.util.Set<String> want = ids == null || ids.isEmpty()
                ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(ids);
        List<ToolCall> calls = new java.util.ArrayList<>();
        for (net.hegq.mos4j.session.HangingCall hanging : session.hangingCalls()) {
            if (hanging.turn() != turn) {
                continue;
            }
            if (!want.isEmpty() && !want.contains(hanging.callId())) {
                continue;
            }
            calls.add(new ToolCall(hanging.callId(), hanging.toolName(), hanging.args()));
        }
        return calls;
    }

    /** 决策：走流式还是非流式。 */
    private Action decide(RunContext rt, Session session, List<net.hegq.mos4j.model.Tool> defs,
                          CancellationToken cancellation) {
        if (rt.stream() && rt.planner() instanceof ReActPlanner react) {
            return react.nextActionStream(rt.history(session), defs,
                    rt.onChunk() == null ? chunk -> {
                    } : rt.onChunk(), cancellation);
        }
        return rt.planner().nextAction(rt.history(session), defs, cancellation);
    }

    /** 记录本次决策（作答或附带工具调用的思考），思维链与用量一并落库。 */
    private static void appendAssistant(Session session, int turn, Action action) throws IOException {
        Event.Builder builder = Event.builder(EventKind.ASSISTANT)
                .turn(turn)
                .text(action.content())
                .reasoning(action.reasoningContent())
                .usage(action.usage());
        if (action.hasToolCalls()) {
            builder.toolCalls(action.toolCalls());
        }
        session.append(builder.build());
    }

    /**
     * 上下文压缩（超预算时摘要并遮蔽旧历史）。
     *
     * <p><b>失败怎么办由策略定</b>：
     * <ul>
     *   <li>{@link CompactionFailurePolicy#FAIL_CLOSED}（缺省）：失败 → 抛出 → 本轮以错误收尾
     *       （历史未被遮蔽，事实仍在日志里）；</li>
     *   <li>{@link CompactionFailurePolicy#SKIP_AND_CONTINUE}：失败 → 跳过压缩、通知宿主、本轮继续。
     *       上下文可能已超预算——<b>风险由宿主自担</b>。</li>
     * </ul>
     *
     * <p>失败原因只经 {@code onCompactionFailure} 回调交给宿主，<b>不写会话事件</b>：
     * 事件字段冻结清单里没有承载「失败原因」的字段，写半截压缩事件会让投影
     * 误以为「历史被遮蔽了但没摘要」。详见 {@link CompactionFailurePolicy} 的说明。
     */
    private static void compact(Session session, RunContext rt) throws IOException {
        try {
            rt.compaction().maybeCompact(session);
        } catch (IOException | RuntimeException e) {
            if (rt.compactionFailurePolicy() != CompactionFailurePolicy.SKIP_AND_CONTINUE) {
                throw e;
            }
            Consumer<Throwable> notify = rt.onCompactionFailure();
            if (notify != null) {
                notify.accept(e);
            }
        }
    }

    /** 出错收尾：以 turn/end{error} 平衡日志，再把错误抛给宿主。 */
    private static String abort(Session session, int turn, RuntimeException cause) throws IOException {
        session.append(Event.builder(EventKind.TURN_END)
                .turn(turn)
                .reason("error")
                .error(Session.errorDetail(cause))
                .build());
        throw cause;
    }

    /**
     * 取消收尾：以 turn/end{interrupted} 平衡日志，再抛 {@link CancellationException}。
     *
     * <p>reason 复用已冻结的 {@code interrupted} 取值（不新增 reason 值），
     * 具体原因写在 error 里——审计能分清「是人叫停的」而不是「跑挂了」。
     *
     * <p>宿主带了取消原因时，原因原文写进 error（Go 的 {@code CancelCause} 同口径）：
     * 「用户点了停止」与「上游超时」对账与 UX 完全不同，只写「已取消」等于把
     * 两件事混成一类。
     */
    private static String abortCancelled(Session session, int turn,
            CancellationToken cancellation) throws IOException {
        String detail = "操作已取消：宿主请求中止本轮（协作式取消）";
        Throwable cause = cancellation == null ? null : cancellation.cause();
        if (cause != null) {
            String message = cause.getMessage();
            detail = detail + "：" + (message == null ? cause.toString() : message);
        }
        session.append(Event.builder(EventKind.TURN_END)
                .turn(turn)
                .reason("interrupted")
                .error(detail)
                .build());
        throw new CancellationException(detail);
    }

    /** 终止详情（把被终止的那次调用写进日志，便于事后追查）。 */
    private static String terminateDetail(List<Outcome> outcomes) {
        for (Outcome outcome : outcomes) {
            if (outcome.terminate()) {
                return "本轮被终止于工具调用 " + outcome.call().name();
            }
        }
        return "本轮被终止";
    }
}
