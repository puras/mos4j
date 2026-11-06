package net.hegq.mos4j.driver;

import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.llm.model.Usage;

import java.util.List;

/**
 * 决策器的一次输出（模型这一步要干什么）。
 *
 * @param content         模型产出的文本
 * @param reasoningContent 思维链内容（推理模型扩展；thinking 模式多轮须原样回传）
 * @param toolCalls       模型请求调用的工具（完整保留，循环层负责执行与回填）
 * @param finishReason    停止原因；length/max_tokens 表示输出被截断
 * @param usage           本次决策的 token 用量（随 assistant 事件落日志）
 */
public record Action(
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String finishReason,
        Usage usage) {

    /** 无工具调用的作答。 */
    public static Action answer(String content, String finishReason, Usage usage) {
        return new Action(content, null, List.of(), finishReason, usage);
    }

    /** 是否请求了工具调用。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 是否因长度被截断。
     *
     * <p>截断的工具调用参数可能是半截 JSON——不可信，必须 fail-closed 拒执行。</p>
     */
    public boolean truncated() {
        return "length".equals(finishReason) || "max_tokens".equals(finishReason);
    }
}
