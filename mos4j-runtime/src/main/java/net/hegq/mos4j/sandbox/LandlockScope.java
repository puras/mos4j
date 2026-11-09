package net.hegq.mos4j.sandbox;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Linux 的路径强制后端：Landlock LSM（内核 5.13 起）。
 *
 * <p><b>语义对齐 macOS Seatbelt 与 Go 的 landlock 后端：只收窄写</b>。读不强制——
 * deny 读会碎掉动态链接器、locale、系统证书与包缓存，成本远超收益；而这个执行器的
 * 事故威胁模型全在<b>写</b>侧。写只在声明的写根里允许，由<b>内核</b>强制。
 *
 * <p><b>为什么是「重新执行自己的 java 进程」</b>：Landlock 的
 * {@code landlock_restrict_self(2)} 只对<b>调用它的那个线程</b>生效，而 Java 没有
 * fork 与 exec 之间的钩子（也不能 exec 替换自身）。所以要给<b>子进程</b>单独套约束，
 * 只能让子进程在起命令之前自己套：父进程把写根交给「重新执行出来的 java 启动器」，
 * 启动器套上 Landlock 再起 {@code /bin/sh -c …}，约束由内核在 fork/exec 时继承，
 * 命令及其所有后代都在约束里。
 *
 * <p><b>与 Go 的三处差异（诚实边界）</b>：
 * <ol>
 *   <li><b>载荷走命令行参数</b>（base64 + {@code \n} 分隔），不是 Go 的管道 fd——
 *       {@code LocalRunner} 不接管子进程 stdin，走 argv 不必改运行器；代价是写根太多
 *       会撞 {@code ARG_MAX}（128KB，实测可放上千个根）；</li>
 *   <li><b>ABI 探测在启动器里做</b>，不是 wrap 阶段：宿主进程不调用任何 native
 *       方法（否则会在宿主的 stderr 上打 {@code --enable-native-access} 警告）。所以
 *       内核没有 Landlock 时，错误在命令执行阶段暴露（退出码 125 + 可读消息），
 *       而不是装配阶段；</li>
 *   <li><b>没有 {@code refer}/{@code truncate} 的降级链之外的东西</b>：权限集按 ABI
 *       分档，与 Go 的 V1/V2/V3 三档一致。</li>
 * </ol>
 *
 * <p><b>fail-closed</b>：套不上就退出 125，<b>绝不退化成「没隔离的假装隔离」</b>。
 */
public final class LandlockScope implements PathScope {

    /** {@code landlock_create_ruleset} 的系统调用号（x86_64 与 aarch64 相同）。 */
    static final long SYS_LANDLOCK_CREATE_RULESET = 444L;
    /** {@code landlock_add_rule} 的系统调用号。 */
    static final long SYS_LANDLOCK_ADD_RULE = 445L;
    /** {@code landlock_restrict_self} 的系统调用号。 */
    static final long SYS_LANDLOCK_RESTRICT_SELF = 446L;
    /** 规则类型：路径之下。 */
    static final int RULE_PATH_BENEATH = 1;
    /** {@code landlock_create_ruleset} 的 flag：只查 ABI 版本。 */
    static final int CREATE_RULESET_VERSION = 1;
    /** {@code prctl} 的 {@code PR_SET_NO_NEW_PRIVS}。 */
    static final int PR_SET_NO_NEW_PRIVS = 38;

    /** 写类权限：改内容、删、建（文件/目录/符号链接/各类节点）。 */
    private static final long ACCESS_FS_WRITE_FILE = 1L << 1;
    private static final long ACCESS_FS_REMOVE_DIR = 1L << 4;
    private static final long ACCESS_FS_REMOVE_FILE = 1L << 5;
    private static final long ACCESS_FS_MAKE_CHAR = 1L << 6;
    private static final long ACCESS_FS_MAKE_DIR = 1L << 7;
    private static final long ACCESS_FS_MAKE_REG = 1L << 8;
    private static final long ACCESS_FS_MAKE_SOCK = 1L << 9;
    private static final long ACCESS_FS_MAKE_FIFO = 1L << 10;
    private static final long ACCESS_FS_MAKE_BLOCK = 1L << 11;
    private static final long ACCESS_FS_MAKE_SYM = 1L << 12;
    private static final long ACCESS_FS_REFER = 1L << 13;
    private static final long ACCESS_FS_TRUNCATE = 1L << 14;

