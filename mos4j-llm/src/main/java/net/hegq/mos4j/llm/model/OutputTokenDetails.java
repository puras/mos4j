package net.hegq.mos4j.llm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 输出侧 token 明细。
 *
 * @param reasoning 推理（思维链）token 数，不直接出现在模型输出中
 * @param audio     音频输出 token 数
 */
public record OutputTokenDetails(
        @JsonProperty("reasoning") int reasoning,
        @JsonProperty("audio") int audio) {

    /** 是否全零。 */
    public boolean isEmpty() {
        return reasoning == 0 && audio == 0;
    }
}
