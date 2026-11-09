package net.hegq.mos4j.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写前观察门（机制 M7）：写一个文件之前，必须先「看过」它，而且看的还是<b>当前这一份</b>。
 *
 * <p><b>为什么要有这道门</b>：模型读文件 A → 上下文被压缩/别的轮次改写 → 模型按旧认知写回，
 * 覆盖掉别人（或自己）后来的修改。没有这道门，「读—改—写」就是一个无声的数据丢失通道。
 *
 * <p><b>三态</b>（L3-15 … L3-17）：
 * <ul>
 *   <li><b>没读过</b>（文件存在）→ 拒绝。模型连内容都没看到就写，等于瞎改。</li>
 *   <li><b>读过且没变</b> → 放行。</li>
 *   <li><b>读过但已改/已删</b> → 拒绝，要求重新 read。</li>
 * </ul>
 *
 * <p><b>新建文件不要求先 read</b>：文件压根不存在时没有「旧内容」可覆盖，
 * 硬要求先 read 会让模型必须先造一个空文件再写，纯属折腾。
 *
 * <p><b>fail-closed</b>：检查过程本身出错（读不到属性等）一律拒绝，不放行。
 */
public final class ObserveGate {

    private final Map<Path, Observation> observations = new ConcurrentHashMap<>();

    /**
     * 记录一次「我读过这个文件」（读工具成功后调用）。
     *
     * @param path 文件路径
     * @return 记录到的观察
     * @throws IOException 读不到文件属性
     */
    public Observation observe(Path path) throws IOException {
        Path key = key(path);
        boolean exists = Files.exists(key, LinkOption.NOFOLLOW_LINKS);
        Observation observation = exists
                ? new Observation(true, VersionToken.of(key), Instant.now())
                : new Observation(false, null, Instant.now());
        observations.put(key, observation);
        return observation;
    }

    /**
     * 写前检查。
     *
     * @param path 文件路径
     * @return 裁决（放行 / 拒绝 + 原因码）
     */
    public Verdict verify(Path path) {
        Path key = key(path);
        Observation observation = observations.get(key);
        boolean exists = Files.exists(key, LinkOption.NOFOLLOW_LINKS);

        if (!exists) {
            if (observation != null && observation.existed()) {
                return Verdict.deny(Verdict.Reason.DELETED, observation.token(), null, "");
            }
            // 新建文件：不要求先 read
            return Verdict.allow();
        }
        if (observation == null || !observation.existed()) {
            return Verdict.deny(Verdict.Reason.NOT_OBSERVED, null, null, "");
        }
        VersionToken current;
        try {
            current = VersionToken.of(key);
        } catch (IOException e) {
            return Verdict.deny(Verdict.Reason.CHECK_FAILED, observation.token(), null, String.valueOf(e.getMessage()));
        }
        if (!current.equals(observation.token())) {
            return Verdict.deny(Verdict.Reason.MODIFIED, observation.token(), current, "");
        }
        return Verdict.allow();
    }

    /**
     * 是否观察过（测试与诊断用）。
     *
     * @param path 文件路径
     * @return true 表示有观察记录
     */
    public boolean observed(Path path) {
        return observations.containsKey(key(path));
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * 一次观察。
     *
     * @param existed   观察时文件是否存在
     * @param token     观察时的版本令牌（不存在时为 null）
     * @param observedAt 观察时间
     */
    public record Observation(boolean existed, VersionToken token, Instant observedAt) {
    }

    /**
     * 裁决。
     *
     * @param allowed  是否放行
     * @param reason   原因码
     * @param expected 读取时的版本令牌（可能是 null）
     * @param actual   当前的版本令牌（可能是 null）
     * @param detail   补充说明（检查失败时有用）
     */
    public record Verdict(boolean allowed, Reason reason, VersionToken expected, VersionToken actual, String detail) {

        /**
         * 原因码（文案由调用方按 {@code API.md} §4 的表拼装，闸门只给事实）。
         */
        public enum Reason {
            /** 放行。 */
            OK,
            /** 文件存在，但没被读过。 */
            NOT_OBSERVED,
            /** 读过，之后被删了。 */
            DELETED,
            /** 读过，之后被改了。 */
            MODIFIED,
            /** 检查本身失败（fail-closed）。 */
            CHECK_FAILED
        }

        /**
         * 放行。
         *
         * @return 放行裁决
         */
        public static Verdict allow() {
            return new Verdict(true, Reason.OK, null, null, "");
        }

        /**
         * 拒绝。
         *
         * @param reason   原因码
         * @param expected 读取时的令牌
         * @param actual   当前的令牌
         * @param detail   补充说明
         * @return 拒绝裁决
         */
        public static Verdict deny(Reason reason, VersionToken expected, VersionToken actual, String detail) {
            return new Verdict(false, reason, expected, actual, detail == null ? "" : detail);
        }
    }
}