    /** 写根（目录）获得的权限：只写类，不含读/执行。 */
    private static final long WRITE_RIGHTS = ACCESS_FS_WRITE_FILE
            | ACCESS_FS_REMOVE_DIR | ACCESS_FS_REMOVE_FILE
            | ACCESS_FS_MAKE_CHAR | ACCESS_FS_MAKE_DIR | ACCESS_FS_MAKE_REG
            | ACCESS_FS_MAKE_SOCK | ACCESS_FS_MAKE_FIFO | ACCESS_FS_MAKE_BLOCK | ACCESS_FS_MAKE_SYM;

    /** {@code /dev/null} 是 shell 的通用丢弃口：不放行它，`2>/dev/null` 这类命令会直接失败。 */
    private static final List<String> SINK_FILES = List.of("/dev/null");

    /** {@code O_PATH | O_CLOEXEC}（x86_64 与 aarch64 相同）。 */
    private static final int O_PATH = 0x200000;
    private static final int O_CLOEXEC = 0x80000;

    /** {@code landlock_ruleset_attr}：只有一个 u64 字段。 */
    private static final MemoryLayout RULESET_ATTR = MemoryLayout.structLayout(JAVA_LONG.withName("handled_access_fs"));
    /**
     * {@code landlock_path_beneath_attr}：u64 + s32。
     *
     * <p>内核侧是 packed（12 字节），这里不强行改对齐（group layout 不能压到自然对齐以下）：
     * 内存里的字段偏移正好是 0 与 8，与 packed 一致；内核只读前 12 字节，尾部 padding 无害。
     */
    private static final StructLayout PATH_BENEATH_ATTR = MemoryLayout.structLayout(
            JAVA_LONG.withName("allowed_access"), JAVA_INT.withName("parent_fd"));

    private final List<Path> writeRoots;

