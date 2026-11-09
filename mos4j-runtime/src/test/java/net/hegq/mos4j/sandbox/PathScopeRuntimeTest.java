package net.hegq.mos4j.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径强制的运行期形态：动态写根（对齐 Go 的 {@code Local.Scope}）、可用性探测、
 * 以及「未装配」的原因如实上报。
 *
 * <p>三条纪律：
 * <ul>
 *   <li>动态写根**每次调用现取**（会话里授权了新目录，shell 跟着能写）；</li>
 *   <li>写根为空 / 来源抛错 → **fail-closed**（命令不跑），绝不「当成无边界执行」；</li>
 *   <li>「没装」要说清是**平台没有**还是**宿主显式关闭**——两者对宿主的含义完全不同。</li>
 * </ul>
 */
class PathScopeRuntimeTest {

    private Path workDir;

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createDirectories(
                Path.of("target", "test-tmp", "scope-" + System.nanoTime()).toAbsolutePath());
    }

    /**
     * 记录用的假工厂：把每次造 scope 时用的写根留下来。
     */
    private static final class RecordingFactory implements PathScopeFactory {
        private final List<List<Path>> seen = new ArrayList<>();

        @Override
        public PathScope create(List<Path> writeRoots) {
            seen.add(List.copyOf(writeRoots));
            return new PathScope() {
                @Override
                public PathScope narrowTo(List<Path> roots) {
                    throw new UnsupportedOperationException("测试替身不支持收窄");
                }

                @Override
                public List<String> wrap(List<String> command) {
                    return command;
                }

                @Override
                public List<Path> writeRoots() {
                    return writeRoots;
                }

                @Override
                public String description() {
                    return "路径强制=测试替身；写根=" + writeRoots;
                }
            };
        }
    }

    @Test
    void dynamicWriteRootsAreFetchedOnEveryCall() {
        // Go 的 Local.Scope 语义：每次调用现取写根（授权了新目录 → 跟着放行）
        AtomicReference<List<Path>> live = new AtomicReference<>(List.of(workDir));
        RecordingFactory factory = new RecordingFactory();
        LocalRunner runner = LocalRunner.builder(workDir)
                .pathScopeSupplier(live::get)
                .pathScopeFactory(factory)
                .build();

        runner.run(RunRequest.of("echo one"));
        Path extra = workDir.resolve("extra");
        live.set(List.of(workDir, extra));
        runner.run(RunRequest.of("echo two"));

        assertEquals(2, factory.seen.size(), "每次调用都要现取一次写根");
        assertEquals(List.of(workDir), factory.seen.get(0));
        assertEquals(List.of(workDir, extra), factory.seen.get(1),
                "写根变化后，下一次调用必须用新的集合（文件工具与 shell 不同步的坑）");
    }

    @Test
    void emptyWriteRootsFailClosedInsteadOfRunningUnbounded() {
        LocalRunner runner = LocalRunner.builder(workDir)
                .pathScopeSupplier(() -> List.of())
                .pathScopeFactory(new RecordingFactory())
                .build();

        RunResult result = runner.run(RunRequest.of("echo should-not-run"));

        assertFalse(result.started(), "写根为空就不该跑命令");
        assertNotNull(result.startFailure());
        assertTrue(result.startFailure().contains("路径强制不可用"), result.startFailure());
    }

    @Test
    void aFailingScopeSourceFailsClosed() {
        LocalRunner runner = LocalRunner.builder(workDir)
                .pathScopeSupplier(() -> {
                    throw new IllegalStateException("根集合读不到");
                })
                .pathScopeFactory(new RecordingFactory())
                .build();

        RunResult result = runner.run(RunRequest.of("echo should-not-run"));

        assertFalse(result.started(), "取写根失败就不该跑命令");
        assertTrue(result.startFailure().contains("路径强制不可用"), result.startFailure());
    }

    @Test
    void theAbsenceReasonIsReported() {
        LocalRunner runner = LocalRunner.builder(workDir)
                .noPathScope("宿主显式关闭（这台机器没有沙箱）")
                .build();

        assertNull(runner.pathScope());
        assertEquals("宿主显式关闭（这台机器没有沙箱）", runner.pathScopeAbsentReason());

        List<String> caveats = SandboxBoundary.of(runner).caveats();
        assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=未装配")
                && c.contains("宿主显式关闭")), caveats.toString());
    }

    @Test
    void theAbsenceReasonDefaultsToAnExplicitStatement() {
        LocalRunner runner = LocalRunner.builder(workDir).noPathScope(null).build();

        assertEquals("宿主显式关闭", runner.pathScopeAbsentReason());
    }

    @Test
    void probeResultIsSelfConsistent() {
        PathScopeProbe.Result result = PathScopes.probe();

        assertNotNull(result);
        assertFalse(result.detail().isBlank(), "探测必须说清为什么（可用也要说可用在哪）");
        if (!result.available()) {
            assertTrue(result.detail().contains("不允许应用")
                            || result.detail().contains("缺失或不可执行")
                            || result.detail().contains("未验证")
                            || result.detail().contains("未实现")
                            || result.detail().contains("探测"),
                    "不可用时要说清原因: " + result.detail());
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void probeDistinguishesApplyDenialFromMissingBackend() {
        // 本机能跑 sandbox-exec 但宿主可能拒绝 apply：两种情况的文案必须能分辨
        PathScopeProbe.Result result = PathScopes.probe();

        if (!result.available()) {
            assertTrue(result.detail().contains("不允许应用")
                    || result.detail().contains("缺失或不可执行")
                    || result.detail().contains("探测"),
                    "不可用原因要具体: " + result.detail());
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void factoryBuildsTheSeatbeltBackend() {
        PathScope scope = PathScopes.factory().create(List.of(workDir));

        assertTrue(scope instanceof SeatbeltScope, "macOS 的工厂应该造 Seatbelt: " + scope.getClass());
        assertEquals(List.of(workDir), scope.writeRoots());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void factoryBuildsTheLandlockBackend() {
        PathScope scope = PathScopes.factory().create(List.of(workDir));

        assertTrue(scope instanceof LandlockScope, "Linux 的工厂应该造 Landlock: " + scope.getClass());
    }

    @Test
    void aDynamicScopeIsReportedAsInstalled() {
        // 动态模式下 pathScope() 是空的，但命令确实被强制着——
        // Describe 说「未装配」就是假话（Describe 必须是事实）
        LocalRunner runner = LocalRunner.builder(workDir)
                .pathScopeSupplier(() -> List.of(workDir))
                .pathScopeFactory(new RecordingFactory())
                .build();

        List<String> caveats = SandboxBoundary.of(runner).caveats();

        assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=已装配")), caveats.toString());
        assertFalse(caveats.stream().anyMatch(c -> c.contains("路径强制=未装配")), caveats.toString());
    }

    @Test
    void boundaryDoesNotRepeatThePathScopePrefix() {
        LocalRunner runner = LocalRunner.builder(workDir)
                .pathScope(new SeatbeltScope(List.of(workDir)))
                .build();

        List<String> caveats = SandboxBoundary.of(runner).caveats();

        assertTrue(caveats.stream().noneMatch(c -> c.contains("路径强制=路径强制=")),
                "别把前缀拼两遍: " + caveats);
    }
}
