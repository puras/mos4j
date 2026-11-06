package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 压缩事件载荷：旧历史被摘要遮蔽，{@code firstKeptSeq} 之后的事件照常投影。
 *
 * @param summary      模型生成的结构化摘要文本（进投影，user 角色）
 * @param firstKeptSeq 保留段首条事件的序号：投影丢弃此前的可投影消息
 * @param tokensBefore 压缩前的上下文 token 估算（审计用）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Compaction(
        @JsonProperty("summary") String summary,
        @JsonProperty("firstKeptSeq") long firstKeptSeq,
        @JsonProperty("tokensBefore") @JsonInclude(JsonInclude.Include.NON_DEFAULT) int tokensBefore) {
}
