package net.hegq.mos4j.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 对话中的一条消息（协议无关的统一模型）。
 *
 * <p>JSON 字段名照抄 Go：{@code role} 无条件；{@code content}/{@code content_parts}/
 * {@code tool_calls}/{@code tool_call_id}/{@code name}/{@code reasoning_content} 为空则省略。</p>
 *
 * <p><b>为什么 {@code toolCalls} 保留在统一模型里而不是只在 assistant 上</b>：
 * 协议差异（OpenAI 在 assistant 消息里带 tool_calls；Anthropic 在 tool_result 块里带
 * id）由适配器负责翻译，统一模型保持一种形状，映射逻辑只有一处。</p>
 *
 * @param role             角色
 * @param content          纯文本内容（多模态时为空）
 * @param contentParts     多模态内容块（优先于 content）
 * @param toolCalls        工具调用（assistant）
 * @param toolCallId       关联的工具调用 id（tool 消息）
 * @param name             工具名（tool 消息）
 * @param reasoningContent 推理内容（思维链；thinking 模式多轮须原样回传）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        @JsonProperty("role") Role role,
        @JsonProperty("content") String content,
        @JsonProperty("content_parts") List<ContentPart> contentParts,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("name") String name,
        @JsonProperty("reasoning_content") String reasoningContent) {

    /** 用户消息。 */
    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null, null, null);
    }

    /** 系统消息。 */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null, null, null);
    }

    /** 助手消息（纯文本）。 */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null, null, null);
    }

    /** 助手消息（带工具调用）。 */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, null, toolCalls, null, null, null);
    }

    /** 工具结果回执（配对 {@code toolCallId}）。 */
    public static Message toolResult(String toolCallId, String name, String content) {
        return new Message(Role.TOOL, content, null, null, toolCallId, name, null);
    }
}
