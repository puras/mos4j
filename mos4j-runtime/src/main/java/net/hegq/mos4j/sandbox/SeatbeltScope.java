package net.hegq.mos4j.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS Seatbelt 后端：把可写目录限制在给定根内。
 *
 * <p><b>为什么是内核级</b>：Seatbelt（{@code sandbox-exec}）的判定发生在内核里，
 * 被沙住的进程<b>绕不过</b>它——不像应用层约定那样「模型说一句不许就不许」。
 *
 * <p><b>Profile 规则顺序</b>：Seatbelt 按顺序匹配，<b>最后一条命中的规则生效</b>，
 * 所以规则写成「先放开、再禁写、再逐个放行子路径」。
 *
 * <p><b>读不限制</b>：本后端只约束写。读的限制由文件 jail（{@code net.hegq.mos4j.fs} 包）负责，
 * 两层各管一件事，不重复也不互相顶替。
 */
public final class SeatbeltScope implements PathScope {

    /** macOS 自带的沙箱执行器（Apple 未公开文档化）。 */
    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    private final List<Path> writeRoots;

    /**
     * @param writeRoots 初始允许写入的根
     */
    public SeatbeltScope(List<Path> writeRoots) {
        if (writeRoots == null || writeRoots.isEmpty()) {
            throw new IllegalArgumentException("sandbox: 至少要有一个写入根");
        }
        this.writeRoots = List.copyOf(writeRoots);
    }

    @Override
    public PathScope narrowTo(List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("sandbox: 至少要有一个写入根");
        }
        for (Path root : roots) {
            // 与 Go 的 validateScope 对齐：根必须是非空的绝对路径（相对路径会让
            // “覆盖关系”变成拿 CWD 猜的模糊判断）
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("sandbox: 根 " + root + " 不是绝对路径");
            }
            if (!covered(root)) {
                throw new IllegalArgumentException(
                        "sandbox: PathScope 只能收窄，不能放开: " + root + "（当前范围 " + writeRoots + "）");
            }
        }
        return new SeatbeltScope(roots);
    }

    @Override
    public List<String> wrap(List<String> command) {
        // fail-closed：执行器缺失就报错，不退化成「假装有路径强制」的执行
        if (!Files.isExecutable(SANDBOX_EXEC)) {
            throw new UnsupportedOperationException(
                    "sandbox: sandbox-exec 不可用（" + SANDBOX_EXEC + " 缺失或不可执行）：不能假装有路径强制");
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add(SANDBOX_EXEC.toString());
        wrapped.add("-p");
        wrapped.add(profile());
        wrapped.addAll(command);
        return wrapped;
    }

    @Override
    public List<Path> writeRoots() {
        return writeRoots;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("路径强制=macOS Seatbelt（sandbox-exec，内核级）；");
        if (unrestricted()) {
            sb.append("允许写入=/（显式全开逃生门，不做写限制）；");
        } else {
            sb.append("允许写入=").append(writeRoots).append("；");
        }
        sb.append("读=不限制（读由文件 jail 负责）；");
        sb.append("强度=进程级，内核判定，绕不过；");
        sb.append("宿主不允许应用时（sandbox_apply 失败）命令直接失败（fail-closed），不会退化成无隔离执行");
        return sb.toString();
    }

    /**
     * 生成 Seatbelt profile。
     *
     * <p><b>形态与 Go 的 {@code seatbeltProfile} 一致</b>（一行，DSH 同款）：
     * {@code (version 1) (allow default) (deny file-write*) (allow file-write* (literal "/dev/null"))
     * (allow file-write* (subpath "<写根>")) ...}
     *
     * <p>规则顺序不能改：Seatbelt 按顺序匹配、<b>最后一条命中的规则生效</b>，
     * 所以必须先全放、再禁写、再逐个放行写根。
     *
     * @return profile 文本
     */
    public String profile() {
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1) (allow default)");
        if (!unrestricted()) {
            sb.append(" (deny file-write*)");
            // /dev/null 是 shell 的通用丢弃口（`2>/dev/null`）：不放行它，
            // 一大批正常命令会直接失败（与 Go 的 seatbeltProfile 对齐）
            sb.append(" (allow file-write* (literal \"/dev/null\"))");
            for (Path root : writeRoots) {
                sb.append(" (allow file-write* (subpath ").append(sbplString(canonical(root))).append("))");
            }
        }
        return sb.toString();
    }

    /**
     * 规范化写根：Seatbelt 按内核解析后的路径匹配，软链根（如 {@code /var} → {@code /private/var}）
     * 不规范化就会拦掉自己的写。
     *
     * <p>根尚不存在时（宿主声明了「以后会创建」的目录）退回绝对规范化路径：写一个不存在的
     * 根本来就会失败，不会静默放宽。
     */
    private static Path canonical(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /** SBPL 字符串字面量（转义反斜杠与双引号）。 */
    private static String sbplString(Path path) {
        String escaped = path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private boolean unrestricted() {
        for (Path root : writeRoots) {
            if (root.getParent() == null) {
                return true; // "/"：文件系统根 = 显式全开
            }
        }
        return false;
    }

    private boolean covered(Path candidate) {
        for (Path root : writeRoots) {
            if (root.getParent() == null) {
                return true; // 当前就是全开，什么都能收窄到
            }
            if (candidate.equals(root) || candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
