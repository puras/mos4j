package net.hegq.mos4j.session;

import java.util.List;

/**
 * 技能事件载荷（{@code skill/activate} 与 {@code skill/allowed-tools-mismatch}）。
 *
 * <p><b>只记元数据</b>：技能正文已经在 {@code tool/result} 里（那是模型看到的东西），
 * 这里记的是「加载了哪个技能」这条<b>人能读</b>的审计线索。
 *
 * <p>字段与 Go 侧 {@code session.SkillEvent} 的 tag 逐字一致：
 * {@code name} 无条件、{@code description} / {@code dir} / {@code missingAllowed} 空则省略。
 *
 * @param name           技能名
 * @param description    一句话说明（清单里那份；可空）
 * @param dir            技能目录（可空）
 * @param missingAllowed 声明了但当前模型看不见的工具名（mismatch 时非空）
 */
public record SkillEvent(
        String name,
        String description,
        String dir,
        List<String> missingAllowed) {

    /**
     * 防御性拷贝：审计载荷入日志后不可变（与 {@link Event#toolCalls()} 同一纪律）。
     */
    public SkillEvent {
        missingAllowed = missingAllowed == null ? null : List.copyOf(missingAllowed);
    }
}