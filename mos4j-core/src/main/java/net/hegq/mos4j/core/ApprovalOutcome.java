package net.hegq.mos4j.core;

import java.util.Optional;

/**
 * 人工审批的四种结局（机制 M10 / 不变式 I1）。
 *
 * <p><b>为什么要分四档而不是「同意 / 拒绝」两档</b>：两档会把
 * 「人没回答」「通道挂了」和「人明确说不」混成一类，于是
 * fail-closed 的文案与「不许绕」的文案都用不上——
 * 对「人没说」说「用户已拒绝此操作…不要尝试用其他命令」是冤枉用户，
 * 对「人明确拒绝」说「无人应答」则会把责任推给空气。
 *
 * <p>另注：通道报错（{@link #UNAVAILABLE}）**不等于**人拒绝——
 * 归一到 {@code unavailable} 但必须带原因，不能悄悄当成拒绝或当成同意。
 */
public enum ApprovalOutcome {

    /** 人批准了。 */
    APPROVED("approved"),

    /** 人明确拒绝了。 */
    REJECTED("rejected"),

    /** 人在回答过程中取消了这次询问（例如关掉了弹窗）。 */
    CANCELLED("cancelled"),

    /** 无人应答 / 通道故障（fail-closed，等价于拒绝执行）。 */
    UNAVAILABLE("unavailable");

    private final String wire;

    ApprovalOutcome(String wire) {
        this.wire = wire;
    }

    /** 日志与协议里的取值（{@code approval/decided} 事件的 {@code outcome} 字段）。 */
    public String wire() {
        return wire;
    }

    /** 是否允许执行（只有「批准」允许）。 */
    public boolean permits() {
        return this == APPROVED;
    }

    /**
     * 这个结局能不能被**复用**（先验复用：同一轮内不重复问人）。
     *
     * <p>只有「真决定」可以：批准与拒绝。撤回（{@link #CANCELLED}）与无人应答
     * （{@link #UNAVAILABLE}）不是人的决定——复用它们等于把「没人答」当成「批过了」。
     */
    public boolean reusable() {
        return this == APPROVED || this == REJECTED;
    }

    /**
     * 解析日志里的结局名（{@code approved} / {@code rejected} / {@code cancelled} /
     * {@code unavailable}）。
     *
     * <p>会话日志里存的是结局名（会话包不依赖审批包），读回来时用它兑成枚举；
     * 认不出来的名字一律当作「没有决定」（不当成拒绝，也不当成批准）。
     *
     * @param value 日志里的结局名
     * @return 解析结果；认不出来时为空
     */
    public static Optional<ApprovalOutcome> fromWire(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (ApprovalOutcome outcome : values()) {
            if (outcome.wire.equals(value)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }
}
