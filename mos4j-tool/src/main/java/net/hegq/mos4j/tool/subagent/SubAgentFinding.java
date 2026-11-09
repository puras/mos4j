package net.hegq.mos4j.tool.subagent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 子代理给出的一条线索（父模型<b>要自己复核</b>）。
 *
 * <p>子代理的自述<b>不是事实</b>：它只是线索。{@code ref} 指向可核对的东西
 * （文件路径 / 命令 / 行号），父模型拿它自己去验。
 *
 * @param ref     可核对的位置（文件路径、命令、位置说明；可空）
 * @param summary 这条线索说明什么（子代理自述）
 */
public record SubAgentFinding(
        @JsonProperty("ref") String ref,
        @JsonProperty("summary") String summary) {

    /** 线索说明（空串归一）。 */
    public String summary() {
        return summary == null ? "" : summary;
    }

    /** 可核对位置（空串归一）。 */
    public String ref() {
        return ref == null ? "" : ref;
    }
}
