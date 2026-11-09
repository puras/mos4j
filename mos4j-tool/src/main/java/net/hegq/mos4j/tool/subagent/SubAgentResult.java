package net.hegq.mos4j.tool.subagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 子代理的固定结构回执。
 *
 * <p><b>结构固定</b>（不随子代理变）：父模型拿到的永远是同一形状的几样东西——
 * 状态、一句自述、若干条线索。{@code summary} 是<b>子代理自述</b>，不是事实。
 *
 * <p><b>失败也是回执</b>：子代理跑挂了、超时了，回执照样是这段结构
 * （{@code status=error/timeout} + 原因说明），父轮次不因此失败。
 *
 * @param status   执行状态
 * @param summary  子代理对结果的一句话总结（自述）
 * @param findings 结构化线索（父模型要自己复核）
 * @param error    失败原因（{@code status != completed} 时非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentResult(
        @JsonProperty("status") SubAgentStatus status,
        @JsonProperty("summary") String summary,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("findings") List<SubAgentFinding> findings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("error") String error) {

    /** 构造器（列表防御性复制 + null 归一）。 */
    public SubAgentResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        summary = summary == null ? "" : summary;
        error = error == null ? "" : error;
    }

    /** 跑完了。 */
    public static SubAgentResult completed(String summary, List<SubAgentFinding> findings) {
        return new SubAgentResult(SubAgentStatus.COMPLETED, summary, findings, "");
    }

    /** 跑挂了。 */
    public static SubAgentResult error(String error) {
        return new SubAgentResult(SubAgentStatus.ERROR, "", List.of(), error);
    }

    /** 超时被掐。 */
    public static SubAgentResult timeout(String error) {
        return new SubAgentResult(SubAgentStatus.TIMEOUT, "", List.of(), error);
    }

    /**
     * 校验回执形状（fail-closed）。
     *
     * <p>状态不在三档里 → 不合法；状态不是 {@code completed} 却带空错误说明 → 不合法
     * （父模型得知道为什么失败）。
     *
     * @return 问题说明；合法时返回 {@code null}
     */
    public String validate() {
        if (status == null) {
            return "status 不是合法取值（completed / error / timeout）";
        }
        if (status != SubAgentStatus.COMPLETED && error.isBlank()) {
            return "status \"" + status.wire() + "\" 必须带 error 说明";
        }
        for (int i = 0; i < findings.size(); i++) {
            if (findings.get(i).summary().isBlank()) {
                return "findings[" + i + "] 缺 summary";
            }
        }
        return null;
    }
}
