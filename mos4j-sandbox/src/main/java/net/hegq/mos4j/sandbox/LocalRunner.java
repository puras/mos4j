package net.hegq.mos4j.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本地运行器：在宿主指定的边界里跑一条命令。
 *
 * <p><b>宿主固定，模型不能改</b>：
 * <ul>
 *   <li><b>工作目录</b>：构造时定死，模型给的工作目录一律忽略；</li>
 *   <li><b>环境</b>：裁剪后传入（默认白名单），模型的密钥不会顺着环境泄进子进程；</li>
 *   <li><b>超时</b>：{@code min(请求值 ?? 缺省值, 上界)}，模型要不来无限时间；</li>
 *   <li><b>输出上限</b>：同样被上界压住。</li>
 * </ul>
 *
 * <p><b>资源上限</b>通过 {@code ulimit} 前缀实现（见 {@link ResourceLimits}）。
 *
 * <p><b>超时后</b>调用 {@link ProcessGroupKiller} 收拾残局，默认是降级实现
 * （{@link DescendantsKiller}），强度说明见 {@link ProcessGroupKiller#description()}。
 */
public final class LocalRunner implements Runner {

    /** 缺省超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /** 超时上界（模型要多大都没用）。 */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    /** 缺省输出上限（字节）。 */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;
    /** 环境白名单（默认只透传这些）。 */
    public static final List<String> DEFAULT_ENV_ALLOWLIST =
            List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "LOGNAME");

    private static final String SHELL = "/bin/sh";

    private final Path workingDirectory;
    private final Duration defaultTimeout;
    private final Duration maxTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> environment;
    private final ResourceLimits limits;
    private final ProcessGroupKiller killer;
    private final PathScope pathScope;

    private LocalRunner(Builder builder) {
        this.workingDirectory = builder.workingDirectory;
        this.defaultTimeout = builder.defaultTimeout;
        this.maxTimeout = builder.maxTimeout;
        this.maxOutputBytes = builder.maxOutputBytes;
        this.environment = Map.copyOf(builder.environment);
        this.limits = builder.limits;
        this.killer = builder.killer;
        this.pathScope = builder.pathScope;
    }

    /**
     * @param workingDirectory 宿主固定的工作目录
     * @return 构建器
     */
    public static Builder builder(Path workingDirectory) {
        return new Builder(workingDirectory);
    }

    @Override
    public RunResult run(RunRequest request) {
        long startedAt = System.nanoTime();
        Duration effectiveTimeout = effectiveTimeout(request);
        int effectiveCap = effectiveCap(request);
        List<String> commandLine;
        try {
            commandLine = buildCommandLine(request.command());
        } catch (RuntimeException e) {
            // 路径强制装不上就不跑：fail-closed，不退化成「无隔离执行」
            return RunResult.notStarted("路径强制不可用: " + e.getMessage(),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }

        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(workingDirectory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return RunResult.notStarted("命令启动失败: " + e.getMessage(), Duration.ofNanos(System.nanoTime() - startedAt));
        }

        boolean timedOut = false;
        boolean[] outTruncated = {false};
        boolean[] errTruncated = {false};
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = pool.submit(() -> drain(process.getInputStream(), effectiveCap, outTruncated));
            Future<byte[]> stderr = pool.submit(() -> drain(process.getErrorStream(), effectiveCap, errTruncated));

            if (!process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                killer.kill(process);
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String out = new String(readQuietly(stdout), StandardCharsets.UTF_8);
            String err = new String(readQuietly(stderr), StandardCharsets.UTF_8);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            return new RunResult(exitCode, out, err, timedOut, outTruncated[0] || errTruncated[0], duration, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killer.kill(process);
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            return RunResult.notStarted("命令被中断: " + e.getMessage(), duration);
        }
    }

    /**
     * 生效超时：{@code min(请求值 ?? 缺省值, 上界)}。
     *
     * @param request 请求
     * @return 生效超时
     */
    public Duration effectiveTimeout(RunRequest request) {
        Duration requested = request.timeout() == null ? defaultTimeout : request.timeout();
        return requested.compareTo(maxTimeout) > 0 ? maxTimeout : requested;
    }

    /**
     * 生效输出上限（同样被上界压住）。
     *
     * @param request 请求
     * @return 生效上限（字节）
     */
    public int effectiveCap(RunRequest request) {
        Integer requested = request.maxOutputBytes();
        if (requested == null) {
            return maxOutputBytes;
        }
        return Math.min(requested, maxOutputBytes);
    }

    private List<String> buildCommandLine(String command) {
        String snippet = limits.shellSnippet();
        String script = snippet.isEmpty() ? command : snippet + "; " + command;
        List<String> line = new ArrayList<>();
        line.add(SHELL);
        line.add("-c");
        line.add(script);
        if (pathScope != null) {
            return pathScope.wrap(line);
        }
        return line;
    }

    private static byte[] drain(InputStream stream, int cap, boolean[] truncated) throws IOException {
        ByteArrayOutputStream keep = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(chunk)) > 0) {
            total += read;
            int room = cap - keep.size();
            if (room > 0) {
                keep.write(chunk, 0, Math.min(room, read));
            }
            // 超出上限的部分继续读走（否则子进程写满管道会挂住），只是不保留
        }
        truncated[0] = total > cap;
        return keep.toByteArray();
    }

    private static byte[] readQuietly(Future<byte[]> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (ExecutionException | TimeoutException e) {
            return new byte[0];
        }
    }

    /**
     * 宿主固定的工作目录。
     *
     * @return 工作目录
     */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * 缺省超时。
     *
     * @return 超时
     */
    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * 超时上界。
     *
     * @return 上界
     */
    public Duration maxTimeout() {
        return maxTimeout;
    }

    /**
     * 输出上限。
     *
     * @return 字节数
     */
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /**
     * 传入子进程的环境（已裁剪）。
     *
     * @return 环境变量
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * 资源上限。
     *
     * @return 上限
     */
    public ResourceLimits limits() {
        return limits;
    }

    /**
     * 进程组击杀器。
     *
     * @return 击杀器
     */
    public ProcessGroupKiller killer() {
        return killer;
    }

    /**
     * 路径强制（可为 null）。
     *
     * @return 路径强制
     */
    public PathScope pathScope() {
        return pathScope;
    }

    /**
     * 构建器。
     */
    public static final class Builder {

        private final Path workingDirectory;
        private Duration defaultTimeout = DEFAULT_TIMEOUT;
        private Duration maxTimeout = MAX_TIMEOUT;
        private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private ResourceLimits limits = ResourceLimits.none();
        private ProcessGroupKiller killer = new DescendantsKiller();
        private PathScope pathScope;

        private Builder(Path workingDirectory) {
            if (workingDirectory == null) {
                throw new IllegalArgumentException("sandbox: 工作目录不能为 null");
            }
            this.workingDirectory = workingDirectory;
            // 默认就是「裁剪后的宿主环境」：PATH 等必需项保留，其余（含密钥）不外泄
            inheritEnvironment(System.getenv(), DEFAULT_ENV_ALLOWLIST);
        }

        /**
         * 缺省超时。
         *
         * @param timeout 超时
         * @return this
         */
        public Builder defaultTimeout(Duration timeout) {
            this.defaultTimeout = timeout;
            return this;
        }

        /**
         * 超时上界。
         *
         * @param timeout 上界
         * @return this
         */
        public Builder maxTimeout(Duration timeout) {
            this.maxTimeout = timeout;
            return this;
        }

        /**
         * 输出上限。
         *
         * @param bytes 字节数
         * @return this
         */
        public Builder maxOutputBytes(int bytes) {
            this.maxOutputBytes = bytes;
            return this;
        }

        /**
         * 从宿主环境里按白名单裁剪。
         *
         * @param hostEnvironment 宿主环境（一般是 {@code System.getenv()}）
         * @param allowlist       白名单
         * @return this
         */
        public Builder inheritEnvironment(Map<String, String> hostEnvironment, List<String> allowlist) {
            for (String key : allowlist) {
                String value = hostEnvironment.get(key);
                if (value != null) {
                    environment.put(key, value);
                }
            }
            return this;
        }

        /**
         * 追加环境变量。
         *
         * @param key   变量名
         * @param value 变量值
         * @return this
         */
        public Builder env(String key, String value) {
            environment.put(key, value);
            return this;
        }

        /**
         * 资源上限。
         *
         * @param limits 上限
         * @return this
         */
        public Builder limits(ResourceLimits limits) {
            this.limits = limits;
            return this;
        }

        /**
         * 进程组击杀器。
         *
         * @param killer 击杀器
         * @return this
         */
        public Builder killer(ProcessGroupKiller killer) {
            this.killer = killer;
            return this;
        }

        /**
         * 路径强制。
         *
         * @param pathScope 路径强制
         * @return this
         */
        public Builder pathScope(PathScope pathScope) {
            this.pathScope = pathScope;
            return this;
        }

        /**
         * 构建。
         *
         * @return 运行器
         */
        public LocalRunner build() {
            return new LocalRunner(this);
        }
    }
}
