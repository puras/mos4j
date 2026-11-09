package net.hegq.mos4j.agent;

import java.util.List;

/**
 * 装配快照：<b>装了什么 + 没装什么</b>。
 *
 * <p><b>为什么必须有「没装什么」</b>：MoS 纪律「假装有边界比没有边界更危险」。
 * 只列出已装部件，部署方会默认「其余能力也有」——比如以为进程被沙箱隔离、
 * 以为取消会自动传播。<b>降级必须写进快照</b>，否则边界只存在于作者心里。</p>
 *
 * @param framework    框架名
 * @param version      框架版本
 * @param javaVersion  运行时 JDK 版本
 * @param driver       驱动类型
 * @param planner      决策器类型
 * @param modelProvider 模型提供方（可空）
 * @param model        模型名（可空）
 * @param capabilities 模型能力位
 * @param tools        已注册工具名
 * @param features     已启用的扩展点
 * @param caveats      <b>诚实的边界与降级声明</b>
 */
public record Describe(
        String framework,
        String version,
        String javaVersion,
        String driver,
        String planner,
        String modelProvider,
        String model,
        List<String> capabilities,
        List<String> tools,
        List<String> features,
        List<String> caveats) {
}
