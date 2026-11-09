package net.hegq.mos4j.tool.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能目录的**并发 reload** 压测（自选加固，不是 Go 对拍缺口）。
 *
 * <p>Go 侧的 reload 用例都不是并发的（逐条核对过 {@code agent/skill/reload_test.go}），
 * 所以这里不是「补对拍」，而是补一条**并发口径**的护栏：
 * 换代是原子的（{@code AtomicReference} 换快照），所以并发 reload + 并发读
 * 不该看到半套状态（重名、半截清单、空快照）。
 */
class SkillCatalogConcurrencyTest {

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Path.of("target", "test-tmp", "skill-concurrency-" + System.nanoTime());
        Path dir = root.resolve("alpha");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: alpha\ndescription: A 技能\n---\n正文\n");
    }

    @Test
    void concurrentReloadsAndReadsNeverSeeAHalfBakedSnapshot() throws Exception {
        SkillCatalog catalog = SkillCatalog.load(SkillCatalog.Options.of(List.of(root.toString())));
        int threads = 4;
        int rounds = 10;

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < rounds; round++) {
                            catalog.reload();
                            List<String> names = catalog.all().stream().map(Skill::name).toList();
                            if (new HashSet<>(names).size() != names.size()) {
                                throw new IllegalStateException("快照里出现重名: " + names);
                            }
                            if (names.isEmpty()) {
                                throw new IllegalStateException("快照空了（换代不该把已有技能抹掉）");
                            }
                            // 读别的口径时也不能炸（渲染与模型可见名单共用同一份快照）。
                            catalog.render();
                            catalog.modelVisibleNames();
                            catalog.lookup("alpha");
                        }
                    } catch (Throwable error) {
                        errors.add(error);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "压测没在时限内结束");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发 reload 不该抛异常: " + errors);
    }
}
