package net.hegq.mos4j.mcp;

import java.util.List;

/**
 * 一次 MCP 工具调用的结果。
 *
 * @param content 内容块（按上游顺序）
 * @param structuredContent 结构化内容（上游给了就原样保留，给程序化调用方用；
 *                            <b>不进模型上下文</b>——模型侧走 {@link #toModelText()} 的投影）
 * @param isError 上游是否把这次调用标成错误
 */
public record McpCallResult(List<McpContent> content, Object structuredContent, boolean isError) {

    /** 非文本内容保留的原始 JSON 长度上限（对齐 Go 参考实现的 {@code maxOpaqueJSON} = 2048）。 */
    private static final int RAW_JSON_LIMIT = 2048;

    /** 构造（内容可空）。 */
    public McpCallResult {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * 投影成给模型看的文本。
     *
     * <p>规则（对齐 Go 参考实现 {@code tool/mcp/client.go} 的 {@code project}）：
     * 文本块按顺序换行拼接；非文本块给**按类型分开的占位诊断** + 截断的原始 JSON。
     *
     * <p>为什么图片 / 音频只给占位：本框架没有图片入模的通道，把二进制塞进上下文只会
     * 撑爆预算且模型也读不懂。占位诊断至少让模型知道「上游返回了图片，但这里看不到」。
     */
    public String toModelText() {
        StringBuilder sb = new StringBuilder();
        for (McpContent part : content) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (part.isText()) {
                sb.append(part.text());
            } else {
                sb.append('[').append(note(part.type())).append(']');
                if (part.json() != null && !part.json().isEmpty()) {
                    sb.append(" 原始内容：").append(truncate(part.json()));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 给模型看的**文本部分**（非文本内容不在内）。
     *
     * @return 文本块按顺序换行拼接的结果
     */
    public String textContent() {
        StringBuilder sb = new StringBuilder();
        for (McpContent part : content) {
            if (!part.isText()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.text());
        }
        return sb.toString();
    }

    /**
     * 非文本内容的**结构化占位**（给程序化调用方）。
     *
     * <p>模型侧只看到 {@link #toModelText()} 里的诊断文案；原始内容（类型 + 截断 JSON）
     * 在这里保留——程序化调用方要能知道「上游到底返回了什么类型的非文本内容」。
     *
     * @return 非文本内容块（按上游顺序）
     */
    public List<McpContent> opaqueParts() {
        List<McpContent> out = new java.util.ArrayList<>();
        for (McpContent part : content) {
            if (!part.isText()) {
                out.add(part);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 按内容类型给占位诊断（口径对齐 Go 参考实现的 {@code project} / {@code opaquePart}）。
     *
     * <p>文案按**本框架的实际能力**写：图片 / 音频确实没有入模通道；
     * 资源与资源链接**可以**用资源工具按 URI 读取——所以不能说「不支持资源」。
     *
     * @param type 上游内容类型
     * @return 给模型看的诊断
     */
    private static String note(String type) {
        String kind = type == null || type.isEmpty() ? "unknown" : type;
        return switch (kind) {
            case "image" -> "图像内容未进模型上下文（本框架不支持图片入模）";
            case "audio" -> "音频内容未进模型上下文（本框架不支持音频入模）";
            case "resource" -> "内嵌资源未进模型上下文（可用资源工具按 URI 读取）";
            case "resource_link" -> "资源链接未展开（可用资源工具按 URI 读取）";
            default -> "未知内容类型，未进模型上下文";
        };
    }

    private static String truncate(String raw) {
        if (raw.length() <= RAW_JSON_LIMIT) {
            return raw;
        }
        return raw.substring(0, RAW_JSON_LIMIT) + "…（已截断）";
    }
}
