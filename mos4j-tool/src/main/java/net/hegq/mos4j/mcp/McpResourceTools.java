package net.hegq.mos4j.mcp;

import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 资源工具（对齐 Go 参考实现 {@code tool/mcp/resource.go}）。
 *
 * <p><b>三个共享工具 + 显式 server 参数</b>（工具面恒为 3，不随服务器数增长）：
 * <ul>
 *   <li>{@code mcp_resource_list}：列某台服务器的资源；</li>
 *   <li>{@code mcp_resource_templates}：列某台服务器的资源模板；</li>
 *   <li>{@code mcp_resource_read}：读某个资源。</li>
 * </ul>
 *
 * <p><b>注册口径</b>：只要**任一台**可见服务器确实声明了资源或模板，就注册全部三个工具。
 * 没资源的服务器不单独注册工具——否则模型会拿到一组永远 404 的工具。
 *
 * <p><b>可见性</b>：hidden 服务器的资源不泄露（模型连服务器名都拿不到，资源更不能给）。
 * 三个工具共用同一判定。
 *
 * <p><b>命名</b>：用 {@code mcp_resource_*} 前缀，与单服务器工具的 {@code mcp__<server>__<tool>}
 * 区分开——避免模型把服务器名混进工具名（沿用 Go 参考实现的口径）。
 */
public final class McpResourceTools {

    /** 列资源。 */
    public static final String LIST_NAME = "mcp_resource_list";

    /** 列资源模板。 */
    public static final String TEMPLATES_NAME = "mcp_resource_templates";

    /** 读资源。 */
    public static final String READ_NAME = "mcp_resource_read";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private McpResourceTools() {
    }

    /**
     * 构造三个资源工具。
     *
     * @param manager 管理器（用于找服务器与读资源）
     * @return 工具列表
     */
    public static List<Tool> all(McpManager manager) {
        List<Tool> tools = new ArrayList<>();
        tools.add(FunctionTool.of(LIST_NAME,
                "列出某台 MCP 服务器暴露的资源（server 用系统提示词 mcp_servers 节里的服务器名）。",
                ServerParam.class, args -> listResources(manager, args)));
        tools.add(FunctionTool.of(TEMPLATES_NAME,
                "列出某台 MCP 服务器的资源模板（URI 模板；按模板填参数得到资源 URI，再用 mcp_resource_read 读取）。",
                ServerParam.class, args -> listTemplates(manager, args)));
        tools.add(FunctionTool.of(READ_NAME,
                "读取某台 MCP 服务器上的一个资源（uri 用 mcp_resource_list 列出的资源 URI；文本内容直接返回，"
                        + "二进制只给占位说明）。",
                ReadParam.class, args -> readResource(manager, args)));
        return tools;
    }

    private static String listResources(McpManager manager, String argsJson) throws Exception {
        ServerParam param = MAPPER.readValue(orEmpty(argsJson), ServerParam.class);
        McpClient client = requireClient(manager, param.server());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", param.server());
        List<Map<String, Object>> resources = new ArrayList<>();
        List<McpResourceInfo> infos = new ArrayList<>(client.listResources());
        // 确定性顺序：按 URI 排序（同样的资源，每次给模型的顺序一样）。
        infos.sort((left, right) -> String.valueOf(left.uri()).compareTo(String.valueOf(right.uri())));
        for (McpResourceInfo info : infos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", info.uri());
            if (info.name() != null && !info.name().isEmpty()) {
                item.put("name", info.name());
            }
            if (info.description() != null && !info.description().isEmpty()) {
                item.put("description", info.description());
            }
            if (info.mimeType() != null && !info.mimeType().isEmpty()) {
                item.put("mimeType", info.mimeType());
            }
            if (info.size() != null && info.size() > 0) {
                item.put("size", info.size());
            }
            resources.add(item);
        }
        out.put("resources", resources);
        return MAPPER.writeValueAsString(out);
    }

    private static String listTemplates(McpManager manager, String argsJson) throws Exception {
        ServerParam param = MAPPER.readValue(orEmpty(argsJson), ServerParam.class);
        McpClient client = requireClient(manager, param.server());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", param.server());
        List<Map<String, Object>> templates = new ArrayList<>();
        List<McpResourceTemplateInfo> infos = new ArrayList<>(client.listResourceTemplates());
        infos.sort((left, right) ->
                String.valueOf(left.uriTemplate()).compareTo(String.valueOf(right.uriTemplate())));
        for (McpResourceTemplateInfo info : infos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uriTemplate", info.uriTemplate());
            if (info.name() != null && !info.name().isEmpty()) {
                item.put("name", info.name());
            }
            if (info.description() != null && !info.description().isEmpty()) {
                item.put("description", info.description());
            }
            if (info.mimeType() != null && !info.mimeType().isEmpty()) {
                item.put("mimeType", info.mimeType());
            }
            templates.add(item);
        }
        out.put("templates", templates);
        return MAPPER.writeValueAsString(out);
    }

    private static String readResource(McpManager manager, String argsJson) throws Exception {
        ReadParam param = MAPPER.readValue(orEmpty(argsJson), ReadParam.class);
        McpClient client = requireClient(manager, param.server());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server", param.server());
        out.put("uri", param.uri());
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> opaque = new ArrayList<>();
        for (McpResourceContent content : client.readResource(param.uri())) {
            if (content.binary()) {
                // 二进制不进模型上下文：只给长度与类型（模型读不了 base64）。
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", "resource");
                item.put("bytes", content.blobBytes());
                item.put("mimeType", content.mimeType() == null || content.mimeType().isEmpty()
                        ? "未知类型" : content.mimeType());
                item.put("note", "二进制资源未进模型上下文；需要完整内容请走 MCP 协议读取");
                opaque.add(item);
                continue;
            }
            if (content.text() != null) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(content.text());
            }
        }
        if (text.length() > 0) {
            out.put("content", text.toString());
        }
        if (!opaque.isEmpty()) {
            out.put("opaque", opaque);
        }
        return MAPPER.writeValueAsString(out);
    }

    private static McpClient requireClient(McpManager manager, String server) {
        McpClient client = manager.resourceClient(server);
        if (client == null) {
            throw new IllegalArgumentException("未知服务器 \"" + server
                    + "\"（或该服务器未暴露资源）；可用服务器见系统提示词 mcp_servers 节");
        }
        return client;
    }

    private static String orEmpty(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
    }

    /**
     * 列资源的参数。
     *
     * @param server 服务器名
     */
    public record ServerParam(
            @ToolParam(desc = "服务器名（见 mcp_servers 节）", required = true) String server) {
    }

    /**
     * 读资源的参数。
     *
     * @param server 服务器名
     * @param uri 资源 URI
     */
    public record ReadParam(
            @ToolParam(desc = "服务器名（见 mcp_servers 节）", required = true) String server,
            @ToolParam(desc = "资源 URI（mcp_resource_list 或 mcp_resource_templates 得到）",
                    required = true) String uri) {
    }
}
