package net.hegq.mos4j.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 输入侧 token 明细。
 *
 * @param cacheRead  缓存命中数
 * @param cacheWrite 缓存写入数（缓存未命中时创建）
 * @param audio      音频输入 token 数
 */
public record InputTokenDetails(
        @JsonProperty("cache_read") int cacheRead,
        @JsonProperty("cache_write") int cacheWrite,
        @JsonProperty("audio") int audio) {

    /** 是否全零。 */
    public boolean isEmpty() {
        return cacheRead == 0 && cacheWrite == 0 && audio == 0;
    }
}
