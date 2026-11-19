package net.hegq.mos4j.model.wire;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 事件级分帧（wire 层）的单元测试。
 *
 * <p><b>分帧口径</b>：空行派发、注释剔除、多行 data 连接、event/id 归位、BOM 剔除、
 * EOF 宽松派发（有意偏离规范的点，必须有测试钉住）。</p>
 */
class SseEventReaderTest {

    private static List<SseEvent> read(String text) throws IOException {
        List<SseEvent> events = new ArrayList<>();
        SseEventReader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                event -> {
                    events.add(event);
                    return true;
                });
        return events;
    }

    @Test
    void eventFieldAndMultiLineData_reassembled() throws IOException {
        List<SseEvent> events = read("event: foo\ndata: a\ndata: b\n\n");
        assertEquals(1, events.size());
        assertEquals("foo", events.get(0).event());
        assertNull(events.get(0).id());
        assertEquals("a\nb", events.get(0).data(), "多行 data 用 \\n 连接");
    }

    @Test
    void idPersistsAcrossEvents() throws IOException {
        List<SseEvent> events = read("id: 1\ndata: a\n\ndata: b\n\n");
        assertEquals(2, events.size());
        assertNull(events.get(0).event());
        assertEquals("1", events.get(0).id());
        assertEquals("a", events.get(0).data());
        assertEquals("1", events.get(1).id(), "id 跨事件保留（lastEventId 语义）");
        assertEquals("b", events.get(1).data());
    }

    @Test
    void commentsAndEmptyEvents_dispatchNothing() throws IOException {
        assertTrue(read(": keep-alive\n\n").isEmpty(), "注释 + 空行 = 无事件");
        assertTrue(read("event: x\n\n").isEmpty(), "只有 event: 没有 data → 不派发");
    }

    @Test
    void colonInDataValue_preserved() throws IOException {
        List<SseEvent> events = read("data: a:b\n\n");
        assertEquals(1, events.size());
        assertEquals("a:b", events.get(0).data(), "只有第一个冒号是字段分隔符");
    }

    @Test
    void oneLeadingSpaceStripped() throws IOException {
        List<SseEvent> events = read("data:  x\n\n");
        assertEquals(" x", events.get(0).data(), "只去掉一个前导空格（规范口径）");
    }

    @Test
    void bomStrippedAndUnknownFieldsIgnored() throws IOException {
        List<SseEvent> events = read("\uFEFFretry: 100\nfoo: bar\ndata: x\n\n");
        assertEquals(1, events.size());
        assertEquals("x", events.get(0).data(), "BOM 剔除；retry:/未知字段忽略");
    }

    @Test
    void crlfHandled() throws IOException {
        List<SseEvent> events = read("data: a\r\n\r\n");
        assertEquals(1, events.size());
        assertEquals("a", events.get(0).data(), "CRLF 行尾兼容");
    }

    @Test
    void eofDispatchesIncompleteEvent() throws IOException {
        List<SseEvent> events = read("data: tail");
        assertEquals(1, events.size(), "EOF 时未派发事件宽松派发（有意偏离规范）");
        assertEquals("tail", events.get(0).data());
    }

    @Test
    void handlerStop_stopsReading() throws IOException {
        List<SseEvent> events = new ArrayList<>();
        SseEventReader.read(new ByteArrayInputStream(
                "data: 1\n\ndata: 2\n\ndata: 3\n\n".getBytes(StandardCharsets.UTF_8)), event -> {
            events.add(event);
            return events.size() < 2;
        });
        assertEquals(2, events.size(), "handler 返回 false 即停止");
    }

    @Test
    void truncatedStream_neverThrows() throws IOException {
        String sample = "event: a\ndata: {\"x\":1}\n\n: c\ndata: two\n\ndata: three";
        byte[] raw = sample.getBytes(StandardCharsets.UTF_8);
        Random random = new Random(20260206);
        for (int i = 0; i < 200; i++) {
            int cut = random.nextInt(raw.length + 1);
            byte[] slice = new byte[cut];
            System.arraycopy(raw, 0, slice, 0, cut);
            List<SseEvent> events = new ArrayList<>();
            // 任何截断点都不抛异常（读到哪里算哪里）
            SseEventReader.read(new ByteArrayInputStream(slice), event -> {
                events.add(event);
                return true;
            });
            // 派发数不得超过切片内的派发点数（空行数 + 可能的 EOF 尾事件）
            int blanks = 0;
            for (byte b : slice) {
                if (b == '\n') {
                    blanks++;
                }
            }
            assertTrue(events.size() <= blanks + 1,
                    "截断到 " + cut + " 字节后事件数越界: " + events.size());
        }
    }

    @Test
    void randomGarbage_neverThrows() throws IOException {
        Random random = new Random(20260206);
        for (int i = 0; i < 200; i++) {
            byte[] garbage = new byte[random.nextInt(256)];
            random.nextBytes(garbage);
            List<SseEvent> events = new ArrayList<>();
            // 任意字节序列（含残缺 UTF-8、控制字符）都不抛异常
            SseEventReader.read(new ByteArrayInputStream(garbage), event -> {
                events.add(event);
                return true;
            });
            assertTrue(events.size() <= 64);
        }
    }
}
