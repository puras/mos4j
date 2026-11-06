package net.hegq.mos4j.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件 jail 测试（T3.1–T3.3；L3-11 … L3-14）。
 *
 * <p>四类逃逸形态：{@code ../} 出根 / 目标是越界链接 / 中间组件是越界链接 / 链接环。
 *
 * <p><b>注意</b>：符号链接的「越界」按内核语义判定——相对目标相对<b>链接所在目录</b>解析。
 * 所以造越界链接必须用<b>绝对</b>目标；用相对目标造出来的链接本来就落在根内，不是逃逸。
 */
class SandboxedFsTest {

    private Path base;
    private Path root;
    private Path rootReal;
    private Path outside;
    private Path outsideReal;
    private SandboxedFs fs;

    @BeforeEach
    void setUp() throws IOException {
        base = Path.of("target", "test-tmp", "fs-" + System.nanoTime()).toAbsolutePath();
        root = Files.createDirectories(base.resolve("root"));
        outside = Files.createDirectories(base.resolve("outside"));
        rootReal = root.toRealPath();
        outsideReal = outside.toRealPath();
        Files.writeString(root.resolve("hello.txt"), "你好", StandardCharsets.UTF_8);
        Files.writeString(outside.resolve("secret.txt"), "机密", StandardCharsets.UTF_8);
        fs = new SandboxedFs(RootSet.of(root));
    }

    // ---------- 正常路径 ----------

    @Test
    void normalRelativePathIsAllowed() throws IOException {
        Path resolved = fs.resolve("hello.txt");

        assertEquals(rootReal.resolve("hello.txt"), resolved);
        assertEquals("你好", Files.readString(resolved, StandardCharsets.UTF_8));
    }

    @Test
    void parentSegmentThatStaysInsideIsAllowed() {
        Path resolved = fs.resolve("sub/../hello.txt");
        assertEquals(rootReal.resolve("hello.txt"), resolved);
    }

    @Test
    void dotSegmentsAreIgnored() {
        assertEquals(rootReal.resolve("hello.txt"), fs.resolve("./hello.txt"));
    }

    // ---------- L3-11 路径越界 ----------

    @Test
    void l3_11_dotDotOutOfRootIsDenied() {
        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("../outside/secret.txt"));

        assertEquals("路径越界", error.reason());
        assertTrue(error.getMessage().contains("该操作允许的根"), error.getMessage());
        assertTrue(error.getMessage().contains(rootReal.toString()), error.getMessage());
    }

    @Test
    void l3_11_deepDotDotOutOfRootIsDenied() {
        assertThrows(PathDeniedError.class, () -> fs.resolve("a/b/../../../../etc/passwd"));
    }

    @Test
    void absolutePathIsDenied() {
        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("/etc/passwd"));

        assertEquals("只接受相对路径", error.reason());
    }

    @Test
    void emptyPathIsDenied() {
        assertThrows(PathDeniedError.class, () -> fs.resolve("  "));
    }

    // ---------- L3-12 目标是越界链接 ----------

    @Test
    void l3_12_symlinkTargetOutsideRootIsDenied() throws IOException {
        Files.createSymbolicLink(root.resolve("link"), outsideReal.resolve("secret.txt"));

        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("link"));

        assertEquals("符号链接指向根外", error.reason());
    }

    @Test
    void l3_12_relativeSymlinkTargetEscapingRootIsDenied() throws IOException {
        // 相对目标相对「链接所在目录」解析：../../outside 一样是逃逸
        Files.createSymbolicLink(root.resolve("link"), Path.of("../../outside/secret.txt"));

        assertThrows(PathDeniedError.class, () -> fs.resolve("link"));
    }

    @Test
    void l3_12_readAlsoGoesThroughRealpath() throws IOException {
        // 读必须走 Realpath：只做 lexical 检查就能把边界外的内容读出来
        Files.createSymbolicLink(root.resolve("link"), outsideReal.resolve("secret.txt"));

        assertThrows(PathDeniedError.class, () -> fs.resolveExisting("link"));
    }

    @Test
    void symlinkInsideRootIsAllowed() throws IOException {
        Files.createSymbolicLink(root.resolve("link"), rootReal.resolve("hello.txt"));

        Path resolved = fs.resolveExisting("link");

        assertEquals("你好", Files.readString(resolved, StandardCharsets.UTF_8));
    }

    // ---------- L3-13 中间组件是越界链接 ----------

    @Test
    void l3_13_symlinkAsMiddleComponentIsDenied() throws IOException {
        Files.createSymbolicLink(root.resolve("escape"), outsideReal);

        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("escape/new.txt"));

        assertEquals("符号链接指向根外", error.reason());
    }

    @Test
    void l3_13_leafMayNotExistButMiddleComponentStillChecked() throws IOException {
        Files.createSymbolicLink(root.resolve("escape"), outsideReal);

        assertThrows(PathDeniedError.class, () -> fs.resolveForWrite("escape/new.txt"));
    }

    @Test
    void newFileUnderNormalDirectoryIsAllowed() throws IOException {
        Files.createDirectories(root.resolve("sub"));

        Path resolved = fs.resolveForWrite("sub/new.txt");

        assertEquals(rootReal.resolve("sub").resolve("new.txt"), resolved);
    }

    // ---------- L3-14 符号链接环 ----------

    @Test
    void l3_14_symlinkLoopIsDeniedInsteadOfHanging() throws IOException {
        Files.createSymbolicLink(root.resolve("a"), rootReal.resolve("b"));
        Files.createSymbolicLink(root.resolve("b"), rootReal.resolve("a"));

        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("a"));

        assertTrue(error.reason().contains("符号链接层数超过上限"), error.reason());
    }

    @Test
    void l3_14_selfLoopIsDenied() throws IOException {
        Files.createSymbolicLink(root.resolve("self"), rootReal.resolve("self"));

        assertThrows(PathDeniedError.class, () -> fs.resolve("self"));
    }

    // ---------- 结构化拒绝 ----------

    @Test
    void denialCarriesTheAllowedRoots() {
        PathDeniedError error = assertThrows(PathDeniedError.class, () -> fs.resolve("../x"));

        assertFalse(error.allowedRoots().isEmpty());
        assertTrue(error.allowedRoots().get(0).endsWith("root"), error.allowedRoots().toString());
        assertEquals("../x", error.requestedPath());
    }

    // ---------- 不存在 ----------

    @Test
    void missingFileIsNotFoundRatherThanDenied() {
        assertThrows(java.nio.file.NoSuchFileException.class, () -> fs.resolveExisting("nope.txt"));
    }
}
