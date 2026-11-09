package net.hegq.mos4j.core;

/**
 * 关键文案的唯一出处（机制 M10 + L3 场景「文案对照验收」）。
 *
 * <p><b>为什么集中在一个类</b>：拒绝文案不是措辞偏好，是<b>安全语义</b>——
 * 实测缺了「不许绕」那半句，模型会把 {@code git rm -r lab} 拆成逐个
 * {@code git rm} 达成同一目的，规则形同虚设。散落在各处各写一遍，
 * 迟早有一处漏掉半句，而且测试很难发现（那句仍然「像那么回事」）。
 *
 * <p><b>所以</b>：文案只允许从这里取；L3 场景断言也只引用这里——
 * 改一处，测试全红。
 *
 * <p><b>值必须与 {@code docs/contract/API.md} §4 文案表逐字一致</b>；
 * 语义等价的小差异允许，但必须写进 PR 说明。
 */
public final class Texts {

    /** 载荷冻结违约（机制 M1 / 不变式 I5）。 */
    public static final String PAYLOAD_FROZEN = "扩展点试图改写工具调用载荷（载荷冻结契约，调用已拒绝）";

    /** 守卫拒绝（安全策略）。 */
    public static String guardRejected(String reason) {
        return "工具调用被安全策略拒绝: " + reason;
    }

    /** 钩子拒绝（扩展点）。 */
    public static String hookRejected(String reason) {
        return "工具调用被扩展点拒绝: " + reason;
    }

    /** 需要人工确认但没装配审批通道（不变式 I1：fail-closed）。 */
    public static String askWithoutChannel(String reason) {
        return "工具调用需要人工确认，但未装配审批通道（fail-closed）: " + reason;
    }

    /**
     * 审批被人工拒绝（含「不许绕」半句——机制 M10）。
     *
     * <p>半句「不要尝试用其他命令或方式达成同一目的」不能删、不能改写：
     * 缺了它模型会拆解命令绕过规则。这不是语气问题，是规则是否成立的问题。
     */
    public static String approvalRejected(String reason) {
        return "用户已拒绝此操作，该操作保持拒绝状态。请停下，向用户说明情况；"
                + "不要尝试用其他命令或方式达成同一目的。"
                + "若确有必要，请先向用户说明并获得同意，然后原样重新发起这次调用（会再次请求批准）。"
                + "拒绝时给出的理由：" + reason;
    }

    /** 审批无人应答（fail-closed）。 */
    public static String approvalUnavailable(String reason) {
        return "人工确认无人应答（fail-closed）: " + reason;
    }

    /**
     * 审批先验复用（同一轮内不重复问人）的审计线索。
     *
     * <p><b>为什么写在 reason 里</b>：事件字段清单已冻结，审批载荷里没有单独的
     * 「复用了哪次决定」字段；不擅自新增字段，就把它写进 asked 事件的
     * {@code reason}（只有 asked 事件带 reason，decided 不重复携带）。
     *
     * @param reusedFrom 被复用的那次审批 id
     * @param reason     本次本来要问人的理由（守卫给的理由）
     */
    public static String priorReused(String reusedFrom, String reason) {
        return "复用先验决定 " + reusedFrom + "（未再次询问）: " + reason;
    }

    /** 投影合成中断回执（机制 M4）。 */
    public static String interruptedReceipt(String toolName) {
        return "（" + toolName + " 调用在执行期间被中断，没有结果；如仍需要请重新调用）";
    }

    /** 写前观察：write 目标已存在但没读过（机制 M7）。 */
    public static String writeNotRead(String path) {
        return "write 拒绝：\"" + path + "\" 已存在但未被读取过。先 read 读取当前内容，再决定是否覆盖";
    }

    /**
     * 写前观察：write 目标在读之后被删除（机制 M7）。
     *
     * <p>措辞与 Go 侧原文待 J3 落地时逐字核对（当前沙箱读不到 Go 仓库）。
     */
    public static String writeDeletedSinceRead(String path) {
        return "write 拒绝：\"" + path + "\" 自读取后已被删除";
    }

    /**
     * 写前观察：write 目标在读之后被修改（机制 M7）。
     *
     * <p>措辞与 Go 侧原文待 J3 落地时逐字核对（当前沙箱读不到 Go 仓库）。
     */
    public static String writeModifiedSinceRead(String path) {
        return "write 拒绝：\"" + path + "\" 自读取后已被修改。请重新 read 获取最新内容后再写入";
    }

    /** 写前观察：edit 未先 read（机制 M7）。 */
    public static String editNotRead(String path) {
        return "edit 拒绝：编辑前必须先 read 读取 " + path + " 的当前内容";
    }

    /** 模型输出被截断 → 该工具调用拒绝执行（参数可能不完整）。 */
    public static String truncatedToolCall(String finishReason) {
        return "模型输出被截断（finish_reason=" + finishReason
                + "），此工具调用的参数可能不完整，已拒绝执行。请重新发起该调用";
    }

    /** shell 退出码标记（非零退出码不是错误——机制 M10）。 */
    public static String exitCodeMarker(int code) {
        return "[exit code: " + code + "]";
    }

    private Texts() {
    }
}
