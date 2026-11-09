package net.hegq.mos4j.session;

import net.hegq.mos4j.model.InputTokenDetails;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.OutputTokenDetails;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.model.UsageDetails;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 一个会话：append-only 事件日志 + 投影。
 *
 * <p><b>并发契约</b>：一个 Session 同一时刻只允许一个 Run；{@code append} 内部加锁
 * 仅防误用，不承载多写者语义。</p>
 *
 * <p><b>fail-fast（不变式 I2：日志即事实）</b>：{@link #append} 先落盘再进内存——
 * 落盘失败则事件<b>不进内存</b>并向上抛错。顺序反了会怎样：内存里有、磁盘上没有，
 * 事件「看起来发生过」但恢复后消失，审计链断在半路。</p>
 */
public final class Session {

    /** 记入日志的错误详情长度上限（超出部分截断）。 */
    public static final int MAX_ERROR_DETAIL = 4096;

    private final String id;
    private final Persistence persist;
    private final List<Event> events = new ArrayList<>();
    private final Set<String> claims = new LinkedHashSet<>();
    private final Object lock = new Object();
    private long seq;
    private boolean headerWritten;

    /**
     * @param id      会话 id
     * @param persist 持久化后端（可空 = 纯内存）
     */
    public Session(String id, Persistence persist) {
        this.id = id;
        this.persist = persist;
    }

    /** 会话 id。 */
    public String id() {
        return id;
    }

    /**
     * 持久化后端（可空 = 纯内存）。
     *
     * <p>给「派生会话」用：子代理要开独立子会话，用<b>同一个后端</b>开，
     * 子会话日志与父会话日志落在同一处（宿主查得到子代理干了什么）。
     *
     * @return 持久化后端
     */
    public Persistence persistence() {
        return persist;
    }

    /**
     * 追加一条事件：填充 Seq 与 Time 后落盘并入日志。
     *
     * @param event 待追加事件（seq/time 会被覆盖）
     * @return 落盘后的事件（带 seq 与 time）
     * @throws IOException 持久化失败——此时事件<b>未</b>进入内存
     */
    public Event append(Event event) throws IOException {
        synchronized (lock) {
            Event filled = new Event(seq + 1, event.kind(), event.turn(),
                    event.time() == null || event.time().isEmpty() ? now() : event.time(),
                    event.text(), event.reasoning(), event.toolCalls(), event.toolCall(),
                    event.toolResult(), event.usage(), event.reason(), event.approval(),
                    event.skill(), event.compaction(), event.error());
            if (persist != null) {
                // 首行 Header：写一次即可（后端实现幂等）
                if (!headerWritten) {
                    persist.saveHeader(id, Header.of(id));
                    headerWritten = true;
                }
                // fail-fast：落盘失败 → 事件不进内存
                persist.append(id, filled);
            }
            seq = filled.seq();
            events.add(filled);
            return filled;
        }
    }

    /** 事件日志的副本（修改副本不影响日志）。 */
    public List<Event> events() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    /**
     * 认领一个**会话级一次性标记**。
     *
     * <p>首次认领返回 {@code true}，之后一律返回 {@code false}。用来做「每会话只做一次」的事：
     * 比如把 MCP 服务器的说明注入模型（注入一次就不再重复，避免刷屏）。
     *
     * <p><b>标记只在内存里</b>，不落盘：会话从日志恢复后标记会丢，
     * 那类一次性注入会再发生一次。这是刻意选择——把标记写进日志就要新增事件类型，
     * 而事件字段清单是冻结的；用「多注入一次」换「不动冻结清单」更划算。
     *
     * @param key 标记键（不能为空）
     * @return true = 本次认领成功（此前未被认领）
     */
    public boolean claim(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("session: claim 的键不能为空");
        }
        synchronized (lock) {
            return claims.add(key);
        }
    }

    /** 已认领的标记（快照，宿主可查）。 */
    public Set<String> claims() {
        synchronized (lock) {
            return new LinkedHashSet<>(claims);
        }
    }

    /**
     * 「问了但还没答」的审批请求（派生：{@code approval/asked} 有、{@code approval/decided} 无，按 id 配对）。
     *
     * <p>顺序按日志顺序（先问的在前）。已决的不在里面——「未决」= 宿主可能还得去问人的那批。
     *
     * <p><b>纯读</b>：不写日志、不改状态、不推进序号——派生视图随时可重算。
     *
     * @return 未决审批（快照）
     */
    public List<Approval> pendingApprovals() {
        synchronized (lock) {
            List<Approval> asked = new ArrayList<>();
            Set<String> decided = new LinkedHashSet<>();
            for (Event event : events) {
                Approval approval = event.approval();
                if (approval == null) {
                    continue;
                }
                if (event.kind() == EventKind.APPROVAL_ASKED) {
                    asked.add(approval);
                } else if (event.kind() == EventKind.APPROVAL_DECIDED) {
                    decided.add(approval.id());
                }
            }
            List<Approval> pending = new ArrayList<>();
            for (Approval approval : asked) {
                if (!decided.contains(approval.id())) {
                    pending.add(approval);
                }
            }
            return pending;
        }
    }

    /**
     * 某次审批的结果（{@code approved} / {@code rejected} / {@code cancelled} / {@code unavailable}）。
     *
     * <p>按 <b>id</b> 查（不是按轮次）：id 是审批请求的身份（asked 与 decided 共用）。
     *
     * @param id 审批 id
     * @return 结果；没有这次决定时为空
     */
    public Optional<String> approvalOutcome(String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        synchronized (lock) {
            for (Event event : events) {
                Approval approval = event.approval();
                if (event.kind() == EventKind.APPROVAL_DECIDED
                        && approval != null && id.equals(approval.id())) {
                    return Optional.ofNullable(approval.outcome());
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 「同一轮、同一次调用」上已有的审批决定（先验复用用）。
     *
     * <p>匹配键是 {@code (turn, callId)}——<b>不是 id</b>：每次审批请求都会签发新 id，
     * 拿 id 匹配等于永远匹配不上。同轮同调用上若有多次决定，取<b>最后一次</b>
     * （最新的一次决定最贴近当下的意图）。
     *
     * @param turn   轮次号
     * @param callId 调用 id
     * @return 最后一次决定；没有时为空
     */
    public Optional<Approval> approvalDecision(int turn, String callId) {
        if (callId == null || callId.isEmpty()) {
            return Optional.empty();
        }
        synchronized (lock) {
            Approval found = null;
            for (Event event : events) {
                Approval approval = event.approval();
                if (event.kind() != EventKind.APPROVAL_DECIDED || approval == null) {
                    continue;
                }
                if (event.turn() != turn || !callId.equals(approval.callId())) {
                    continue;
                }
                found = approval;
            }
            return Optional.ofNullable(found);
        }
    }

    /**
     * 「被中断的那一轮」（有 {@code turn/start}、且没<b>正常</b>收尾的最后一轮）。
     * 0 = 没有被中断的轮次。
     *
     * <p><b>中断 = 没结束 或 以 {@code interrupted} 收尾</b>：重开会话时恢复会给孤儿
     * turn 合成一条 {@code turn/end{reason:"interrupted"}}（只补事件、不回写日志）——
     * 那一轮<b>仍然是</b>被中断的（工具调用悬空），续跑要认它。
     *
     * <p>续跑要沿用<b>同一轮</b>的轮次号：审批决定的复用键是 {@code (turn, callId)}，
     * 轮次号一变就匹配不上。
     *
     * @return 被中断的轮次号；没有时为 0
     */
    public int interruptedTurn() {
        synchronized (lock) {
            Map<Integer, Boolean> started = new LinkedHashMap<>();
            Map<Integer, String> ended = new LinkedHashMap<>();
            for (Event event : events) {
                if (event.kind() == EventKind.TURN_START) {
                    started.put(event.turn(), Boolean.TRUE);
                } else if (event.kind() == EventKind.TURN_END) {
                    ended.put(event.turn(), event.reason());
                }
            }
            int best = 0;
            for (Integer turn : started.keySet()) {
                if (turn <= best) {
                    continue;
                }
                String reason = ended.get(turn);
                // 正常收尾（completed / error / terminated）的轮次不算被中断
                if (reason != null && !"interrupted".equals(reason)) {
                    continue;
                }
                best = turn;
            }
            return best;
        }
    }

    /**
     * 「声明了但没有结果」的调用（派生：{@code tool/call} 有、{@code tool/result} 无，按 CallID 配对）。
     *
     * <p>顺序按日志顺序（先声明的在前）。已回填的不在里面。
     *
     * <p><b>纯读</b>：不写日志、不改状态、不推进序号。
     *
     * @return 悬空调用（快照）
     */
    public List<HangingCall> hangingCalls() {
        synchronized (lock) {
            List<HangingCall> calls = new ArrayList<>();
            Set<String> answered = new LinkedHashSet<>();
            for (Event event : events) {
                if (event.kind() == EventKind.TOOL_CALL) {
                    net.hegq.mos4j.model.ToolCall call = event.toolCall();
                    if (call == null || call.id() == null || call.id().isEmpty()) {
                        continue;
                    }
                    calls.add(new HangingCall(event.turn(), call.id(), call.name(),
                            call.args(), event.seq()));
                } else if (event.kind() == EventKind.TOOL_RESULT) {
                    ToolResult result = event.toolResult();
                    if (result != null && result.callId() != null) {
                        answered.add(result.callId());
                    }
                }
            }
            List<HangingCall> hanging = new ArrayList<>();
            for (HangingCall call : calls) {
                if (!answered.contains(call.callId())) {
                    hanging.add(call);
                }
            }
            return hanging;
        }
    }

    /** 把事件日志投影为对话消息（派生而非存储，可随时重算）。 */
    public List<Message> messages() {
        synchronized (lock) {
            return Projection.project(events);
        }
    }

    /** 当前投影视图（消息 + 事件序号）：压缩器用它定切割点。 */
    public List<Projection.SeqMessage> view() {
        synchronized (lock) {
            return Projection.projectSeq(events);
        }
    }

    /** 下一轮的轮次序号（派生：日志中已出现的最大 Turn + 1）。 */
    public int nextTurn() {
        synchronized (lock) {
            int max = 0;
            for (Event event : events) {
                if (event.turn() > max) {
                    max = event.turn();
                }
            }
            return max + 1;
        }
    }

    /**
     * 折叠日志中所有事件的模型调用用量（派生值，不存储）。
     *
     * <p>三个主计数直接相加；<b>明细也累计</b>（缓存读/写、推理 token）——
     * 不然缓存命中会掩在「输入」里看不出来。</p>
     */
    public Usage totalUsage() {
        synchronized (lock) {
            int prompt = 0;
            int fresh = 0;
            int completion = 0;
            int total = 0;
            int cacheRead = 0;
            int cacheWrite = 0;
            int audioIn = 0;
            int reasoning = 0;
            int audioOut = 0;
            for (Event event : events) {
                Usage usage = event.usage();
                if (usage == null) {
                    continue;
                }
                prompt += usage.promptTokens();
                fresh += usage.freshInputTokens();
                completion += usage.completionTokens();
                total += usage.totalTokens();
                if (usage.details() != null && usage.details().input() != null) {
                    cacheRead += usage.details().input().cacheRead();
                    cacheWrite += usage.details().input().cacheWrite();
                    audioIn += usage.details().input().audio();
                }
                if (usage.details() != null && usage.details().output() != null) {
                    reasoning += usage.details().output().reasoning();
                    audioOut += usage.details().output().audio();
                }
            }
            return new Usage(prompt, fresh, completion, total,
                    new UsageDetails(new InputTokenDetails(cacheRead, cacheWrite, audioIn),
                            new OutputTokenDetails(reasoning, audioOut)));
        }
    }

    /**
     * 估算当前投影视图的上下文大小（压缩触发用）。
     *
     * <p><b>两段式</b>：基座是真实值——最后一次模型调用的返回用量；
     * 它之后的新增事件还没有权威数字，按 chars/4 估算。完全没有用量记录时全量估算。
     * 这是量级正确的启发式，不是分词。</p>
     */
    public int contextTokens() {
        synchronized (lock) {
            int base = 0;
            int baseIdx = -1;
            for (int i = events.size() - 1; i >= 0; i--) {
                Usage usage = events.get(i).usage();
                if (usage == null) {
                    continue;
                }
                if (usage.totalTokens() > 0) {
                    base = usage.totalTokens();
                } else {
                    int read = usage.details() == null || usage.details().input() == null
                            ? 0 : usage.details().input().cacheRead();
                    int write = usage.details() == null || usage.details().input() == null
                            ? 0 : usage.details().input().cacheWrite();
                    base = usage.freshInput() + read + write + usage.completionTokens();
                }
                baseIdx = i;
                break;
            }
            for (int i = baseIdx + 1; i < events.size(); i++) {
                base += estimateEventTokens(events.get(i));
            }
            return base;
        }
    }

    /**
     * 估算单条事件进入上下文的 token 量（chars/4）。
     *
     * <p>{@code tool/call} 审计事件不计：它不进投影，工具调用已随 assistant 事件的
     * ToolCalls 计过——重复计会让压缩触发偏早。</p>
     */
    private static int estimateEventTokens(Event event) {
        int n = length(event.text()) + length(event.reasoning());
        if (event.toolResult() != null) {
            n += length(event.toolResult().content());
        }
        if (event.toolCalls() != null) {
            for (ToolCall call : event.toolCalls()) {
                n += length(call.name()) + length(call.args());
            }
        }
        if (event.compaction() != null) {
            n += length(event.compaction().summary());
        }
        return (n + 3) / 4;
    }

    /** 用 chars/4 启发式估算文本 token 数。 */
    public static int estimateTokens(String text) {
        return (length(text) + 3) / 4;
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** 当前时间（RFC3339 UTC 文本）。 */
    public static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * 只进内存的追加（恢复时合成的事件用；<b>不回写日志</b>）。
     *
     * <p>不这么做会出什么事：合成事件被写回磁盘 → 日志里多出一条从未发生过的记录，
     * 审计链出现「幽灵事件」。</p>
     */
    Event appendInMemory(Event event) {
        synchronized (lock) {
            Event filled = new Event(seq + 1, event.kind(), event.turn(),
                    event.time() == null || event.time().isEmpty() ? now() : event.time(),
                    event.text(), event.reasoning(), event.toolCalls(), event.toolCall(),
                    event.toolResult(), event.usage(), event.reason(), event.approval(),
                    event.skill(), event.compaction(), event.error());
            seq = filled.seq();
            events.add(filled);
            return filled;
        }
    }

    /** 恢复：装载已有事件（不落盘）。 */
    void restoreFrom(List<Event> loaded) {
        synchronized (lock) {
            events.clear();
            seq = 0L;
            for (Event event : loaded) {
                seq = event.seq();
                events.add(event);
            }
        }
    }

    /**
     * 把错误转成可入日志的详情：超长按 UTF-8 边界截断。
     *
     * <p>错误串可能很长（服务端返回片段、嵌套的 wrap 链），日志不该被它撑爆；
     * 完整内容仍在返回值里，宿主自行决定怎么呈现。</p>
     */
    public static String errorDetail(Throwable error) {
        if (error == null) {
            return "";
        }
        String s = error.getMessage() == null ? error.toString() : error.getMessage();
        if (s.length() <= MAX_ERROR_DETAIL) {
            return s;
        }
        int cut = MAX_ERROR_DETAIL;
        // 回退到字符边界：切在代理对中间会切出半个字符
        while (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut) + "…（已截断）";
    }
}
