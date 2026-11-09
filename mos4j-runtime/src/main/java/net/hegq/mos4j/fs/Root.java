package net.hegq.mos4j.fs;

import java.time.Instant;
import java.nio.file.Path;

/**
 * 一个被允许的根（jail 的一个「洞」）。
 *
 * @param id       挂载名（锚点根用空串；其他根用可读的短名，如 {@code data}）
 * @param path     根的真实路径（已 {@code toRealPath()}，绝对、已解 symlink）
 * @param addedAt  加入时间（变更留痕用）
 * @param readable 是否可读（只写根 = 能写不能读）
 * @param writable 是否可写（只读根 = 能读不能写）
 */
public record Root(String id, Path path, Instant addedAt, boolean readable, boolean writable) {

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
        if (!readable && !writable) {
            throw new IllegalArgumentException("fs: 根至少要可读或可写一个: " + id);
        }
    }

    /** 读写都放行的根（缺省形态）。 */
    public Root(String id, Path path, Instant addedAt) {
        this(id, path, addedAt, true, true);
    }

    /** 锚点根（相对路径的默认基准）。 */
    public boolean isAnchor() {
        return id.isEmpty();
    }

    @Override
    public String toString() {
        String base = id.isEmpty() ? path.toString() : id + "=" + path;
        if (readable && writable) {
            return base;
        }
        return base + (readable ? "(只读)" : "(只写)");
    }
}