    /**
     * @param writeRoots 初始允许写入的根
     */
    public LandlockScope(List<Path> writeRoots) {
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
            if (!covered(root)) {
                throw new IllegalArgumentException(
                        "sandbox: PathScope 只能收窄，不能放开: " + root + "（当前范围 " + writeRoots + "）");
            }
        }
        return new LandlockScope(roots);
    }

    /**
     * 把命令包进 Landlock 启动器。
     *
     * <p>写根含 {@code /} 是显式全开逃生门：不套约束，原样返回（危险写在 {@link #description()} 里）。
     */
    @Override
    public List<String> wrap(List<String> command) {
        if (unrestricted()) {
            return command;
        }
        Path java = javaExecutable();
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("\n", writeRoots.stream().map(Path::toString).toList())
                        .getBytes(StandardCharsets.UTF_8));
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isEmpty()) {
            throw new UnsupportedOperationException(
                    "sandbox: 拿不到 java.class.path（Landlock 启动器要重新执行自己）：不能假装有路径强制");
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add(java.toString());
        wrapped.add("--enable-native-access=ALL-UNNAMED");
        wrapped.add("-XX:-UsePerfData");
        wrapped.add("-Djava.io.tmpdir=" + tmpDirectory());
        wrapped.add("-cp");
        wrapped.add(classpath);
        wrapped.add(LandlockLauncher.class.getName());
        wrapped.add(payload);
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
        sb.append("路径强制=Linux Landlock LSM（内核级，只收窄写）；");
        if (unrestricted()) {
            sb.append("允许写入=/（显式全开逃生门，不做写限制）；");
        } else {
            sb.append("允许写入=").append(writeRoots).append("；");
        }
        sb.append("读=不限制（读由文件 jail 负责）；");
        sb.append("强度=内核判定，命令及其所有后代都在约束里；");
        sb.append("已知缺口=内核 <5.19 时跨目录重命名、<6.2 时 `> file` 截断写不在强制范围；");
        sb.append("套不上时命令直接失败（fail-closed，退出码 125），不会退化成无隔离执行");
        return sb.toString();
    }

    /**
     * 给<b>当前进程</b>套上 Landlock（只在启动器进程里调用）。
     *
     * @param roots 允许写入的根（相对路径会被绝对化）
     * @return 生效的内核 ABI 版本（≥1）
     * @throws IllegalStateException 内核不支持或套用失败
     */
    public static int applyToCurrentProcess(List<String> roots) {
        int abi = abiVersion();
        if (abi < 1) {
            throw new IllegalStateException(
                    "本机内核没有 Landlock（需要 Linux ≥5.13 且启用 CONFIG_SECURITY_LANDLOCK）");
        }
        long handled = WRITE_RIGHTS;
        long dirRights = WRITE_RIGHTS;
        long fileRights = ACCESS_FS_WRITE_FILE;
        if (abi >= 2) {
            handled |= ACCESS_FS_REFER;
            dirRights |= ACCESS_FS_REFER;
        }
        if (abi >= 3) {
            handled |= ACCESS_FS_TRUNCATE;
            dirRights |= ACCESS_FS_TRUNCATE;
            fileRights |= ACCESS_FS_TRUNCATE;
        }
        if (!noNewPrivileges()) {
            throw new IllegalStateException("设置 PR_SET_NO_NEW_PRIVS 失败（Landlock 的前置条件）");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attr = arena.allocate(RULESET_ATTR);
            attr.set(JAVA_LONG, 0L, handled);
            long ruleset = syscall(SYS_LANDLOCK_CREATE_RULESET, attr.address(), RULESET_ATTR.byteSize(), 0L);
            if (ruleset < 0) {
                throw new IllegalStateException("landlock_create_ruleset 失败（可能是内核未启用 Landlock LSM）");
            }
            try {
                for (String root : roots) {
                    Path path = Path.of(root).toAbsolutePath().normalize();
                    if (!Files.exists(path)) {
                        // 不存在的根没有 inode 可绑 → 跳过（fail-closed：写它会被拒）
                        continue;
                    }
                    addPathBeneath(arena, ruleset, path, dirRights);
                }
                for (String sink : SINK_FILES) {
                    addPathBeneath(arena, ruleset, Path.of(sink), fileRights);
                }
                if (syscall(SYS_LANDLOCK_RESTRICT_SELF, ruleset, 0L) < 0) {
                    throw new IllegalStateException("landlock_restrict_self 失败");
                }
            } finally {
                closeQuietly(ruleset);
            }
        }
        return abi;
    }

    /**
     * 探测内核的 Landlock ABI 版本（0 = 不支持）。
     *
     * @return ABI 版本
     */
    public static int abiVersion() {
        long version = syscall(SYS_LANDLOCK_CREATE_RULESET, 0L, 0L, CREATE_RULESET_VERSION);
        return version < 0 ? 0 : (int) version;
    }

    private static void addPathBeneath(Arena arena, long ruleset, Path path, long rights) {
        MemorySegment name = arena.allocateFrom(path.toString());
        int fd = open(name, O_PATH | O_CLOEXEC);
        if (fd < 0) {
            throw new IllegalStateException("打开写根失败: " + path);
        }
        try {
            MemorySegment rule = arena.allocate(PATH_BENEATH_ATTR);
            rule.set(JAVA_LONG, 0L, rights);
            rule.set(JAVA_INT, 8L, fd);
            if (syscall(SYS_LANDLOCK_ADD_RULE, ruleset, RULE_PATH_BENEATH, rule.address()) < 0) {
                throw new IllegalStateException("landlock_add_rule 失败: " + path);
            }
        } finally {
            closeQuietly(fd);
        }
    }

    private static void closeQuietly(long fd) {
        if (fd >= 0) {
            close((int) fd);
        }
    }

    private static boolean noNewPrivileges() {
        return prctl(PR_SET_NO_NEW_PRIVS, 1L) == 0;
    }

    private Path javaExecutable() {
        Path java = Path.of(System.getProperty("java.home", ""), "bin", "java");
        if (!Files.isExecutable(java)) {
            throw new UnsupportedOperationException(
                    "sandbox: 找不到可执行的 java（Landlock 启动器要重新执行自己）: " + java);
        }
        return java;
    }

    private String tmpDirectory() {
        Path first = writeRoots.get(0).toAbsolutePath().normalize();
        Path tmp = first.resolve(".mos4j-tmp");
        return Files.isDirectory(tmp) ? tmp.toString() : first.toString();
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
                return true;
            }
            if (candidate.equals(root) || candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    // ---------- libc 绑定（只在启动器进程里被调用） ----------

    /**
     * 绑定一律惰性解析（只在 {@link #applyToCurrentProcess(List)} 里被访问）：
     * 宿主平台（macOS / Windows）上这个类会被装载、也可能被反射，
     * 类初始化时就去解析 {@code prctl} 会直接炸——macOS 的 libc 没有 {@code prctl}。
     */
    private static final class Libc {

        private static final Linker LINKER = Linker.nativeLinker();

        /** {@code long syscall(long number, ...)}：变参，参数一律按 long 传（地址用 address()）。 */
        private static final MethodHandle SYSCALL = downcall("syscall",
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG), 1);
        /** {@code int prctl(int option, unsigned long arg2)}。 */
        private static final MethodHandle PRCTL = downcall("prctl",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG), 1);
        /** {@code int open(const char *path, int flags)}。 */
        private static final MethodHandle OPEN = downcall("open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), 2);
        /** {@code int close(int fd)}（非变参）。 */
        private static final MethodHandle CLOSE = downcall("close",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT), -1);

        private static MethodHandle downcall(String name, FunctionDescriptor descriptor, int firstVariadic) {
            MemorySegment symbol = LINKER.defaultLookup().find(name).orElseThrow(
                    () -> new IllegalStateException("libc 缺符号（Landlock 后端只在 Linux 上有）: " + name));
            return firstVariadic < 0
                    ? LINKER.downcallHandle(symbol, descriptor)
                    : LINKER.downcallHandle(symbol, descriptor, Linker.Option.firstVariadicArg(firstVariadic));
        }

        private Libc() {
        }
    }

    private static long syscall(long number, long a1) {
        return syscall(number, a1, 0L, 0L, 0L);
    }

    private static long syscall(long number, long a1, long a2) {
        return syscall(number, a1, a2, 0L, 0L);
    }

    private static long syscall(long number, long a1, long a2, long a3) {
        return syscall(number, a1, a2, a3, 0L);
    }

    private static long syscall(long number, long a1, long a2, long a3, long a4) {
        try {
            return (long) Libc.SYSCALL.invokeExact(number, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw new IllegalStateException("系统调用失败: " + number + ": " + t.getMessage(), t);
        }
    }

    private static int prctl(int option, long arg) {
        try {
            return (int) Libc.PRCTL.invokeExact(option, arg);
        } catch (Throwable t) {
            throw new IllegalStateException("prctl 失败: " + t.getMessage(), t);
        }
    }

    private static int open(MemorySegment path, int flags) {
        try {
            return (int) Libc.OPEN.invokeExact(path, flags);
        } catch (Throwable t) {
            throw new IllegalStateException("open 失败: " + t.getMessage(), t);
        }
    }

    private static int close(int fd) {
        try {
            return (int) Libc.CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            return -1;
        }
    }
}