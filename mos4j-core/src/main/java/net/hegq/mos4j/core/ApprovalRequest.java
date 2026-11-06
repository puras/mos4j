package net.hegq.mos4j.core;

/**
 * 一次人工审批请求。
 *
 * <p>{@code id} 把 {@code approval/asked} 与 {@code approval/decided} 两条审计事件配成一对
 * （机制 M10 的审计不变式）；批准**只对本次调用生效**——不缓存、不复用
 * （下次同参数的调用要重新问，否则「批准一次」就变成了「永久放行」）。
 *
 * @param id       审批 id（asked / decided 配对用）
 * @param toolName 工具名
 * @param callId   对应的工具调用 id（可空）
 * @param reason   问人的理由（也是拒绝文案里回显给用户的内容）
 */
public record ApprovalRequest(String id, String toolName, String callId, String reason) {

    public ApprovalRequest {
        id = id == null ? "" : id;
        toolName = toolName == null ? "" : toolName;
        callId = callId == null ? "" : callId;
        reason = reason == null ? "" : reason;
    }
}
