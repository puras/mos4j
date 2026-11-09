package net.hegq.mos4j.tool.subagent;

import java.util.List;

/**
 * 一次子代理调用的输入。
 *
 * <p>执行体拿它建子智能体：指令正文 + 白名单 + 模型覆盖 + 这次要干的活。
 *
 * @param name         子代理名（工具名 = {@code subagent__<name>}）
 * @param task         父模型交代的任务
 * @param context      附加上下文（可选；父模型给的额外材料）
 * @param instructions 子代理的指令正文（调用时现读）
 * @param tools        声明白名单（实际工具面由执行体收窄：白名单 ∩ 父可见集）
 * @param model        模型覆盖（空 = 用父模型）
 */
public record SubAgentSpec(
        String name,
        String task,
        String context,
        String instructions,
        List<String> tools,
        String model) {

    /** 构造器（列表防御性复制 + null 归一）。 */
    public SubAgentSpec {
        tools = tools == null ? List.of() : List.copyOf(tools);
        task = task == null ? "" : task;
        context = context == null ? "" : context;
        instructions = instructions == null ? "" : instructions;
        model = model == null ? "" : model;
    }

    /** 是否声明了模型覆盖。 */
    public boolean hasModel() {
        return !model.isBlank();
    }

    /** 是否声明了工具白名单（空 = 不收窄，继承父的可见集）。 */
    public boolean hasToolWhitelist() {
        return !tools.isEmpty();
    }
}
