package net.hegq.mos4j.model;

import java.util.Set;

/**
 * 协议能力位。
 *
 * <p><b>为什么显式声明能力</b>：宿主与驱动层必须知道「这个模型支持不支持流式 /
 * 函数调用 / 多模态」。能力缺口必须 <b>fail-closed</b>（拒绝并说明），
 * 而不是「试试看能不能用」——否则调用会走到一半才失败，
 * 留下半截状态。</p>
 */
public enum Capability {

    /** 支持函数调用（工具）。 */
    FUNCTION_CALLING,
    /** 支持流式输出。 */
    STREAMING,
    /** 支持结构化输出（response_format）。 */
    STRUCTURED_OUTPUT,
    /** 支持多模态输入（图片/音频内容块）。 */
    MULTIMODAL,
    /** 支持推理模型参数与推理 token 统计。 */
    REASONING;

    /** 判断能力集合是否包含指定能力。 */
    public static boolean has(Set<Capability> capabilities, Capability capability) {
        return capabilities != null && capabilities.contains(capability);
    }
}
