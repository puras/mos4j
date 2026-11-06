package net.hegq.mos4j.tool.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能元数据（来自 {@code SKILL.md} 的 frontmatter）。
 *
 * <p><b>为什么单独一层</b>：元数据是<b>常驻</b>的（进系统提示词的技能清单，每轮都在），
 * 正文是<b>按需</b>的（模型调用 skill 工具时才读）。两者生命周期不同、体积差两个数量级，
 * 混在一个对象里迟早有人把正文也塞进清单——那正是「技能清单把上下文吃光」的经典原因。
 *
 * @param name                  技能名（kebab-case，必需；缺失时回退到目录名）
 * @param description           一句话说明（必需）：模型据此判断要不要加载这个技能
 * @param whenToUse             「什么时候用」的提示（可选，进清单）
 * @param allowedTools          「预批准工具名单」（可选；<b>只诊断、不生效</b>，见类注释）
 * @param disableModelInvocation 为 true 时模型看不到也加载不了这个技能（只能用户显式调用）
 * @param extra                 自由元数据（frontmatter 里的 {@code metadata} 字段，原样保留）
 */
public record SkillMetadata(
        String name,
        String description,
        String whenToUse,
        List<String> allowedTools,
        boolean disableModelInvocation,
        Map<String, Object> extra) {

    public SkillMetadata {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /**
     * 校验元数据（PI / DSH 同款约束）。
     *
     * <p><b>fail-closed</b>：不合法的技能<b>不装</b>（调用方跳过并出诊断），
     * 而不是「装上再让模型撞墙」——模型撞墙时看到的是「技能不存在」，
     * 宿主看到的是「莫名其妙的行为」，两边都拿不到真正的原因。
     *
     * @throws SkillException 元数据不合法（附具体原因）
     */
    public void validate() {
        if (name == null || name.isEmpty()) {
            throw new SkillException("name 缺失");
        }
        int nameLength = name.codePointCount(0, name.length());
        if (nameLength > SkillCatalog.MAX_NAME_LENGTH) {
            throw new SkillException("name 过长（" + nameLength + " 字符，上限 "
                    + SkillCatalog.MAX_NAME_LENGTH + "）");
        }
        if (!SkillCatalog.NAME_PATTERN.matcher(name).matches()) {
            throw new SkillException("name \"" + name + "\" 不是合法 kebab-case"
                    + "（需匹配 ^[a-z0-9]+(?:-[a-z0-9]+)*$）");
        }
        if (description == null || description.isEmpty()) {
            throw new SkillException("description 缺失");
        }
        int descriptionLength = description.codePointCount(0, description.length());
        if (descriptionLength > SkillCatalog.MAX_DESCRIPTION_LENGTH) {
            throw new SkillException("description 过长（" + descriptionLength + " 字符，上限 "
                    + SkillCatalog.MAX_DESCRIPTION_LENGTH + "）");
        }
    }
}
