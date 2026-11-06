package net.hegq.mos4j.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fuzz（T6.7）：JSONL 尾部截断的随机化回归。
 *
 * <p><b>策略</b>：先生成完整合法日志，再随机截断到任意字节位置（含行中间、行尾、
 * 半个多字节字符），恢复要么完整要么丢弃尾半行——<b>绝不抛异常</b>
 * （「尾部半行可丢」是契约，不是巧合）。</p>
 *
 * <p>文件测试用 {@code target/test-tmp}（沙箱禁止写系统临时目录）。</p>
 */
class JsonlTruncationFuzzTest {

    private static Path tempDir(String name) throws IOException {
        Path dir = Path.of("target", "test-tmp", name);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void truncatedJsonl_neverThrows_recoversPrefix() throws IOException {
        Path dir = tempDir("fuzz-jsonl");
        // 每次运行独立目录：JSONL 是 append 模式，复用目录会跨运行累积事件
        JsonlPersistence persistence = new JsonlPersistence(dir.resolve("full-" + System.nanoTime()));
        Session session = new Session("fuzz", persistence);
        session.append(Event.builder(EventKind.TURN_START).turn(0).build());
        session.append(Event.builder(EventKind.USER).turn(0)
                .text("你好 world —— emoji \ud83d\ude00 与长文本".repeat(3)).build());
        session.append(Event.builder(EventKind.TURN_END).turn(0).reason("completed").build());
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.USER).turn(1).text("第二条").build());
        byte[] full = Files.readAllBytes(persistence.path("fuzz"));
        // 精确计算每行的字节终点：截断点越过第 k 行行末 → 前 k-1 条事件行必然完整可恢复
        // （第 0 行是 Header；最后一段半行丢弃）
        List<Integer> lineEnds = new java.util.ArrayList<>();
        for (int i = 0; i < full.length; i++) {
            if (full[i] == '\n') {
                lineEnds.add(i + 1);
            }
        }

        Random random = new Random(20260206);
        for (int i = 0; i < 200; i++) {
            int cut = random.nextInt(full.length + 1);
            String id = "corrupt-" + i;
            JsonlPersistence corruptPersistence = new JsonlPersistence(dir.resolve("case-" + i));
            Files.write(corruptPersistence.path(id), slice(full, cut),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            List<Event> events = corruptPersistence.load(id);
            // 加载绝不抛异常；恢复数 == 完整事件行数（半行丢弃，一行不多不少）
            int completeEventLines = 0;
            for (int line = 1; line < lineEnds.size(); line++) {
                if (cut >= lineEnds.get(line)) {
                    completeEventLines++;
                }
            }
            assertEquals(completeEventLines, events.size(),
                    "截断到 " + cut + " 字节后恢复的事件数应等于完整事件行数");
            if (!events.isEmpty()) {
                assertEquals(EventKind.TURN_START, events.get(0).kind(),
                        "恢复的事件序与行序一致");
            }
        }
    }

    /** 按字节截断（可能在多字节字符中间——真实崩溃现场）。 */
    private static byte[] slice(byte[] data, int end) {
        byte[] out = new byte[end];
        System.arraycopy(data, 0, out, 0, end);
        return out;
    }
}
