package net.hegq.mos4j.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件 jail：把「模型给的路径」解析成「根内的真实路径」。
 *
 * <p><b>两层校验，缺一不可</b>（机制 M9）：
 * <ol>
 *   <li><b>Lexical</b>：路径必须落在某个根内（{@code ..} 不能爬出根）；</li>
 *   <li><b>Realpath</b>：逐段解析符号链接，解析后的真实位置仍必须落在根内。</li>
 * </ol>
 *
 * <p><b>读也必须走 Realpath</b>：根内一个指向 {@code /etc/passwd} 的符号链接，
 * 只做 lexical 检查就能把边界外的内容读出来。Go 侧踩过这个坑
 * （{@code filesystem/sandboxed.go:Resolve}）。
 *
 * <p><b>逐段解析，而不是「先 normalize 再 toRealPath」</b>：
 * {@code normalize()} 是纯字面操作，遇到符号链接就撒谎——
 * {@code root/link/../x} 在字面上是 {@code root/x}，在真实文件系统里却是
 * 「link 指向的目录的父目录/x」。所以这里的算法是<b>逐段</b>的：
 * 走一段、解一段、每段都确认还在根内。
 *
 * <p><b>新建路径</b>（叶子不存在）也走同一条路：中间组件只要是符号链接就照样解析，
 * 解析后越界照样拒绝（机制 M9 的「最深存在祖先」要求由此满足）。
 *
 * <p><b>符号链接层数上限 32</b>：链接成环时提前拒绝，而不是死循环。
 *
 * <p><b>为什么需要这层（不这么做会出什么事）</b>：模型给的路径是<b>不可信输入</b>，
 * 它可能来自被注入的网页、被污染的仓库说明，甚至模型自己的幻觉。不做 jail 解析，
 * 一次 {@code read("/etc/passwd")} 就能把边界外的内容读进上下文并回传给模型；
 * 只做词法校验，则根内一个指向边界外的符号链接就能绕过全部检查。
 * 所以这里的每个方法要么拒绝，要么给出<b>确实在根内</b>的真实路径——没有「先放行再说」。
 */
public final class SandboxedFs {

    /** 符号链接解析层数上限（超过即拒绝，防环）。 */
    public static final int MAX_SYMLINK_DEPTH = 32;

    private final RootSet roots;

    /**
     * @param roots 活根集合
     */
    public SandboxedFs(RootSet roots) {
        if (roots == null) {
            throw new IllegalArgumentException("fs: 根集合不能为 null");
        }
        this.roots = roots;
    }

    /**
     * 解析一个模型给的相对路径。
     *
     * @param userPath 相对路径（不接受绝对路径）
     * @return 根内的真实路径（不保证存在）
     * @throws PathDeniedError 越界 / 符号链接越界 / 链接层数超限 / 路径非法
     */
    public Path resolve(String userPath) {
        return resolve(userPath, Access.BOTH);
    }

    /**
     * 解析一个模型给的相对路径（只读）。
     *
     * @param userPath 相对路径
     * @return 根内的真实路径（不保证存在）
     */
    public Path resolveForRead(String userPath) {
        return resolve(userPath, Access.READ);
    }

    /**
     * 解析一个模型给的相对路径（只写）。
     *
     * @param userPath 相对路径
     * @return 根内的真实路径（不保证存在）
     */
    public Path resolveForWritePath(String userPath) {
        return resolve(userPath, Access.WRITE);
    }

    /**
     * 本次操作允许碰的根类型。
     */
    private enum Access {
        /** 读写都算（通用路径解析）。 */
        BOTH,
        /** 只有可读根。 */
        READ,
        /** 只有可写根。 */
        WRITE;

        boolean allows(Root root) {
            return switch (this) {
                case BOTH -> true;
                case READ -> root.readable();
                case WRITE -> root.writable();
            };
        }
    }

    private Path resolve(String userPath, Access access) {
        List<Root> allRoots = roots.roots();
        List<Root> usableRoots = new ArrayList<>();
        for (Root root : allRoots) {
            if (access.allows(root)) {
                usableRoots.add(root);
            }
        }
        List<String> allowed = allowedRootPaths(usableRoots);
        if (userPath == null || userPath.isBlank()) {
            throw new PathDeniedError("", allowed, "路径为空");
        }
        Path input;
        try {
            input = Path.of(userPath);
        } catch (InvalidPathException e) {
            throw new PathDeniedError(userPath, allowed, "路径非法");
        }
        if (input.isAbsolute()) {
            throw new PathDeniedError(userPath, allowed, "只接受相对路径");
        }
        if (usableRoots.isEmpty()) {
            throw new PathDeniedError(userPath, allowed, "没有可用于本次操作的根");
        }
        RootSet.Mount mount = roots.mountFor(input);
        Root root = mount.root();
        if (!access.allows(root)) {
            // 命中的挂载点对本次操作不可用（例如只读根里的文件被写操作碰到）：
            // 直接说「这个根不能这么用」，而不是让它落到锚点下变成「文件不存在」。
            throw new PathDeniedError(userPath, allowed,
                    "该根对本次操作不可用: " + root.id());
        }
        List<String> parts = new ArrayList<>();
        for (Path part : input) {
            parts.add(part.toString());
        }
        parts = parts.subList(mount.consumed(), parts.size());
        return resolveParts(root.path(), parts, 0, usableRoots, true, userPath);
    }

