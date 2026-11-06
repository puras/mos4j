package net.hegq.mos4j.tool.skill;

import java.util.List;

/**
 * 重扫的结果（宿主拿它决定要不要给模型补一条消息）。
 *
 * @param changed 目录摘要是否变了（快照已替换）
 * @param added   新增的技能名
 * @param removed 被移除的技能名
 * @param updated 元数据变化的技能名
 * @param notice  给模型的 user 侧短消息（changed 时非空）
 */
public record SkillReloadResult(
        boolean changed,
        List<String> added,
        List<String> removed,
        List<String> updated,
        String notice) {

    public SkillReloadResult {
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
        updated = updated == null ? List.of() : List.copyOf(updated);
        notice = notice == null ? "" : notice;
    }

    /**
     * 没有变化的结果（幂等重扫的返回值）。
     *
     * @return 空变更结果
     */
    public static SkillReloadResult unchanged() {
        return new SkillReloadResult(false, List.of(), List.of(), List.of(), "");
    }

    /**
     * 带上通知文案（内部用：差异算完才知道文案）。
     *
     * @param newNotice 通知文案
     * @return 新结果
     */
    public SkillReloadResult withNotice(String newNotice) {
        return new SkillReloadResult(changed, added, removed, updated, newNotice);
    }
}
