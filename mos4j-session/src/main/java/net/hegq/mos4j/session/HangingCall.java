package net.hegq.mos4j.session;

/**
 * 一次「悬空」的调用：日志里有 {@code tool/call}，没有配对的 {@code tool/result}。
 *
 * <p>进程在执行期间被硬杀（或宿主没写回执）时的残留。
 *
 * <p><b>与投影里的「合成失败回执」是两回事</b>：投影为了保证
 * {@code assistant.toolCalls} 与 {@code tool/result} 成对，会给孤儿调用补一条
 * <b>合成</b>结果——那是<b>派生</b>（给人看的）；本记录报告的是<b>日志事实</b>
 * （宿主据此决定要不要续跑）。
 *
 * @param turn     所属轮次（恢复时的匹配键之一）
 * @param callId   调用标识（模型给的；与 {@code tool/result} 配对用）
 * @param toolName 工具名
 * @param args     调用参数（JSON 原文）
 * @param seq      {@code tool/call} 事件的序号
 */
public record HangingCall(int turn, String callId, String toolName, String args, long seq) {
}
