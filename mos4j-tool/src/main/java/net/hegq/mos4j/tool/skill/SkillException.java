package net.hegq.mos4j.tool.skill;

/**
 * 技能装配期的问题（不合法 / 读不动 / 重名……）。
 *
 * <p><b>为什么用异常而不是跳过</b>：单个技能的坏不该让整批技能装不上，
 * 但「静默跳过」更糟——宿主会以为技能已经装好。调用方（目录扫描）捕获它，
 * 转成一条诊断（{@code warnings}），让宿主在快照里看得见。
 */
public class SkillException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造器。
     *
     * @param message 原因（中文，面向宿主）
     */
    public SkillException(String message) {
        super(message);
    }

    /**
     * 构造器（带原因）。
     *
     * @param message 原因（中文，面向宿主）
     * @param cause   底层异常
     */
    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
