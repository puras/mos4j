package net.hegq.mos4j.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 审批事件载荷（{@code approval/asked} 与 {@code approval/decided} 共用）。
 *
 * @param id       审批请求标识（配对 asked 与 decided）
 * @param toolName 被审批的工具名
 * @param callId   被审批的那次调用
 * @param reason   问人的理由（asked 才有）
 * @param outcome  审批结果（decided 才有）：approved / rejected / cancelled / unavailable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Approval(
        @JsonProperty("id") String id,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("callId") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String callId,
        @JsonProperty("reason") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String reason,
        @JsonProperty("outcome") @JsonInclude(JsonInclude.Include.NON_DEFAULT) String outcome) {
}
