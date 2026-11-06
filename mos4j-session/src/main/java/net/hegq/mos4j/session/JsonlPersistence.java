package net.hegq.mos4j.session;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量默认后端：每会话一个 {@code .jsonl} 文件，首行 Header、逐行事件追加写。
 *
 * <p><b>容忍尾部半行</b>（崩溃时最后一条写了一半）：最后一段没有换行结尾 → 丢弃。
 * <b>中段损坏报错</b>：日志中段损坏属于异常，不允许静默跳过——跳过等于伪造历史。</p>
 *
 * <p><b>HTML 不转义</b>：Jackson 默认就不转义 {@code & < >}（与 Go 侧
 * {@code SetEscapeHTML(false)} 一致）——日志是给人读的，模型输出的 shell 命令里
 * 全是 {@code &&} 和 {@code >}，转义之后全是噪声。</p>
 */
public final class JsonlPersistence implements Persistence {

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param dir 日志目录（不存在时自动创建）
     */
    public JsonlPersistence(Path dir) throws IOException {
        if (dir == null || dir.toString().isBlank()) {
            throw new IOException("session: JSONL 目录为空");
        }
        this.dir = dir;
        Files.createDirectories(dir);
    }

    /** 日志目录。 */
    public Path dir() {
        return dir;
    }

    /** 日志文件路径。 */
    public Path path(String id) {
        return dir.resolve(id + ".jsonl");
    }

    /** 会话日志文件是否存在。 */
    public boolean exists(String id) {
        return Files.exists(path(id));
    }

    @Override
    public void saveHeader(String id, Header header) throws IOException {
        Path path = path(id);
        if (Files.exists(path)) {
            // 已存在时不重复写（保住原有的 createdAt）
            return;
        }
        Files.writeString(path, encodeLine(header), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void append(String id, Event event) throws IOException {
        byte[] line = encodeLine(event).getBytes(StandardCharsets.UTF_8);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path(id),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(java.nio.ByteBuffer.wrap(line));
            channel.force(true);
        }
    }

    @Override
    public List<Event> load(String id) throws IOException {
        Path path = path(id);
        if (!Files.exists(path)) {
            return List.of();
        }
        // 字节读入 + 替换式解码（new String 的 UTF-8 解码对残缺序列用 U+FFFD 替换而非抛错）：
        // 崩溃残留的半行可能停在多字节字符中间——严格解码（Files.readString）会直接抛
        // MalformedInputException，违反「尾部半行可丢」契约（T6.7 fuzz 抓到的真实缺陷）。
        // 残缺字节只可能出现在最后一段（丢弃区），替换不影响任何完整行。
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        List<Event> events = new ArrayList<>();
        // 最后一段一律丢弃：文件以换行结尾时它是空串（不是数据行），
        // 文件不以换行结尾时它是崩溃残留的半行。
        int limit = Math.max(lines.length - 1, 0);
        for (int i = 0; i < limit; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0) {
                // 首行是 Header
                continue;
            }
            try {
                events.add(mapper.readValue(line, Event.class));
            } catch (JacksonException e) {
                throw new IOException("session: 日志第 " + (i + 1) + " 行损坏 " + id + ": " + e.getMessage(), e);
            }
        }
        return events;
    }

    @Override
    public void close() {
        // JSONL 无持有资源
    }

    /**
     * 打开或恢复会话：落盘 {@code dir/id.jsonl}；文件不存在则新建（写首行 header）。
     *
     * <p>中断的 turn 由 {@link #restore} 合成收尾（孤儿 turn → interrupted）。</p>
     */
    public static Session open(Path dir, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IOException("session: 会话 id 不能为空");
        }
        JsonlPersistence persistence = new JsonlPersistence(dir);
        persistence.saveHeader(id, Header.of(id));
        return restore(id, persistence);
    }

    /**
     * 从后端重放事件重建会话，并修复孤儿 turn。
     *
     * <p>日志尾部的 turn/start 没有配对 turn/end 时（崩溃残留），在内存中合成
     * turn/end{reason: interrupted} 平衡日志——只补事件，不回写已有日志。
     * 会话不存在时返回空会话（不报错）。</p>
     */
    public static Session restore(String id, Persistence persistence) throws IOException {
        List<Event> events = persistence.load(id);
        Session session = new Session(id, persistence);
        session.restoreFrom(events);
        if (repairOrphanTurn(events)) {
            // 合成 turn/end{reason: interrupted}：只补事件，不回写已有日志
            session.appendInMemory(Event.builder(EventKind.TURN_END)
                    .reason("interrupted")
                    .build());
        }
        return session;
    }

    /** 检查日志尾部是否有未配对的 turn/start：从尾部向前，先遇 turn/end 即完整。 */
    private static boolean repairOrphanTurn(List<Event> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            EventKind kind = events.get(i).kind();
            if (kind == EventKind.TURN_END) {
                return false;
            }
            if (kind == EventKind.TURN_START) {
                return true;
            }
        }
        return false;
    }

    /** 编成一行 JSON（含换行）。 */
    private String encodeLine(Object value) {
        try {
            return mapper.writeValueAsString(value) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException("session: 编码日志行失败", e);
        }
    }
}
