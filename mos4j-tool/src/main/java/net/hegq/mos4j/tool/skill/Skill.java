package net.hegq.mos4j.tool.skill;

/**
 * 一个已装配的技能：元数据 + 正文位置 + 来源目录。
 *
 * <p><b>正文不驻留</b>：{@code bodyPath} 指向 {@code SKILL.md}，正文在
 * <b>调用时现读</b>——改了 {@code SKILL.md} 立刻生效，不重扫、不重启，
 * 也不用盯文件。这是「目录 / 正文分离」：目录摘要只管「有哪些技能」，
 * 正文管「技能说了什么」，两者的变更节奏本来就不一样。
 *
 * @param metadata 元数据（frontmatter）
 * @param dir      技能目录（{@code SKILL.md} 所在目录）
 * @param bodyPath 技能文件路径（正文在调用时从这里现读）
 */
public record Skill(SkillMetadata metadata, String dir, String bodyPath) {

    /** 技能名。 */
    public String name() {
        return metadata.name();
    }

    /** 技能描述。 */
    public String description() {
        return metadata.description();
    }

    /** 「什么时候用」的提示（可空）。 */
    public String whenToUse() {
        return metadata.whenToUse();
    }

    /** 声明的 allowed-tools（只诊断，不生效）。 */
    public java.util.List<String> allowedTools() {
        return metadata.allowedTools();
    }

    /** 是否仅用户可调用（模型不可见、不可加载）。 */
    public boolean disableModelInvocation() {
        return metadata.disableModelInvocation();
    }
}
