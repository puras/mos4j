package net.hegq.mos4j.llm;

import net.hegq.mos4j.llm.transport.SseLineReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fuzz（T6.7）：SSE 解析的随机化回归。
 *
 * <p><b>策略</b>：①噪声行注入（event:/注释/空行/非 data 行/残缺 UTF-8）→
 * 解析器只认 data: 行，其余跳过，不炸；②语料随机截断 → 读到哪里算哪里，不炸。</p>
 */
class SseParserFuzzTest {

    private static final String SAMPLE = """
            event: content_block_delta
            data: {"type":"text_delta","text":"Hi"}

            : keep-alive comment
            data: {"type":"message_stop"}

            """;

    @Test
    void noiseLinesAreSkipped() throws IOException {
        List<String> dataLines = new ArrayList<>();
        String noisy = ": ping\nevent: x\ndata: {\"a\":1}\n\n\ngarbage line\r\ndata: {\"b\":2}\r\n";
        SseLineReader.read(new ByteArrayInputStream(noisy.getBytes(StandardCharsets.UTF_8)),
                line -> {
                    if (line.trim().startsWith("data:")) {
                        dataLines.add(line);
                    }
                    return true;
                });

        assertEquals(2, dataLines.size(), "只认 data: 行；event:/注释/空行/garbage 全跳过");
        assertTrue(dataLines.get(0).endsWith("{\"a\":1}"));
        assertTrue(dataLines.get(1).endsWith("{\"b\":2}"), "CRLF 也兼容");
    }

    @Test
    void truncatedStream_neverThrows() throws IOException {
        byte[] raw = SAMPLE.getBytes(StandardCharsets.UTF_8);
        Random random = new Random(20260206);
        for (int i = 0; i < 200; i++) {
            int cut = random.nextInt(raw.length + 1);
            byte[] slice = new byte[cut];
            System.arraycopy(raw, 0, slice, 0, cut);
            List<String> lines = new ArrayList<>();
            // 任何截断点都不抛异常（读到哪里算哪里）
            SseLineReader.read(new ByteArrayInputStream(slice), line -> {
                lines.add(line);
                return true;
            });
            // 行数上限 = 切片内换行数 +（末尾无换行时的最后半行）
            int newlines = 0;
            for (byte b : slice) {
                if (b == '\n') {
                    newlines++;
                }
            }
            int maxLines = newlines + (cut > 0 && slice[cut - 1] != '\n' ? 1 : 0);
            assertTrue(lines.size() <= maxLines,
                    "截断到 " + cut + " 字节后行数越界: " + lines.size() + " > " + maxLines);
        }
    }

    @Test
    void randomGarbage_neverThrows() throws IOException {
        Random random = new Random(20260206);
        for (int i = 0; i < 200; i++) {
            byte[] garbage = new byte[random.nextInt(256)];
            random.nextBytes(garbage);
            List<String> lines = new ArrayList<>();
            // 任意字节序列（含残缺 UTF-8、控制字符）都不抛异常
            SseLineReader.read(new ByteArrayInputStream(garbage), line -> {
                lines.add(line);
                return true;
            });
            assertTrue(lines.size() <= 64);
        }
    }
}
