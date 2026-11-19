package net.hegq.mos4j.model.wire;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE 事件级分帧读取器（wire 层：协议无关的流机械）。
 *
 * <p><b>定位</b>：mos4j 自己的投影只需要 data 行（行级视图见
 * {@link net.hegq.mos4j.model.transport.SseLineReader}）；网关/透传场景要
 * 「厂商协议原样转发」——{@code event:} 字段、{@code id:} 字段、多行 data 都不能丢，
 * 分帧必须是完整的 SSE 语义而不是「只挑 data 行」。本类是这两个场景共用的分帧底座。</p>
 *
 * <p><b>分帧规则</b>（WHATWG HTML 标准的 {@code text/event-stream} 部分）：
 * 事件由空行分隔；{@code event:}/{@code id:}/{@code data:} 字段积累进当前事件；
 * {@code :} 开头是注释（不改变事件缓冲，keep-alive 用）；多行 data 用 {@code \n} 连接；
 * 字段值去掉一个前导空格；行尾 {@code \r}（CRLF / bare CR）由行读取器处理；
 * 流开头的 BOM 剔除；<b>只有 data 缓冲非空的事件才派发</b>（注释 + 空行 = 无事发生）。</p>
 *
 * <p><b>与规范唯一的有意偏差</b>：规范要求 EOF 时丢弃未派发的事件，这里<b>宽松派发</b>——
 * 与既有行级实现行为对齐（每行即时处理），上游少发结束空行时最后一条消息不丢。
 * 「流算不算完整」由上层终止规则判定（{@link StreamTermination}，机制 M5），不在分帧层做。</p>
 *
 * <p><b>取消</b>：本类不处理取消令牌——handler 里自行检查（与 {@code SseLineReader} 同口径）。</p>
 */
public final class SseEventReader {

    private SseEventReader() {
    }

    /** 事件处理器；返回 {@code false} 表示停止读取（调用方关闭流）。 */
    @FunctionalInterface
    public interface EventHandler {
        /**
         * 处理一个事件。
         *
         * @return {@code false} 停止读取
         */
        boolean handle(SseEvent event) throws IOException;
    }

    /**
     * 逐事件读取输入流。
     *
     * @param in      输入流（调用方负责关闭）
     * @param handler 事件处理器
     */
    public static void read(InputStream in, EventHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String eventType = null;
            String id = null;
            StringBuilder data = null;
            boolean firstLine = true;
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    line = stripBom(line);
                    firstLine = false;
                }
                if (line.isEmpty()) {
                    // 空行 = 派发点；没有 data 缓冲就不派发（规范：空 data 的事件不发出去）
                    if (data != null) {
                        boolean cont = handler.handle(new SseEvent(eventType, id, data.toString()));
                        if (!cont) {
                            return;
                        }
                        eventType = null;
                        data = null;
                        // id 保留：lastEventId 跨事件持续（规范）
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;   // 注释行：忽略（keep-alive）
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : stripOneLeadingSpace(line.substring(colon + 1));
                switch (field) {
                    case "event" -> eventType = value;
                    case "id" -> id = value;
                    case "data" -> {
                        if (data == null) {
                            data = new StringBuilder();
                        } else {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // retry: 与未知字段：不处理（重连策略不属于分帧层）
                    }
                }
            }
            // EOF：宽松派发未完成事件（有意偏离规范，见类注释）
            if (data != null) {
                handler.handle(new SseEvent(eventType, id, data.toString()));
            }
        }
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }

    private static String stripOneLeadingSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}
