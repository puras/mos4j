package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 日志文件首行的会话元信息。
 *
 * <p>title / cwd 供宿主做会话索引（列表、按项目分组）用；
 * parentId / seedSeq 为 fork（从某条消息分叉）预留。不适用时省略。</p>
 *
 * @param id        会话 id
 * @param createdAt 创建时间（RFC3339 UTC 文本）
 * @param title     标题
 * @param cwd       工作目录
 * @param parentId  父会话 id（fork 用）
 * @param seedSeq   分叉种子事件序号（fork 用）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Header(
        @JsonProperty("id") String id,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("title") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String title,
        @JsonProperty("cwd") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String cwd,
        @JsonProperty("parentId") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String parentId,
        @JsonProperty("seedSeq") @JsonInclude(JsonInclude.Include.NON_DEFAULT) long seedSeq) {

    /** 只有 id 与创建时间的头。 */
    public static Header of(String id) {
        return new Header(id, Session.now(), null, null, null, 0L);
    }
}
