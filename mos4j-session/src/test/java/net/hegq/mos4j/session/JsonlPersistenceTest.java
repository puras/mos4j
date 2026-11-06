package net.hegq.mos4j.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSONL 持久化测试：往返、尾部半行、中段损坏、孤儿 turn 修复、HTML 不转义。
 */
class JsonlPersistenceTest {

    /**
     * 测试用临时目录（放在模块 target 下——沙箱里系统临时目录不可写）。
     */
    private static Path tempDir() throws IOException {
        Files.createDirectories(Path.of("target", "test-tmp"));
        return Files.createTempDirectory(Path.of("target", "test-tmp"), "session-");
    }

    @Test
    void roundTripKeepsEvents() throws IOException {
        Path dir = tempDir();
        JsonlPersistence persistence = new JsonlPersistence(dir);
        Session session = new Session("s1", persistence);
        session.append(Event.builder(EventKind.TURN_START).turn(1).build());
        session.append(Event.builder(EventKind.USER).turn(1).text("问题").build());
        session.append(Event.builder(EventKind.ASSISTANT).turn(1).text("回答").build());
        session.append(Event.builder(EventKind.TURN_END).turn(1).reason("completed").build());

        Session restored = JsonlPersistence.restore("s1", persistence);

        assertEquals(4, restored.events().size());
        assertEquals("问题", restored.messages().get(0).content());
        assertEquals("回答", restored.messages().get(1).content());
        assertEquals(2, restored.nextTurn());
        assertTrue(Files.readString(persistence.path("s1")).startsWith("{\"id\":\"s1\""),
                "首行必须是 Header");
    }

    @Test
    void tailHalfLineIsDiscarded() throws IOException {
        Path dir = tempDir();
        JsonlPersistence persistence = new JsonlPersistence(dir);
        persistence.saveHeader("s1", Header.of("s1"));
        Files.writeString(persistence.path("s1"),
                "{\"seq\":1,\"kind\":\"user\",\"text\":\"完整\"}\n{\"seq\":2,\"kind\":\"assi",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<Event> events = persistence.load("s1");

        assertEquals(1, events.size(), "尾部半行必须丢弃（崩溃残留）");
        assertEquals("完整", events.get(0).text());
    }

    @Test
    void corruptedMiddleLineFails() throws IOException {
        Path dir = tempDir();
        JsonlPersistence persistence = new JsonlPersistence(dir);
        persistence.saveHeader("s1", Header.of("s1"));
        Files.writeString(persistence.path("s1"),
                "{\"seq\":1,\"kind\":\"user\",\"text\":\"好\"}\n{坏掉的行\n{\"seq\":3}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IOException error = assertThrows(IOException.class, () -> persistence.load("s1"));

        assertTrue(error.getMessage().contains("损坏"), error.getMessage());
    }

    @Test
    void orphanTurnIsRepairedInMemoryOnly() throws IOException {
        Path dir = tempDir();
        JsonlPersistence persistence = new JsonlPersistence(dir);
        persistence.saveHeader("s1", Header.of("s1"));
        Files.writeString(persistence.path("s1"),
                "{\"seq\":1,\"kind\":\"turn/start\",\"turn\":1}\n{\"seq\":2,\"kind\":\"user\",\"turn\":1,\"text\":\"x\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Session restored = JsonlPersistence.restore("s1", persistence);

        Event last = restored.events().get(restored.events().size() - 1);
        assertEquals(EventKind.TURN_END, last.kind());
        assertEquals("interrupted", last.reason());
        assertEquals(2, restored.nextTurn(), "孤儿 turn 补完后轮次恢复正常（L2-15）");

        String disk = Files.readString(persistence.path("s1"));
        assertFalse(disk.contains("interrupted"), "合成事件只补在内存里，不回写日志");
    }

    @Test
    void htmlIsNotEscaped() throws IOException {
        Path dir = tempDir();
        JsonlPersistence persistence = new JsonlPersistence(dir);
        Session session = new Session("s1", persistence);
        session.append(Event.builder(EventKind.USER).text("cd lab && go test > out").build());

        String disk = Files.readString(persistence.path("s1"));

        assertTrue(disk.contains("&&"), "HTML 字符不应被转义（日志是给人读的）：" + disk);
        assertFalse(disk.contains("\\u0026"), disk);
    }
}
