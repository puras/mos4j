package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.core.ToolContext;

/**
 * 子代理的<b>执行体</b>（宿主或框架注入）。
 *
 * <p>框架不自己造子智能体：本接口只定义接缝——独立会话、白名单子注册表、
 * 模型覆盖、子智能体内部的工具调用怎么走执行通道，由实现方决定。
 *
 * <p><b>三条契约</b>：
 * <ol>
 *   <li><b>同步</b>：跑完才返回（框架没有运行期消息回注通道，异步要先造一条通道）；</li>
 *   <li><b>失败不是错误</b>：子代理跑挂了要<b>返回</b>一个 {@code status=error} 的回执
 *       （父轮次不因此失败）；抛异常只表示「连子代理都没跑起来」；</li>
 *   <li><b>取消透传</b>：上下文里的取消令牌取消 → 子代理跟着取消。</li>
 * </ol>
 */
@FunctionalInterface
public interface SubAgentRunner {

    /**
     * 跑一次子代理，返回固定结构的回执。
     *
     * @param spec    调用输入（任务 / 指令正文 / 白名单 / 模型覆盖）
     * @param context 执行上下文（轮次 / 会话 id / 取消令牌）
     * @return 回执（子代理跑挂了也要返回 {@code status=error} 的回执，不是抛异常）
     * @throws Exception 连子代理都没跑起来（框架层错误）
     */
    SubAgentResult run(SubAgentSpec spec, ToolContext context) throws Exception;
}
