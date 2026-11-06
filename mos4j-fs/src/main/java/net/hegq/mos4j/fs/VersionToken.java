package net.hegq.mos4j.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * 版本令牌：文件在某个时刻的「身份 + 状态」快照。
 *
 * <p><b>Unix 口径</b>：{@code dev:ino:size:mtimeNs}。
 * <ul>
 *   <li>{@code dev:ino} —— 身份：inode 被删了又建时 ino 可能复用，加上 dev（设备号）后复用概率极低；</li>
 *   <li>{@code size:mtimeNs} —— 状态：内容变了通常先变 size 或 mtime。</li>
 * </ul>
 *
 * <p><b>非 Unix 退化</b>：{@code size:mtimeNs}（{@code unix:} 视图不可用时拿不到 dev/ino）。
 * 退化必须<b>说出口</b>（{@link #unix()} 返回 false），不能假装和 Unix 一样强——
 * 「删了重建同样大小、mtime 也一样」的文件在退化口径下会漏检。这是能力边界，不是 bug。
 *
 * <p><b>关于 ctime</b>：JDK 的 {@code unix:} 视图其实<b>能</b>读到 {@code ctime}
 * （{@code Files.getAttribute(path, "unix:ctime")}），但冻结的令牌口径<b>不含 ctime</b>：
 * 令牌要跨平台形状一致，且 ctime 在不同文件系统上的语义并不统一（有的会随读取更新）。
 * 代价是「只改了元数据（chmod）、内容没变」这类改动检测不到——已知缺口，登记在案。
 */
public record VersionToken(long dev, long ino, long size, long mtimeNs, boolean unix) {

    private static final long UNKNOWN = -1L;

    /**
     * 捕获一个文件的版本令牌。
     *
     * @param path 文件路径（跟随符号链接）
     * @return 版本令牌
     * @throws IOException 读不到文件属性
     */
    public static VersionToken of(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attributes.size();
        long mtimeNs = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        try {
            long dev = (Long) Files.getAttribute(path, "unix:dev");
            long ino = (Long) Files.getAttribute(path, "unix:ino");
            return new VersionToken(dev, ino, size, mtimeNs, true);
        } catch (UnsupportedOperationException | IllegalArgumentException | ClassCastException e) {
            // 非 Unix 平台（或不支持 unix: 视图）：退化为 size:mtimeNs
            return new VersionToken(UNKNOWN, UNKNOWN, size, mtimeNs, false);
        }
    }

    /**
     * 令牌字符串（Unix：{@code dev:ino:size:mtimeNs}；非 Unix：{@code size:mtimeNs}）。
     *
     * @return 令牌字符串
     */
    public String value() {
        if (unix) {
            return dev + ":" + ino + ":" + size + ":" + mtimeNs;
        }
        return size + ":" + mtimeNs;
    }

    /**
     * 是否是 Unix 强口径（含 dev/ino）。
     *
     * @return true 表示含 dev/ino
     */
    public boolean strong() {
        return unix;
    }

    @Override
    public String toString() {
        return value();
    }
}