    /**
     * 解析并要求文件存在（读路径）。
     *
     * @param userPath 相对路径
     * @return 根内的真实路径
     * @throws IOException 文件不存在
     * @throws PathDeniedError 越界
     */
    public Path resolveExisting(String userPath) throws IOException {
        Path target = resolveForRead(userPath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("文件不存在: " + userPath);
        }
        return target;
    }

    /**
     * 解析并要求父目录存在（写路径；叶子可以不存在）。
     *
     * @param userPath 相对路径
     * @return 根内的真实路径
     * @throws IOException 父目录不存在或不是目录
     * @throws PathDeniedError 越界
     */
    public Path resolveForWrite(String userPath) throws IOException {
        Path target = resolveForWritePath(userPath);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new NoSuchFileException("父目录不存在: " + userPath);
        }
        return target;
    }

    /**
     * 该操作允许的根（给拒绝文案用）。
     *
     * @return 根路径列表
     */
    public List<String> allowedRoots() {
        return allowedRootPaths(roots.roots());
    }

    /**
     * 活根集合（加根后立即生效）。
     *
     * @return 根集合
     */
    public RootSet roots() {
        return roots;
    }

    // ---------- 内部 ----------

    private static List<String> allowedRootPaths(List<Root> allRoots) {
        List<String> allowed = new ArrayList<>(allRoots.size());
        for (Root root : allRoots) {
            allowed.add(root.path().toString());
        }
        return allowed;
    }

    /**
     * 逐段解析。
     *
     * @param start        起点（用户路径：根的真实路径；绝对路径：{@code /}）
     * @param parts        路径段
     * @param depth        已解析的符号链接层数
     * @param allRoots     全部根
     * @param containment  是否每一步都要确认在根内（绝对路径的中间态不在根内，所以是 false）
     * @param original     原始路径（错误文案用）
     * @return 解析后的真实路径
     */
    private Path resolveParts(Path start, List<String> parts, int depth, List<Root> allRoots,
                              boolean containment, String original) {
        Path current = start;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                Path parent = current.getParent();
                if (parent == null) {
                    throw new PathDeniedError(original, allowedRootPaths(allRoots), "路径越界");
                }
                current = parent;
                if (containment) {
                    ensureAllowed(current, allRoots, original, "路径越界");
                }
                continue;
            }
            Path next = current.resolve(part);
            if (Files.isSymbolicLink(next)) {
                if (depth >= MAX_SYMLINK_DEPTH) {
                    throw new PathDeniedError(original, allowedRootPaths(allRoots),
                            "符号链接层数超过上限 " + MAX_SYMLINK_DEPTH);
                }
                Path target = readLink(next, original, allRoots);
                Path absolute = target.isAbsolute() ? target : next.getParent().resolve(target);
                current = resolveAbsolute(absolute, depth + 1, allRoots, original);
                if (containment) {
                    ensureAllowed(current, allRoots, original, "符号链接指向根外");
                }
            } else {
                current = next;
                if (containment) {
                    ensureAllowed(current, allRoots, original, "路径越界");
                }
            }
        }
        return current;
    }

    /** 解析一个绝对路径（符号链接目标用）：从 {@code /} 逐段走，最终由调用方确认在根内。 */
    private Path resolveAbsolute(Path absolute, int depth, List<Root> allRoots, String original) {
        List<String> parts = new ArrayList<>();
        for (Path part : absolute) {
            parts.add(part.toString());
        }
        return resolveParts(Path.of("/"), parts, depth, allRoots, false, original);
    }

    private Path readLink(Path link, String original, List<Root> allRoots) {
        try {
            return Files.readSymbolicLink(link);
        } catch (IOException | UnsupportedOperationException e) {
            throw new PathDeniedError(original, allowedRootPaths(allRoots),
                    "符号链接无法读取: " + e.getMessage());
        }
    }

    private void ensureAllowed(Path candidate, List<Root> allRoots, String original, String reason) {
        for (Root root : allRoots) {
            if (candidate.startsWith(root.path())) {
                return;
            }
        }
        throw new PathDeniedError(original, allowedRootPaths(allRoots), reason);
    }
}
