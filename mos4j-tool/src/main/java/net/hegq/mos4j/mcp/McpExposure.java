package net.hegq.mos4j.mcp;

/**
 * MCP 工具的曝光档位。
 *
 * <p>三档语义（对齐 Go 参考实现 {@code tool/mcp} 的 {@code Exposure}）：
 * <ul>
 *   <li>{@link #DIRECT}：模型清单里**直接看得见**，也能被 {@code tool_search} 搜到；</li>
 *   <li>{@link #DEFERRED}（默认）：模型清单里**看不见**，但能被 {@code tool_search} 搜到，
 *       激活后才进清单；</li>
 *   <li>{@link #HIDDEN}：模型看不见、也搜不到，但**仍可被程序化调用**（宿主按名调用）。</li>
 * </ul>
 *
 * <p>为什么默认是 deferred：MCP 服务器的工具面往往很大，全量进清单会挤爆上下文；
 * 而「一开就全进」等于让模型面对一堵工具墙。两头都不能要，就用 deferred。
 */
public enum McpExposure {

    /** 直接进模型清单。 */
    DIRECT,

    /** 默认不进清单；经 {@code tool_search} 激活后进。 */
    DEFERRED,

    /** 不进清单、搜不到；仅程序化调用。 */
    HIDDEN;

    /** 是否直接出现在模型清单里。 */
    public boolean visibleToModel() {
        return this == DIRECT;
    }

    /** 是否能被 {@code tool_search} 搜到（激活后进清单）。 */
    public boolean searchable() {
        return this != HIDDEN;
    }
}
