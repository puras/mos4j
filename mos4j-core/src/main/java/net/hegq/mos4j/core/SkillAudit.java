package net.hegq.mos4j.core;

import java.io.IOException;
import java.util.List;

/**
 * 技能审计出口：技能正文进模型上下文之前，必须留下「这轮加载了哪个技能」的痕迹。
 *
 * <p><b>为什么要有这个接口</b>：技能正文是<b>会改变模型行为</b>的输入——它告诉模型
 * 「按这几步做」。没有留痕的加载等于没发生过的加载：事后没人能回答「这轮为什么
 * 突然按发布流程走了」。所以技能激活是<b>先审计、后返回正文</b>：审计写失败就让
 * 这次加载失败（fail-closed），而不是「正文已经进模型了，审计慢慢补」。
 *
 * <p><b>为什么放在 core</b>：技能能力落在 {@code mos4j-tool}，会话事件落在
 * {@code mos4j-session}，两边都要谈「技能审计」——接口放在共享词汇表模块
 * （core）里，避免工具模块反向依赖会话模块（那会把日志层拖进能力层）。
 *
 * <p><b>实现方</b>：会话侧提供 {@code SessionSkillAudit}（写进会话日志）；
 * 宿主也可以自己实现（写到自己的审计系统）。<b>不实现</b> = 不留痕
 * （零开销降级），此时技能正文照常返回。
 */
public interface SkillAudit {

    /**
     * 记一条「技能已激活」。
     *
     * <p>实现方写失败必须抛异常——调用方（技能工具）会据此让这次加载失败：
     * 正文已经进了模型上下文却查不到记录，比拒绝加载更糟。
     *
     * @param name        技能名
     * @param description 技能描述（清单里那一份；可空）
     * @param dir         技能目录（可空）
     * @throws IOException 留痕失败（调用方按 fail-closed 处理）
     */
    void activate(String name, String description, String dir) throws IOException;

    /**
     * 记一条「技能声明的工具当前不可用」（allowed-tools 求交诊断）。
     *
     * <p>这是<b>诊断</b>不是激活：写失败不拦加载（缺了这条只损失可观测性，
     * 不损失安全性——工具面本来就没被这个字段改变过）。
     *
     * @param name           技能名
     * @param missingAllowed 声明了但当前模型看不见的工具名
     * @throws IOException 留痕失败（调用方按「不拦加载」处理）
     */
    void allowedToolsMismatch(String name, List<String> missingAllowed) throws IOException;
}
