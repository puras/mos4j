package net.hegq.mos4j.session;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;

/**
 * 事件的<b>手写</b>序列化器（逐字照抄 Go 侧 {@code session.Event} 的 tag 规则）。
 *
 * <p><b>规则表</b>（Go {@code omitempty} 语义）：</p>
 * <pre>
 * seq/kind/time          无条件输出
 * turn                   0 省略
 * text/reasoning/reason/error   空串省略
 * toolCalls/toolCall/toolResult/usage/approval/compaction   nil 省略
 * toolResult.isError     false 省略
 * compaction.tokensBefore 0 省略
 * </pre>
 *
 * <p><b>为什么必须手写</b>：Jackson 的 {@code NON_EMPTY} 对数字<b>不</b>省略 0，
 * 对 record <b>不</b>生效（record 不是 Collection/Map/String）。结果是
 * 「turn=0 的事件多一个 turn 键」「空 details 变成一串零值」——
 * 与 Go 侧日志不一致，审计脚本按键消费时会把「键存在」当成「有值」。</p>
 */
public final class EventJson {

    private EventJson() {
    }

    /** 事件序列化器。 */
    public static final class Serializer extends ValueSerializer<Event> {

        @Override
        public void serialize(Event value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            gen.writeStartObject();
            gen.writeNumberProperty("seq", value.seq());
            gen.writeStringProperty("kind", value.kind() == null ? null : value.kind().wire());
            if (value.turn() != 0) {
                gen.writeNumberProperty("turn", value.turn());
            }
            gen.writeStringProperty("time", value.time() == null ? "" : value.time());
            writeString(gen, "text", value.text());
            writeString(gen, "reasoning", value.reasoning());
            if (value.toolCalls() != null && !value.toolCalls().isEmpty()) {
                gen.writeName("toolCalls");
                gen.writeStartArray();
                for (ToolCall call : value.toolCalls()) {
                    ctxt.writeValue(gen, call);
                }
                gen.writeEndArray();
            }
            if (value.toolCall() != null) {
                gen.writeName("toolCall");
                ctxt.writeValue(gen, value.toolCall());
            }
            if (value.toolResult() != null) {
                gen.writeName("toolResult");
                writeToolResult(gen, value.toolResult());
            }
            if (value.usage() != null) {
                gen.writeName("usage");
                ctxt.writeValue(gen, (Usage) value.usage());
            }
            writeString(gen, "reason", value.reason());
            if (value.approval() != null) {
                gen.writeName("approval");
                writeApproval(gen, value.approval());
            }
            if (value.skill() != null) {
                gen.writeName("skill");
                writeSkill(gen, value.skill());
            }
            if (value.compaction() != null) {
                gen.writeName("compaction");
                writeCompaction(gen, value.compaction());
            }
            writeString(gen, "error", value.error());
            gen.writeEndObject();
        }

        private static void writeString(JsonGenerator gen, String field, String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            gen.writeStringProperty(field, value);
        }

        private static void writeToolResult(JsonGenerator gen, ToolResult result) {
            gen.writeStartObject();
            writeString(gen, "callId", result.callId());
            gen.writeStringProperty("content", result.content() == null ? "" : result.content());
            if (result.isError()) {
                gen.writeBooleanProperty("isError", true);
            }
            gen.writeEndObject();
        }

        private static void writeApproval(JsonGenerator gen, Approval approval) {
            gen.writeStartObject();
            gen.writeStringProperty("id", approval.id() == null ? "" : approval.id());
            gen.writeStringProperty("toolName", approval.toolName() == null ? "" : approval.toolName());
            writeString(gen, "callId", approval.callId());
            writeString(gen, "reason", approval.reason());
            writeString(gen, "outcome", approval.outcome());
            gen.writeEndObject();
        }

        private static void writeSkill(JsonGenerator gen, SkillEvent skill) {
            gen.writeStartObject();
            gen.writeStringProperty("name", skill.name() == null ? "" : skill.name());
            writeString(gen, "description", skill.description());
            writeString(gen, "dir", skill.dir());
            if (skill.missingAllowed() != null && !skill.missingAllowed().isEmpty()) {
                gen.writeName("missingAllowed");
                gen.writeStartArray();
                for (String tool : skill.missingAllowed()) {
                    gen.writeString(tool);
                }
                gen.writeEndArray();
            }
            gen.writeEndObject();
        }

        private static void writeCompaction(JsonGenerator gen, Compaction compaction) {
            gen.writeStartObject();
            gen.writeStringProperty("summary", compaction.summary() == null ? "" : compaction.summary());
            gen.writeNumberProperty("firstKeptSeq", compaction.firstKeptSeq());
            if (compaction.tokensBefore() != 0) {
                gen.writeNumberProperty("tokensBefore", compaction.tokensBefore());
            }
            gen.writeEndObject();
        }
    }
}
