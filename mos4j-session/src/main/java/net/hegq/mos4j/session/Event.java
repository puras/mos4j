package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonSerialize;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;

import java.util.List;

/**
 * 日志中的一条事件。
 *
 * <p><b>序列化规则必须手写</b>（见 {@link EventJson}）：Go 的 {@code omitempty} 对 int 0
 * 也省略、对结构体无效；Jackson 的 {@code NON_EMPTY} 既不省略 0 也不作用于 record——
 * 直接用注解会得到「turn=0 也出现」「空 details 变成一串零值」的日志，
 * 与 Go 侧日志对不上，审计脚本按键消费时会读到假数据。</p>
 *
 * <p><b>时间用 String</b>（RFC3339 UTC）：引入 {@code jackson-datatype-jsr310} 只为
 * 一个时间字段不值——依赖纪律（I7）要求运行时依赖只有 Jackson 核心三件套 + RE2J，
 * 而 JSR-310 模块是第四个 Jackson 构件。时间格式由 {@code EventJson} 统一写出，
 * 读回来是同一个字符串，语义无损。</p>
 *
 * @param seq        序号（由 {@link Session#append} 统一分配，从 1 开始）
 * @param kind       事件类型
 * @param turn       轮次（0 = 不带轮次；序列化时省略）
 * @param time       时间戳（RFC3339 UTC 文本）
 * @param text       文本（user / assistant）
 * @param reasoning  推理内容（assistant）
 * @param toolCalls  工具调用列表（assistant）
 * @param toolCall   单次工具调用（tool/call）
 * @param toolResult 工具结果（tool/result）
 * @param usage      模型调用用量（assistant / compaction）
 * @param reason     结束原因（turn/end）
 * @param approval   审批载荷（approval/*）
 * @param skill      技能载荷（skill/*）
 * @param compaction 压缩载荷（compaction）
 * @param error      失败详情（turn/end；只进日志，不进投影）
 */
@JsonSerialize(using = EventJson.Serializer.class)
public record Event(
        @JsonProperty("seq") long seq,
        @JsonProperty("kind") EventKind kind,
        @JsonProperty("turn") int turn,
        @JsonProperty("time") String time,
        @JsonProperty("text") String text,
        @JsonProperty("reasoning") String reasoning,
        @JsonProperty("toolCalls") List<ToolCall> toolCalls,
        @JsonProperty("toolCall") ToolCall toolCall,
        @JsonProperty("toolResult") ToolResult toolResult,
        @JsonProperty("usage") Usage usage,
        @JsonProperty("reason") String reason,
        @JsonProperty("approval") Approval approval,
        @JsonProperty("skill") SkillEvent skill,
        @JsonProperty("compaction") Compaction compaction,
        @JsonProperty("error") String error) {

    /**
     * 防御性拷贝：工具调用列表一旦入日志就不可变。
     *
     * <p>不这么做会怎样：调用方拿着自己传进来的 {@code List} 追加后改一条，
     * 日志里的 assistant 事件就跟着变了——「日志即事实」当场失效，
     * 而且这种改动在事件序列化之前完全不可见（最难查的一类 bug）。</p>
     */
    public Event {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    /** 构造器：只填事件类型，其余留空。 */
    public static Builder builder(EventKind kind) {
        return new Builder(kind);
    }

    /** 事件构造器（链式）。 */
    public static final class Builder {

        private final EventKind kind;
        private int turn;
        private String time;
        private String text;
        private String reasoning;
        private List<ToolCall> toolCalls;
        private ToolCall toolCall;
        private ToolResult toolResult;
        private Usage usage;
        private String reason;
        private Approval approval;
        private SkillEvent skill;
        private Compaction compaction;
        private String error;

        private Builder(EventKind kind) {
            this.kind = kind;
        }

        public Builder turn(int turn) {
            this.turn = turn;
            return this;
        }

        public Builder time(String time) {
            this.time = time;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCall(ToolCall toolCall) {
            this.toolCall = toolCall;
            return this;
        }

        public Builder toolResult(ToolResult toolResult) {
            this.toolResult = toolResult;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder approval(Approval approval) {
            this.approval = approval;
            return this;
        }

        public Builder skill(SkillEvent skill) {
            this.skill = skill;
            return this;
        }

        public Builder compaction(Compaction compaction) {
            this.compaction = compaction;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Event build() {
            return new Event(0L, kind, turn, time, text, reasoning, toolCalls, toolCall,
                    toolResult, usage, reason, approval, skill, compaction, error);
        }
    }
}
