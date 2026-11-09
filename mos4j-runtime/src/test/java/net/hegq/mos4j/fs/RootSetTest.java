package net.hegq.mos4j.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活根集合测试（T3.4）。
 *
 * <p>锚点稳定 / 加根立即生效 / 变更留痕 / 并发安全。
 */
class RootSetTest {

    private Path base;
    private Path anchor;
    private Path extra;

    @BeforeEach
    void setUp() throws IOException {
        base = Path.of("target", "test-tmp", "roots-" + System.nanoTime());
        anchor = Files.createDirectories(base.resolve("anchor"));
        extra = Files.createDirectories(base.resolve("extra"));
    }

    @Test
    void anchorIsTheRealPathAndStaysPut() throws IOException {
        RootSet roots = RootSet.of(anchor);
        Path before = roots.anchor();

        roots.add("data", extra);

        assertEquals(before, roots.anchor());
        assertEquals(anchor.toRealPath(), roots.anchor());
        assertNotEquals(extra.toRealPath(), roots.anchor());
    }

    @Test
    void addedRootTakesEffectOnTheNextCall() throws IOException {
        RootSet roots = RootSet.of(anchor);
        SandboxedFs fs = new SandboxedFs(roots);
        Files.writeString(extra.resolve("data.txt"), "d", java.nio.charset.StandardCharsets.UTF_8);

        // 加根之前：路径落在锚点内（不存在），但绝不会落到 extra 里
        Path before = fs.resolve("data/data.txt");
        assertFalse(before.startsWith(extra.toRealPath()), before.toString());

        roots.add("data", extra);

        // 加根之后，下一次调用立即生效
        Path resolved = fs.resolve("data/data.txt");
        assertEquals(extra.resolve("data.txt").toRealPath(), resolved);
    }

    @Test
    void mountNameShadowsTheSameNamedDirectoryInsideAnchor() throws IOException {
        // 挂载名优先于锚点同名目录（否则同一个路径前后指向两个地方）
        Path shadow = Files.createDirectories(anchor.resolve("data"));
        Files.writeString(shadow.resolve("x.txt"), "锚点里的", java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(extra.resolve("x.txt"), "挂载里的", java.nio.charset.StandardCharsets.UTF_8);

        RootSet roots = RootSet.of(anchor);
        roots.add("data", extra);
        SandboxedFs fs = new SandboxedFs(roots);

        Path resolved = fs.resolve("data/x.txt");

        assertEquals(extra.resolve("x.txt").toRealPath(), resolved);
        assertEquals("挂载里的", Files.readString(resolved, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void changesAreTraced() throws IOException {
        RootSet roots = RootSet.of(anchor);

        roots.add("data", extra);
        roots.add("more", base);

        List<String> changes = roots.changes();
        assertEquals(3, changes.size());
        assertTrue(changes.get(0).startsWith("init anchor="), changes.toString());
        assertTrue(changes.get(1).startsWith("add data "), changes.toString());
        assertTrue(changes.get(2).startsWith("add more "), changes.toString());
    }

    @Test
    void duplicateMountIdIsRejected() throws IOException {
        RootSet roots = RootSet.of(anchor);
        roots.add("data", extra);

        assertThrows(IllegalStateException.class, () -> roots.add("data", base));
    }

    @Test
    void missingRootDirectoryIsRejected() throws IOException {
        RootSet roots = RootSet.of(anchor);

        assertThrows(IOException.class, () -> roots.add("nope", base.resolve("nope")));
    }

    @Test
    void concurrentAddsAreSafe() throws Exception {
        RootSet roots = RootSet.of(anchor);
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        roots.add("root-" + index, anchor);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failures.get());
        assertEquals(workers + 1, roots.roots().size());
        assertEquals(workers + 1, roots.changes().size());
    }

    @Test
    void aReadOnlyRootCanBeReadButNotWritten() throws IOException {
        RootSet roots = RootSet.of(anchor);
        roots.addRead("ro", extra);
        Files.writeString(extra.resolve("data.txt"), "d", java.nio.charset.StandardCharsets.UTF_8);
        SandboxedFs fs = new SandboxedFs(roots);

        assertEquals(extra.toRealPath().resolve("data.txt"), fs.resolveExisting("ro/data.txt"),
                "只读根能读");

        PathDeniedError denied = assertThrows(PathDeniedError.class,
                () -> fs.resolveForWrite("ro/data.txt"));
        assertTrue(denied.getMessage().contains("ro"), denied.getMessage());
    }

    @Test
    void aWriteOnlyRootCanBeWrittenButNotRead() throws IOException {
        Path output = Files.createDirectories(base.resolve("output"));
        RootSet roots = RootSet.of(anchor);
        roots.addWrite("wo", output);
        SandboxedFs fs = new SandboxedFs(roots);

        assertEquals(output.toRealPath().resolve("out.txt"), fs.resolveForWrite("wo/out.txt"),
                "只写根能写");

        assertThrows(PathDeniedError.class, () -> fs.resolveExisting("wo/out.txt"));
    }

    @Test
    void removedRootStopsResolving() throws IOException {
        RootSet roots = RootSet.of(anchor);
        roots.add("data", extra);
        Files.writeString(extra.resolve("data.txt"), "d", java.nio.charset.StandardCharsets.UTF_8);

        roots.remove("data");

        Path after = new SandboxedFs(roots).resolve("data/data.txt");
        assertFalse(after.startsWith(extra.toRealPath()), "移除后不再落到 extra 里：" + after);
        assertTrue(roots.roots().stream().noneMatch(r -> r.id().equals("data")));
        assertTrue(roots.changes().stream().anyMatch(c -> c.startsWith("remove data")), roots.changes().toString());
    }

    @Test
    void theAnchorRootCannotBeRemoved() throws IOException {
        RootSet roots = RootSet.of(anchor);

        assertThrows(IllegalStateException.class, () -> roots.remove(RootSet.ANCHOR_ID));
        assertEquals(1, roots.roots().size(), "锚点还在");
    }

    @Test
    void removingAnUnknownRootFailsInsteadOfPretending() throws IOException {
        RootSet roots = RootSet.of(anchor);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> roots.remove("nope"));
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
    }

    @Test
    void rootsSnapshotIsImmutable() throws IOException {
        RootSet roots = RootSet.of(anchor);
        roots.add("data", extra);

        List<Root> snapshot = roots.roots();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Root("x", extra, java.time.Instant.now())));
    }
}
