package net.hegq.mos4j.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件边界的<b>诚实快照</b>：装了什么 + 没装什么 + 哪里是降级的。
 *
 * <p>事实由 fs 模块自己掌握（它知道根在哪、平台支不支持 unix 属性），
 * 驱动层只负责把它写进 {@code Describe}——
 * 边界写在作者心里等于没有边界。
 *
 * @param roots              允许的根（挂载名 + 真实路径）
 * @param versionTokenStrong 版本令牌是否为 Unix 强口径（含 dev/ino）
 * @param observeGateEnabled 是否装配写前观察门
 */
public record FileSystemBoundary(List<String> roots, boolean versionTokenStrong, boolean observeGateEnabled) {

    /**
     * 从实际的文件 jail 生成快照。
     *
     * @param fs 文件 jail
     * @return 快照
     */
    public static FileSystemBoundary of(SandboxedFs fs) {
        List<String> roots = new ArrayList<>();
        for (Root root : fs.roots().roots()) {
            roots.add(root.id().isEmpty() ? root.path().toString() : root.id() + "=" + root.path());
        }
        return new FileSystemBoundary(List.copyOf(roots), supportsUnixAttributes(fs.roots().anchor()),
                true);
    }

    /**
     * 已装配的能力（进 Describe 的 features）。
     *
     * @return 说明列表
     */
    public List<String> features() {
        List<String> features = new ArrayList<>();
        features.add("文件 jail=已装配；允许的根=" + roots);
        features.add("路径校验=Lexical + Realpath 双校验（读也走 Realpath；符号链接逐段解析，深度上限 32）");
        if (observeGateEnabled) {
            features.add("写前观察门=已装配（读过才允许覆盖/编辑；新建文件免读；已改/已删一律拒绝）");
        }
        return features;
    }

    /**
     * 降级与边界（进 Describe 的 caveats）。
     *
     * @return 说明列表
     */
    public List<String> caveats() {
        List<String> caveats = new ArrayList<>();
        if (versionTokenStrong) {
            caveats.add("版本令牌=Unix 强口径 dev:ino:size:mtimeNs（不含 ctime：只改元数据、内容不变的改动检测不到）");
        } else {
            caveats.add("版本令牌=退化口径 size:mtimeNs（当前文件系统拿不到 dev/ino："
                    + "同大小同 mtime 的替换可能漏检）");
        }
        return caveats;
    }

    private static boolean supportsUnixAttributes(Path path) {
        try {
            Files.getAttribute(path, "unix:dev");
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException | ClassCastException e) {
            return false;
        }
    }
}
