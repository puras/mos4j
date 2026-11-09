package net.hegq.mos4j.mcp;

import net.hegq.mos4j.tool.Tool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 把上游 MCP 工具包装成 MoS4J 的 {@link Tool}。
 *
 * <p>三条口径（对齐 Go 参考实现 {@code tool/mcp/tool.go}）：
 * <ul>
 *   <li><b>schema 原样透传</b>：上游给什么就用什么，不重写、不裁剪——本地改过的 schema
 *       会与上游对不上，模型按本地 schema 发的参数上游不认；</li>
 *   <li><b>上游 {@code isError} → 工具失败</b>：错误文本原样带给模型，让它能自我纠正
 *       （吞掉错误等于让模型以为成功了）；</li>
 *   <li><b>公开名带命名空间</b>：{@code mcp__<server>__<tool>}，避免与内置工具撞名。</li>
 * </ul>
 */
public final class McpToolAdapter implements Tool {

    /** 参数校验用的 JSON mapper（只读、线程安全）。 */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final McpClient client;
    private final McpToolInfo info;
    private final String publicName;

    private McpToolAdapter(McpClient client, McpToolInfo info, String publicName) {
        this.client = client;
        this.info = info;
        this.publicName = publicName;
    }

    /**
     * 包装一个上游工具。
     *
     * @param client 客户端
     * @param serverName 服务器名（进公开名）
     * @param info 上游工具描述
     */
    public static McpToolAdapter of(McpClient client, String serverName, McpToolInfo info) {
        return new McpToolAdapter(client, info, McpToolNaming.publicName(serverName, info.name()));
    }

    /** 公开名（{@code mcp__<server>__<tool>}）。 */
    public String publicName() {
        return publicName;
    }

    /** 上游工具名（调用上游时用它）。 */
    public String upstreamName() {
        return info.name();
    }

    @Override
    public String name() {
        return publicName;
    }

    @Override
    public String description() {
        return info.description();
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> schema = info.schema();
        // 上游没给 schema 时兜底成空对象：schema 为 null 会让注册表退回反射路径，
        // 而 MCP 工具没有参数类型，反射拿不到东西。
        return schema == null ? Map.of("type", "object") : schema;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        String raw = argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
        requireJsonObject(raw);
        McpCallResult result = client.callTool(info.name(), raw);
        if (result == null) {
            // 上游没给结果：fail-closed，不能当成空成功（模型会以为工具跑通了）。
            throw McpError.protocol("MCP 工具 " + publicName + " 上游没有返回结果", null);
        }
        if (result.isError()) {
            // 上游把这次调用标成错误 → 工具失败（不是成功返回一个「错误说明」）。
            throw new IllegalStateException("MCP 工具 " + publicName + " 返回错误：" + result.toModelText());
        }
        return result.toModelText();
    }

    /**
     * 参数必须是 JSON 对象（口径对齐 Go 参考实现 {@code tool/mcp/tool.go} 的 {@code Execute}）。
     *
     * <p>不是 JSON 对象时在**本地**就报协议层错误：参数形状不对时上游多半会回一个笼统的
     * 「invalid params」，本地先拦能把「这次调用参数不合法」与「服务器现在不可用」分开。
     *
     * @param raw 参数 JSON 原文
     * @throws McpError 参数不是 JSON 对象
     */
    private void requireJsonObject(String raw) throws McpError {
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw McpError.protocol("MCP 工具 " + publicName + " 的参数不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw McpError.protocol("MCP 工具 " + publicName + " 的参数不是 JSON 对象", null);
        }
    }
}
