package net.hegq.mos4j.core;

import java.util.List;

/**
 * Describe 快照的补充者：让**工具层**（如 MCP 接入）把装配事实写进快照。
 *
 * <p><b>为什么需要这个接口</b>：工具层不能反过来被装配层依赖——否则底层能力会被上层拖住，
 * 也没法单独复用。但快照必须如实报告「装了什么」——所以方向只能是
 * <b>工具层往装配层的槽里写</b>，而不是装配层去 import 工具层。
 *
 * <p>本接口放在 mos4j-core：它零依赖（只用 List），是工具层与装配层共同认识的最小词汇——
 * 放在 core 才能被两端同时引用而不产生依赖环。
 *
 * <p>补充者写的是**事实**：装了什么、没装什么、降级了什么。不许写宣传词。
 */
@FunctionalInterface
public interface DescribeContributor {

    /**
     * 往快照里补事实。
     *
     * @param features 已启用的扩展点（追加）
     * @param caveats  诚实的边界与降级声明（追加）
     */
    void contribute(List<String> features, List<String> caveats);
}
