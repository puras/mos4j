package net.hegq.mos4j.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱边界测试（T3.7–T3.9）：资源上限、进程组击杀强度声明、路径强制。
 */
class SandboxBoundaryTest {

    // ---------- 资源上限 ----------

    @Test
    void macOsSkipsVirtualMemoryLimit() {
        ResourceLimits limits = new ResourceLimits(5, 1024L, 10L);

        if (PathScopes.isMac()) {
            assertFalse(limits.shellSnippet().contains("ulimit -v"), limits.shellSnippet());
            assertTrue(limits.skipped().get(0).contains("macOS"), limits.skipped().toString());
            assertFalse(limits.applied().toString().contains("virtualMemory"), limits.applied().toString());
        } else {
            assertTrue(limits.shellSnippet().contains("ulimit -v"), limits.shellSnippet());
        }
    }

    @Test
    void appliedLimitsAreReported() {
        ResourceLimits limits = ResourceLimits.of(5, 10L);

        assertEquals(List.of("cpu=5s", "fileSize=10blocks"), limits.applied());
        assertTrue(limits.shellSnippet().contains("ulimit -t 5"));
        assertTrue(limits.shellSnippet().contains("ulimit -f 10"));
    }

    @Test
    void emptyLimitsProduceNoSnippet() {
        assertTrue(ResourceLimits.none().isEmpty());
        assertEquals("", ResourceLimits.none().shellSnippet());
    }

    // ---------- 进程组击杀 ----------

    @Test
    void descendantsKillerDeclaresItsWeakness() {
        String description = new DescendantsKiller().description();

        assertTrue(description.contains("尽力而为"), description);
        assertTrue(description.contains("非原子"), description);
        assertTrue(description.contains("不等价于 Go"), description);
    }

    @Test
    void descendantsKillerKillsTheProcess() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 30").start();

        new DescendantsKiller().kill(process);

