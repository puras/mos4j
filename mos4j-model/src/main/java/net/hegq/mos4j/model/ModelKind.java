package net.hegq.mos4j.model;

/**
 * 模型种类（kind）谱系：区分「模型<b>做什么</b>」，与「模型<b>怎么说</b>」（协议方言）正交。
 *
 * <p><b>为什么要有它</b>：同一个平台会提供 chat / embedding / rerank / TTS / ASR / 图像 / 视频 /
 * 文件解析等不同能力的模型，它们的输入输出语义与生命周期完全不同（chat 是 SSE 单向流，
 * 图像生成是异步任务，embedding 是一次 JSON）。目录、提供方注册表、装饰策略都要能说清
 * 「这一项是什么 kind」——否则宿主只能靠模型 ID 猜。
 *
 * <p><b>与 {@code chat.Capability} 的分工</b>：{@code Capability} 描述的是
 * <b>chat 协议内部</b>的能力位（函数调用 / 流式 / 结构化输出 / 多模态 / 推理）；
 * kind 描述的是<b>跨族的种类</b>。不把 TTS / ASR / REALTIME 塞进 chat 的 Capability——
 * 它们不是 chat 的能力位，是别的 kind。
 *
 * <p><b>kind 与方言正交</b>：kind 是「做什么」，方言（{@code protocol.<方言>.<kind>}）是「怎么说」。
 * 一个 OpenAI 方言可以实现多个 kind；一个 kind 也可以由多个方言实现。
 */
public enum ModelKind {

    /** 对话补全（含视觉理解、工具调用、流式）。 */
    CHAT,

    /** 文本嵌入（文本 → 向量）。 */
    EMBEDDING,

    /** 重排序（query × documents → 分数）。 */
    RERANK,

    /** 图像生成 / 编辑（通常为异步任务，产出二进制产物引用）。 */
    IMAGE,

    /** 视频生成（异步任务，产出二进制产物引用）。 */
    VIDEO,

    /** 文本转语音（TTS，产出二进制产物或音频流）。 */
    SPEECH,

    /** 语音转文本（ASR，整段或流式）。 */
    ASR,

    /** 文件解析（PDF / 文档 → 结构化文本或产物引用）。 */
    FILE,

    /** 实时双工（WebSocket：音频 / 文本双向流）。 */
    REALTIME
}
