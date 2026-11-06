package net.hegq.mos4j.llm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型请求的一次工具调用。
 *
 * <p><b>关键约束：{@code args} 保持 String 原始 JSON 文本</b>，不解析成对象或 Map。
 * 两个原因（机制 M1 载荷冻结）：①载荷冻结要求对 name/args/id 做<b>逐字</b>比对——
 * 解析成对象再序列化会改字节序（键序、空格、数字格式），冻结检查会误报；
 * ②模型偶发输出 {@code {...}{...}} 拼接的参数，需要原样保留再由工具层容错解析
 * （机制 M9 之前的工具层容错，见 T1.9）。</p>
 *
 * <p>JSON 字段名照抄 Go（{@code id}/{@code name}/{@code args}，三者均无条件输出）。</p>
 *
 * @param id   调用标识；与 tool_result 的 callId 配对
 * @param name 工具名
 * @param args 参数 JSON 原文（可能不完整——例如模型输出被截断时）
 */
public record ToolCall(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("args") String args) {

    /**
     * 规范化构造：{@code null} 参数归一为空串（避免下游到处判空）。
     *
     * <p>不这么做会出什么事：{@code args} 为 null 时，载荷冻结比对会 NPE，
     * 而错误栈指向冻结检查而不是真正的空值来源。</p>
     */
    public ToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        args = args == null ? "" : args;
    }
}