        process.waitFor();
        assertFalse(process.isAlive());
    }

    // ---------- 路径强制 ----------

    @Test
    void seatbeltProfileDeniesWritesOutsideTheRoots() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/work")));

        String profile = scope.profile();

        assertTrue(profile.contains("(deny file-write*)"), profile);
        assertTrue(profile.contains("(allow file-write* (subpath \"/work\"))"), profile);
    }

    @Test
    void seatbeltProfileAllowsTheDevNullSink() {
        // 与 Go 的 seatbeltProfile 对齐：不放行 /dev/null 会把 `2>/dev/null` 这类正常命令拦掉
        String profile = new SeatbeltScope(List.of(Path.of("/work"))).profile();

        assertTrue(profile.contains("(allow file-write* (literal \"/dev/null\"))"), profile);
    }

    @Test
    void seatbeltProfileCanonicalisesSymlinkedRoots() throws Exception {
        // macOS 的 /var → /private/var：不规范化就会拦掉自己的写
        Path real = java.nio.file.Files.createDirectories(
                Path.of("target", "test-tmp", "seatbelt-canonical-" + System.nanoTime()).toAbsolutePath());

        String profile = new SeatbeltScope(List.of(real)).profile();

        assertTrue(profile.contains("(subpath \"" + real.toRealPath() + "\")"), profile);
    }

    @Test
    void runnerFailsClosedWhenThePathScopeCannotBeApplied() {
        PathScope exploding = new PathScope() {
            @Override
            public PathScope narrowTo(List<Path> writeRoots) {
                return this;
            }

            @Override
            public List<String> wrap(List<String> command) {
                throw new UnsupportedOperationException("没有后端");
            }

            @Override
            public List<Path> writeRoots() {
                return List.of(Path.of("/work"));
            }

            @Override
            public String description() {
                return "假的";
            }
        };
        Path work = Path.of("target", "test-tmp").toAbsolutePath();
        LocalRunner runner = LocalRunner.builder(work).pathScope(exploding).build();

        RunResult result = runner.run(new RunRequest("echo hi", null, null));

        assertFalse(result.started(), "路径强制装不上就不该跑命令");
        assertTrue(result.startFailure().contains("路径强制不可用"), result.startFailure());
        assertTrue(result.startFailure().contains("没有后端"), result.startFailure());
    }

    @Test
    void landlockWrapUsesAJavaLauncherAndRoundTripsTheRoots() {
        // 不碰 native：这条在任何平台都能验（Linux 真机才验内核强制）
        Path root = Path.of("/work/jail");
        List<String> command = List.of("/bin/sh", "-c", "true");

        List<String> wrapped = new LandlockScope(List.of(root)).wrap(command);

        assertEquals(LandlockLauncher.class.getName(), wrapped.get(6), wrapped.toString());
        assertTrue(wrapped.contains("--enable-native-access=ALL-UNNAMED"), wrapped.toString());
        assertTrue(wrapped.contains("-XX:-UsePerfData"), wrapped.toString());
        assertTrue(wrapped.stream().anyMatch(arg -> arg.startsWith("-Djava.io.tmpdir=")), wrapped.toString());
        String payload = new String(Base64.getUrlDecoder().decode(wrapped.get(7)), StandardCharsets.UTF_8);
        assertEquals(root.toString(), payload);
        assertEquals(command, wrapped.subList(8, wrapped.size()));
    }

    @Test
    void landlockScopeKeepsTheUnrestrictedEscapeHatch() {
        List<String> command = List.of("/bin/sh", "-c", "true");

        assertEquals(command, new LandlockScope(List.of(Path.of("/"))).wrap(command));
    }

    @Test
    void landlockScopeCannotBeWidened() {
        LandlockScope scope = new LandlockScope(List.of(Path.of("/work")));

        assertThrows(IllegalArgumentException.class, () -> scope.narrowTo(List.of(Path.of("/etc"))));
        assertEquals(List.of(Path.of("/work/a")), scope.narrowTo(List.of(Path.of("/work/a"))).writeRoots());
    }

    @Test
    void deriveUsesTheWorkDirectoryAsTheOnlyWriteRoot() {
        if (!PathScopes.supported()) {
            // 无后端平台：derive 必须明确报错（不做假降级）
            assertThrows(UnsupportedOperationException.class, () -> PathScopes.derive(Path.of("/work")));
            return;
        }
        Path work = Path.of("target", "test-tmp").toAbsolutePath();

        PathScope scope = PathScopes.derive(work);

        assertEquals(List.of(work), scope.writeRoots());
    }

    @Test
    void seatbeltWrapsTheCommand() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/work")));

        List<String> wrapped = scope.wrap(List.of("/bin/sh", "-c", "echo hi"));

        assertEquals("/usr/bin/sandbox-exec", wrapped.get(0));
        assertEquals("-p", wrapped.get(1));
        assertEquals("/bin/sh", wrapped.get(3));
    }

    @Test
    void pathScopeCanOnlyNarrow() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/work")));

        PathScope narrowed = scope.narrowTo(List.of(Path.of("/work/sub")));

        assertEquals(List.of(Path.of("/work/sub")), narrowed.writeRoots());
    }

    @Test
    void pathScopeRefusesToWiden() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/work")));

        assertThrows(IllegalArgumentException.class, () -> scope.narrowTo(List.of(Path.of("/etc"))));
        assertThrows(IllegalArgumentException.class, () -> scope.narrowTo(List.of(Path.of("/"))));
    }

    @Test
    void rootWriteRootIsAnExplicitEscapeHatch() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/")));

        assertFalse(scope.profile().contains("(deny file-write*)"), scope.profile());
        assertTrue(scope.description().contains("显式全开逃生门"), scope.description());
    }

    @Test
    void descriptionReportsTheBoundary() {
        SeatbeltScope scope = new SeatbeltScope(List.of(Path.of("/work")));

        String description = scope.description();

        assertTrue(description.contains("Seatbelt"), description);
        assertTrue(description.contains("/work"), description);
        assertTrue(description.contains("读=不限制"), description);
    }

    @Test
    void descriptionSaysTheScopeFailsClosedWhenItCannotBeApplied() {
        String description = new SeatbeltScope(List.of(Path.of("/work"))).description();

        assertTrue(description.contains("fail-closed"), description);
        assertTrue(description.contains("不会退化成无隔离执行"), description);
    }

    @Test
    void seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt() throws Exception {
        if (!PathScopes.isMac()) {
            return;
        }
        Path base = Path.of("target", "test-tmp", "seatbelt-" + System.nanoTime()).toAbsolutePath();
        Path allowed = java.nio.file.Files.createDirectories(base.resolve("allowed"));
        Path forbidden = base.resolve("forbidden.txt");
        SeatbeltScope scope = new SeatbeltScope(List.of(allowed));
        List<String> wrapped = scope.wrap(List.of("/bin/sh", "-c", "echo bad > " + forbidden));

        Process process = new ProcessBuilder(wrapped).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();

        if (output.contains("sandbox_apply")) {
            // 环境不允许应用 Seatbelt：强制效果无法验证，如实标成跳过（不假装通过）
            org.junit.jupiter.api.Assumptions.abort(
                    "当前环境不允许应用 Seatbelt（" + output.trim() + "），强制效果未验证");
        }
        assertFalse(java.nio.file.Files.exists(forbidden), "Seatbelt 应阻止写可写根之外: " + output);
    }

    @Test
    void factoryFailsLoudlyOnUnsupportedPlatform() {
        if (PathScopes.isMac()) {
            // macOS 有后端；不支持的平台分支无法在本机验证
            return;
        }
        assertThrows(UnsupportedOperationException.class, () -> PathScopes.create(Path.of("/work")));
    }
}
