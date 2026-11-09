package net.hegq.mos4j.context;

import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.session.Compaction;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Projection;
import net.hegq.mos4j.session.Session;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 压缩服务（机制 M8 / 不变式 I4）。
 *
 * <p><b>压缩是遮蔽，不是删除</b>：旧历史留在事件日志里，只是不再投影给模型。
 * 日志是事实，删了就查不出「当时到底发生了什么」。
 *
 * <p><b>触发条件</b>：{@code contextTokens > contextWindow - reserveTokens}。
 * 没配置窗口就不压（见 {@link CompactionPolicy}）。
 *
 * <p><b>切割点合法</b>：保留段不能以 tool 结果开头（见 {@link CutPointFinder}）。
 *
 * <p><b>旧摘要折叠</b>：连续两轮压缩时，上一轮摘要是 user 角色消息、
 * 以 {@code [历史摘要]} 开头。压缩时它被识别出来，作为 {@code <previous-summary>}
 * 传给摘要器，**不重复出现在对话文本里**——否则摘要会滚雪球：
 * 第二轮摘要里包含第一轮摘要，第三轮包含前两轮……
 */
public final class CompactionService {

    /**
     * 摘要调用的默认超时（3 分钟），与 Go 参考版 {@code DefaultSummaryTimeout} 同口径。
     *
     * <p>取 3 分钟的理由：摘要是<b>非流式、短、可重复</b>的调用——正常几十秒内完成；
     * 3 分钟足够覆盖慢网关与重试，又不至于让用户面对「整轮挂住」而毫无提示。
     */
    public static final Duration DEFAULT_SUMMARY_TIMEOUT = Duration.ofMinutes(3);

    private final CompactionPolicy policy;
    private final SummaryGenerator generator;
    private final Duration summaryTimeout;

    /**
     * @param policy    压缩策略
     * @param generator 摘要生成器
     */
    public CompactionService(CompactionPolicy policy, SummaryGenerator generator) {
        this(policy, generator, null);
    }

    /**
     * @param policy         压缩策略
     * @param generator      摘要生成器
     * @param summaryTimeout 摘要调用自己的超时：
     *                       <ul>
     *                         <li>{@code null} = 缺省 {@link #DEFAULT_SUMMARY_TIMEOUT}（3 分钟）</li>
     *                         <li>{@link Duration#ZERO} = <b>不限</b>（宿主自担挂住的风险）</li>
     *                         <li>其他 = 该时长</li>
     *                       </ul>
     */
    public CompactionService(CompactionPolicy policy, SummaryGenerator generator, Duration summaryTimeout) {
        if (policy == null || generator == null) {
            throw new IllegalArgumentException("context: 压缩服务需要策略与摘要生成器");
        }
        this.policy = policy;
        this.generator = generator;
        this.summaryTimeout = summaryTimeout == null ? DEFAULT_SUMMARY_TIMEOUT : summaryTimeout;
        if (this.summaryTimeout.isNegative()) {
            // 负时长没有合理语义（Future.get 遇负值行为含混），直接拒绝：
            // 「不限时」用 Duration.ZERO 明确表达。
            throw new IllegalArgumentException("context: 摘要超时不能为负: " + this.summaryTimeout);
        }
    }

    /**
     * 换一个摘要超时（返回新实例；策略与生成器不变）。
     *
     * <p>压缩服务是不可变的：换超时不改宿主手里那个对象，避免「同一个服务前后超时不一样」。
     *
     * @param summaryTimeout 新超时（{@code null} = 缺省 3 分钟，{@link Duration#ZERO} = 不限）
     * @return 新压缩服务
     */
    public CompactionService withSummaryTimeout(Duration summaryTimeout) {
        return new CompactionService(policy, generator, summaryTimeout);
    }

    /** 摘要调用超时（{@link Duration#ZERO} = 不限）。 */
    public Duration summaryTimeout() {
        return summaryTimeout;
    }

    /** 压缩策略。 */
    public CompactionPolicy policy() {
        return policy;
    }

