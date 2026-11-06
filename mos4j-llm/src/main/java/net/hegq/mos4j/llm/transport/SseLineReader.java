package net.hegq.mos4j.llm.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE 行读取器（把 HTTP 字节流拆成一行一行）。
 *
 * <p><b>为什么自己读而不用 {@code java.net.http} 的行分隔器</b>：
 * SSE 的行可能很长（工具参数分片动辄几 KB），而 JDK 的
 * {@code BodyHandlers.ofLines()} 会把<b>整条流</b>收完才交给调用方——
 * 流式输出会退化成「等全部结束才显示」。必须自己边读边给。</p>
 *
 * <p>行分隔按 {@code \n}，并去掉结尾 {@code \r}（Windows 风格网关）。</p>
 */
public final class SseLineReader {

    private SseLineReader() {
    }

    /** 行处理器；返回 {@code false} 表示停止读取。 */
    @FunctionalInterface
    public interface LineHandler {
        /**
         * 处理一行（不含换行符）。
         *
         * @return {@code false} 停止读取
         */
        boolean handle(String line) throws IOException;
    }

    /**
     * 逐行读取输入流。
     *
     * @param in      输入流（调用方负责关闭）
     * @param handler 行处理器
     */
    public static void read(InputStream in, LineHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handler.handle(line)) {
                    return;
                }
            }
        }
    }
}
