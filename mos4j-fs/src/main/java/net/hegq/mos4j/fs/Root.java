package net.hegq.mos4j.fs;

import java.time.Instant;
import java.nio.file.Path;

/**
 * 一个被允许的根（jail 的一个「洞」）。
 *
 * @param id      挂载名（锚点根用空串；其他根用可读的短名，如 {@code data}）
 * @param path    根的真实路径（已 {@code toRealPath()}，绝对、已解 symlink）
 * @param addedAt 加入时间（变更留痕用）
 */
public record Root(String id, Path path, Instant addedAt) {

    public Root {
        if (id == null) {
            throw new IllegalArgumentException("fs: 根的挂载名不能为 null（锚点根用空串）");
        }
        if (path == null) {
            throw new IllegalArgumentException("fs: 根的路径不能为 null");
        }
        if (addedAt == null) {
            throw new IllegalArgumentException("fs: 根的加入时间不能为 null");
        }
    }

    /** 锚点根（相对路径的默认基准）。 */
    public boolean isAnchor() {
        return id.isEmpty();
    }

    @Override
    public String toString() {
        return id.isEmpty() ? path.toString() : id + "=" + path;
    }
}
