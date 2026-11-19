package net.hegq.mos4j.model.wire;

/**
 * SSE 流里的一个事件（按规范分帧后的产物）。
 *
 * <p><b>与行级视图的差异</b>：{@link net.hegq.mos4j.model.transport.SseLineReader} 给的是行，
 * 「这一行是不是 data:」由调用方判断；本类给的是帧——{@code event:}/{@code id:}/{@code data:}
 * 字段已归位，多行 data 已用 {@code \n} 连接，注释行已剔除。网关/透传场景要原样转发厂商事件，
 * 必须拿到完整的帧而不是只有 data。</p>
 *
 * @param event {@code event:} 字段的值（可空——很多事件只有 data 行）
 * @param id    {@code id:} 字段的值（可空；跨事件保留最后一次出现的值）
 * @param data  data 缓冲（派发的必要条件：为空的帧不派发；多行 data 以 {@code \n} 连接）
 */
public record SseEvent(String event, String id, String data) {
}
