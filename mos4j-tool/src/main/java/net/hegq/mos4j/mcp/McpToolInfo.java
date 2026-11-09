package net.hegq.mos4j.mcp;

import java.util.Map;

/**
 * 上游 MCP 工具的描述。
 *
 * @param name 上游工具名（在**上游服务器内部**的名字，调用时用它）
 * @param description 上游说明（给模型看的）
 * @param schema 上游入参 schema（<b>原样透传</b>：不重写、不裁剪——
 *             重写会与上游对不上，模型按本地 schema 发的参数上游不认）
 */
public record McpToolInfo(String name, String description, Map<String, Object> schema) {

    /** 构造（schema 可空 = 上游没给）。 */
    public McpToolInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 工具名不能为空");
        }
        description = description == null ? "" : description;
    }
}