    /**
     * 需要的话压缩一次会话。
     *
     * @param session 会话
     * @return 压缩结果
     * @throws IOException 事件落盘失败（此时压缩**未生效**，历史没被遮蔽）
     */
    public CompactionResult maybeCompact(Session session) throws IOException {
        int tokensBefore = session.contextTokens();
        if (!policy.shouldCompact(tokensBefore)) {
            return CompactionResult.skipped(tokensBefore, "未超预算或未配置上下文窗口");
        }

        List<Projection.SeqMessage> view = session.view();
        CutPointFinder.Cut cut = CutPointFinder.find(view, policy.keepRecentTokens());
        if (cut == null) {
            return CompactionResult.skipped(tokensBefore, "没有可遮蔽的历史区间（保留段已覆盖全部历史）");
        }

        // 旧摘要折叠：识别 [历史摘要] 前缀的消息
        String previousSummary = null;
        List<Projection.SeqMessage> masked = new ArrayList<>(view.subList(0, cut.index()));
        for (int i = masked.size() - 1; i >= 0; i--) {
            Message message = masked.get(i).message();
            if (message == null || message.role() != Role.USER) {
                continue;
            }
            String content = message.content();
            if (content == null || !content.startsWith(Projection.COMPACTION_SUMMARY_PREFIX)) {
                continue;
            }
            previousSummary = content.substring(Projection.COMPACTION_SUMMARY_PREFIX.length()).trim();
            // 折叠进新摘要，不再单独出现在对话文本里
            masked.remove(i);
        }

        String transcript = Transcript.render(masked, previousSummary);
        SummaryGenerator.Summary summary;
        try {
            summary = generateWithTimeout(transcript, previousSummary);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("压缩失败：摘要生成异常（历史未被遮蔽）", e);
        }
        if (summary == null || summary.text() == null || summary.text().isBlank()) {
            // 没有摘要就不遮蔽：遮蔽了历史却留不下摘要 = 旧历史彻底消失
            return CompactionResult.skipped(tokensBefore, "摘要为空，历史未被遮蔽");
        }

        Event.Builder builder = Event.builder(EventKind.COMPACTION)
                .turn(compactionTurn(session))
                .compaction(new Compaction(summary.text(), cut.firstKeptSeq(), tokensBefore));
        if (summary.usage() != null) {
            builder.usage(summary.usage());
        }
        session.append(builder.build());

        return new CompactionResult(true, cut.firstKeptSeq(), summary.text(), tokensBefore, List.of());
    }

    /**
     * 在超时内生成摘要。
     *
     * <p><b>为什么要自己掐超时</b>：宿主的轮次上下文通常只有「取消」没有「期限」。
     * 网关半开（上游挂起）时摘要请求会永远等下去，整轮不返回，宿主拿不到 {@code turn/end}，
     * 界面只能显示「正在处理」——用户分不清是模型在思考、在限流重试，还是连接已经死了。
     * 摘要是<b>非流式、短、可重复</b>的调用，自带超时才能让这一轮<b>确定性地收尾</b>。
     *
     * <p>实现用虚拟线程执行摘要调用，主线程限时等待；超时后中断该虚拟线程
     * （协作式取消，与框架统一的取消口径一致）并按失败处理（交给
     * {@link CompactionFailurePolicy} 决定终止本轮还是跳过继续）。
     *
     * @param transcript      待摘要文本
     * @param previousSummary 上一轮摘要（可空）
     * @return 摘要结果
     * @throws IOException 摘要超时或生成失败
     */
    private SummaryGenerator.Summary generateWithTimeout(String transcript, String previousSummary)
            throws IOException {
        if (summaryTimeout.isZero()) {
            try {
                return generator.generate(transcript, previousSummary);
            } catch (Exception e) {
                throw new IOException("压缩失败：摘要生成异常（历史未被遮蔽）", e);
            }
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<SummaryGenerator.Summary> future =
                    executor.submit(() -> generator.generate(transcript, previousSummary));
            try {
                return future.get(summaryTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("压缩失败：摘要生成超时（限时 " + summaryTimeout + "，历史未被遮蔽）", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw new IOException("压缩失败：摘要生成异常（历史未被遮蔽）", ex);
                }
                throw new IOException("压缩失败：摘要生成异常（历史未被遮蔽）", cause);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("压缩失败：摘要生成被中断（历史未被遮蔽）", e);
            }
        } finally {
            // 不等待：被取消的摘要线程若不响应中断，不能拖住本轮收尾。
            executor.shutdownNow();
        }
    }

    /**
     * 压缩事件归属的轮次。
     *
     * <p>进行中的轮次用「日志里最后一轮」（压缩发生在某个 turn 之内）；
     * 没有进行中的轮次时用下一轮。用 {@code nextTurn()} 无条件取下一轮会把事件
     * 挂到一个还不存在的未来轮次上——审计时看着像「下一轮的事」。
     */
    private static int compactionTurn(Session session) {
        List<Event> events = session.events();
        int max = 0;
        for (Event event : events) {
            max = Math.max(max, event.turn());
        }
        if (!events.isEmpty() && events.get(events.size() - 1).kind() != EventKind.TURN_END) {
            return max > 0 ? max : session.nextTurn();
        }
        return session.nextTurn();
    }
}
