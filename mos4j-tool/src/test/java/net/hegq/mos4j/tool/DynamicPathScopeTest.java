package net.hegq.mos4j.tool;

import net.hegq.mos4j.fs.Root;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.sandbox.PathScope;
import net.hegq.mos4j.sandbox.RunRequest;
import net.hegq.mos4j.sandbox.RunResult;
import net.hegq.mos4j.tool.builtin.GrantAccessTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态写根（Q2′）：文件 jail 与 shell 沙箱共用同一份**活**根集合。
 *
 * <p>对位 Go 的 {@code Local.Scope func() *PathScope}：Go 里
 * {@code sandbox.defaultScope()} 每次调用现取写根，所以「会话里授权了新目录，
 * 下一个 shell 命令就能写那里」是成立的。Java 若在装配期把写根拍死，
 * 就会出现「文件工具能写、命令写不了」的错位。
 *
 * <p><b>这里能证明什么、不能证明什么</b>：用假的 {@link PathScopeFactory} 记录
 * 每次运行取到的写根，证明的是「动态取根这条链路」；
 * 真正的内核级强制（Seatbelt/Landlock）要靠平台后端的用例与真机验收。
 */
class DynamicPathScopeTest {

    /** 记录型假作用域：只记写根，不真的强制。 */
    private record RecordingScope(List<Path> writeRoots) implements PathScope {

        @Override
        public PathScope narrowTo(List<Path> roots) {
            return new RecordingScope(List.copyOf(roots));
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
            return "路径强制=测试记录（不真的强制）";
        }
    }

    @Test
    void aNewlyGrantedRootReachesTheNextShellCall() throws Exception {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "dyn-scope-" + System.nanoTime()));
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Path extra = Files.createDirectories(base.resolve("extra"));

        RootSet roots = RootSet.of(workspace);
        SandboxedFs fs = new SandboxedFs(roots);
        List<List<Path>> seen = new ArrayList<>();

        LocalRunner runner = LocalRunner.builder(workspace)
                .pathScopeFactory(writeRoots -> {
                    seen.add(List.copyOf(writeRoots));
                    return new RecordingScope(writeRoots);
                })
                .pathScopeSupplier(() -> roots.roots().stream()
                        .filter(Root::writable).map(Root::path).toList())
                .build();

        RunResult first = runner.run(RunRequest.of("echo hi"));
        assertEquals(0, first.exitCode(), "第一次命令正常退出");
        assertEquals(1, seen.size(), "第一次取一次写根");
        assertEquals(List.of(workspace.toRealPath()), seen.get(0), "第一次只有锚点");

        GrantAccessTool grant = new GrantAccessTool(roots, fs, (path, reason) -> true);
        String granted = (String) grant.execute(
                "{\"mount\":\"extra\",\"dir\":\"" + extra.toRealPath() + "\",\"reason\":\"要写日志\"}");
        assertTrue(granted.contains("已开放"), granted);

        RunResult second = runner.run(RunRequest.of("echo again"));
        assertEquals(0, second.exitCode(), "第二次命令正常退出");
        assertEquals(2, seen.size(), "第二次再取一次写根");
        assertEquals(2, seen.get(1).size(), "新加的根进了下一次命令的写根");
        assertTrue(seen.get(1).contains(extra.toRealPath()), "写根里是新授权的目录: " + seen.get(1));
    }

    @Test
    void theWriteRootsAreFetchedPerRunNotPerAssembly() throws Exception {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "dyn-scope2-" + System.nanoTime()));
        Path workspace = Files.createDirectories(base.resolve("ws"));
        RootSet roots = RootSet.of(workspace);
        List<List<Path>> seen = new ArrayList<>();

        LocalRunner runner = LocalRunner.builder(workspace)
                .pathScopeFactory(writeRoots -> {
                    seen.add(List.copyOf(writeRoots));
                    return new RecordingScope(writeRoots);
                })
                .pathScopeSupplier(() -> roots.roots().stream()
                        .filter(Root::writable).map(Root::path).toList())
                .build();

        runner.run(RunRequest.of("echo 1"));
        runner.run(RunRequest.of("echo 2"));
        runner.run(RunRequest.of("echo 3"));

        assertEquals(3, seen.size(), "跑几次就取几次（不是装配一次就拍死）");
    }

    /**
     * 只读根**不进** shell 的写范围；只写根进。
     *
     * <p>只读根能读不能写（文件工具明文拒绝写它）。若把它也送进写根，
     * shell 就能写它——两个边界对不上，「只读」这个承诺就废了。
     */
    @Test
    void readOnlyRootsDoNotReachTheShellWriteScope() throws Exception {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "dyn-scope3-" + System.nanoTime()));
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Path readOnly = Files.createDirectories(base.resolve("ro"));
        Path writeOnly = Files.createDirectories(base.resolve("wo"));

        RootSet roots = RootSet.of(workspace);
        roots.addRead("ro", readOnly);
        roots.addWrite("wo", writeOnly);
        List<List<Path>> seen = new ArrayList<>();

        LocalRunner runner = LocalRunner.builder(workspace)
                .pathScopeFactory(writeRoots -> {
                    seen.add(List.copyOf(writeRoots));
                    return new RecordingScope(writeRoots);
                })
                .pathScopeSupplier(() -> roots.roots().stream()
                        .filter(Root::writable).map(Root::path).toList())
                .build();

        runner.run(RunRequest.of("echo hi"));

        assertEquals(1, seen.size(), "取一次写根");
        List<Path> writeRoots = seen.get(0);
        assertTrue(writeRoots.contains(workspace.toRealPath()), "锚点根可写，在写范围里");
        assertTrue(writeRoots.contains(writeOnly.toRealPath()), "只写根可写，在写范围里");
        assertFalse(writeRoots.contains(readOnly.toRealPath()),
                "只读根不可写，不能在写范围里: " + writeRoots);
    }
}
