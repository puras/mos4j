package net.hegq.mos4j.session;

import java.io.IOException;
import java.util.List;

/**
 * 会话日志的持久化后端（backend 可换）。
 *
 * <p>数据库（SQLite / Postgres）等重后端由业务层实现并注入，SDK 不内置。</p>
 */
public interface Persistence extends AutoCloseable {

    /** 写入日志首行的会话元信息（文件已存在时不应重复写）。 */
    void saveHeader(String id, Header header) throws IOException;

    /** 追加一条事件并落盘。写失败必须抛错（由 {@link Session#append} 落实 fail-fast）。 */
    void append(String id, Event event) throws IOException;

    /** 读取事件（跳过首行 Header）。 */
    List<Event> load(String id) throws IOException;

    @Override
    void close() throws IOException;
}
