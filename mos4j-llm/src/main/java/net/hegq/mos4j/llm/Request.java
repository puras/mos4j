package net.hegq.mos4j.llm;

import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Tool;

import java.util.List;
import java.util.Map;

/**
 * 一次模型请求（协议无关）。
 *
 * <p>可选参数用<b>包装类型</b>（{@link Double}/{@link Integer}/{@link String}）表示
 * 「未设置」与「设置为 0/空串」的区别——两者在协议层语义不同：
 * {@code temperature=0} 是明确要求确定性输出，{@code temperature=null} 是用服务端默认。</p>
 *
 * @param messages        对话历史（必填）
 * @param tools           可用工具（可空）
 * @param temperature     采样温度（可空 = 服务端默认）
 * @param maxTokens       输出上限（可空；宿主成本旋钮）
 * @param responseFormat  结构化输出格式（可空）
 * @param modalities      期望输出模态（如 text、audio；空 = 协议默认）
 * @param reasoningEffort 推理投入（low|medium|high；仅推理能力协议生效）
 */
public record Request(
        List<Message> messages,
        List<Tool> tools,
        Double temperature,
        Integer maxTokens,
        ResponseFormat responseFormat,
        List<String> modalities,
        String reasoningEffort) {

    /** 只带消息的请求。 */
    public static Request of(List<Message> messages) {
        return new Request(messages, null, null, null, null, null, null);
    }

    /** 只带一条用户消息的请求。 */
    public static Request ofUser(String content) {
        return of(List.of(Message.user(content)));
    }

    /** 结构化输出格式。 */
    public record ResponseFormat(String type, String name, Map<String, Object> schema) {

        /** json_object 模式。 */
        public static ResponseFormat jsonObject() {
            return new ResponseFormat("json_object", null, null);
        }

        /** json_schema 模式（带 schema）。 */
        public static ResponseFormat jsonSchema(String name, Map<String, Object> schema) {
            return new ResponseFormat("json_schema", name, schema);
        }
    }
}
