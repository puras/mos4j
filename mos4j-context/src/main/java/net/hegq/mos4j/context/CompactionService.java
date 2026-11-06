package net.hegq.mos4j.context;

import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.session.Compaction;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Projection;
import net.hegq.mos4j.session.Session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private final CompactionPolicy policy;
    private final SummaryGenerator generator;

    /**
     * @param policy    压缩策略
     * @param generator 摘要生成器
     */
    public CompactionService(CompactionPolicy policy, SummaryGenerator generator) {
        if (policy == null || generator == null) {
            throw new IllegalArgumentException("context: 压缩服务需要策略与摘要生成器");
        }
        this.policy = policy;
        this.generator = generator;
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
            summary = generator.generate(transcript, previousSummary);
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
