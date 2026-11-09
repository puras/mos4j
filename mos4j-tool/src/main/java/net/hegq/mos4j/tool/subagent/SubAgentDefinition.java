package net.hegq.mos4j.tool.subagent;

import java.util.List;

/**
 * 一个子代理的声明（frontmatter + 正文位置）。
 *
 * <p><b>正文不驻留</b>：{@code instructionsPath} 指向 {@code AGENT.md}，
 * 指令正文在<b>调用时现读</b>（改了立刻生效，不必重扫）。
 *
 * @param name             子代理名（kebab-case，必需；缺失时回退到目录名）
 * @param description      一句话说明（必需）：模型据此判断该不该派这个子代理
 * @param model            模型覆盖（可选）：声明了但宿主目录里没有 → 装配期报错
 * @param tools            声明白名单（可选）：实际工具面 = 白名单 ∩ 父可见工具集
 * @param dir              子代理目录（{@code AGENT.md} 所在目录）
 * @param instructionsPath 定义文件路径（{@code AGENT.md}）
 * @param hasInstructions  指令正文非空（装配期诊断用）
 */
public record SubAgentDefinition(
        String name,
        String description,
        String model,
        List<String> tools,
        String dir,
        String instructionsPath,
        boolean hasInstructions) {

    /** 构造器（列表防御性复制 + null 归一）。 */
    public SubAgentDefinition {
        tools = tools == null ? List.of() : List.copyOf(tools);
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        model = model == null ? "" : model;
        dir = dir == null ? "" : dir;
        instructionsPath = instructionsPath == null ? "" : instructionsPath;
    }

    /**
     * 校验声明（fail-closed：不合法就<b>不装</b>，而不是「装上再让模型撞墙」）。
     *
     * @return 问题说明；合法时返回 {@code null}
     */
    public String validate() {
        if (name.isEmpty()) {
            return "name 缺失";
        }
        if (name.codePointCount(0, name.length()) > SubAgentCatalog.MAX_NAME_LENGTH) {
            return "name 过长（上限 " + SubAgentCatalog.MAX_NAME_LENGTH + " 字符）";
        }
        if (!isKebabCase(name)) {
            return "name \"" + name + "\" 不是合法 kebab-case（需匹配 ^[a-z0-9]+(-[a-z0-9]+)*$）";
        }
        if (description.isEmpty()) {
            return "description 缺失";
        }
        if (description.codePointCount(0, description.length()) > SubAgentCatalog.MAX_DESCRIPTION_LENGTH) {
            return "description 过长（上限 " + SubAgentCatalog.MAX_DESCRIPTION_LENGTH + " 字符）";
        }
        return null;
    }

    /**
     * kebab-case 判定（不首尾连字符、不连续连字符）。
     *
     * <p><b>为什么不用正则</b>：SDK 模块禁用 {@code java.util.regex}
     * （回溯引擎处理不可信输入有风险）；这个判定只有一条规则，手写更直白。
     */
    static boolean isKebabCase(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        boolean previousDash = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '-') {
                if (previousDash || i == 0 || i == value.length() - 1) {
                    return false;
                }
                previousDash = true;
                continue;
            }
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alnum) {
                return false;
            }
            previousDash = false;
        }
        return true;
    }
}
