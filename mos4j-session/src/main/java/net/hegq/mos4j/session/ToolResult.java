package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一次工具执行的回填（事件日志审计载荷）。
 *
 * <p><b>为什么 isError 是事件日志特有语义</b>：协议消息里没有「工具失败了」这个概念
 * （工具结果就是一段文本）；但审计需要区分「成功」与「被守卫拦了 / 工具报错了」，
 * 否则事后看日志只能看到一段错误文本。</p>
 *
 * @param callId  对应的工具调用 id
 * @param content 结果正文
 * @param isError 是否是失败结果（false 时序列化省略）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        @JsonProperty("callId") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String callId,
        @JsonProperty("content") String content,
        @JsonProperty("isError") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean isError) {
}
