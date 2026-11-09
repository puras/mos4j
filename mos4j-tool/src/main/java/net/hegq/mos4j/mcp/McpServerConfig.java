package net.hegq.mos4j.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 MCP 服务器的配置。
 *
 * <p>字段与 Go 参考实现 {@code tool/mcp.ServerConfig} 对位：
 * {@code Name/Transport/Command/Args/Env/Dir/URL/Headers/Exposure/ToolExposure/Disabled}。
 *
 * <p><b>校验放在构造期</b>：传输方式与必填项对不上（stdio 没命令、http 没 url）立刻报错——
 * 装配期失败比「连了十分钟才发现命令是空的」便宜得多。
 *
 * @param name 服务器名（进公开工具名 {@code mcp__<name>__<tool>}）
 * @param transport 传输方式
 * @param command stdio 启动命令（stdio 必填）
 * @param args stdio 参数
 * @param env stdio 环境变量（同名覆盖）
 * @param dir stdio 工作目录（可空 = 继承进程目录）
 * @param url streamable-http 端点（http 必填）
 * @param headers HTTP 请求头（可空）
 * @param exposure 服务器默认曝光档（默认 deferred）
 * @param toolExposure 逐工具曝光覆盖（精确工具名 / {@code "*"}）
 * @param disabled 是否禁用（禁用 = 不连接，但要在快照里写明「未连接（配置为禁用）」）
 * @param tokenProvider 访问凭据来源（可空 = 不注入凭据；仅 streamable-http 生效）
 */
public record McpServerConfig(
        String name,
        McpTransport transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String dir,
        String url,
        Map<String, String> headers,
        McpExposure exposure,
        Map<String, McpExposure> toolExposure,
        boolean disabled,
        McpTokenProvider tokenProvider) {

    /** 构造（校验 + 归一化）。 */
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 服务器名不能为空");
        }
        if (transport == null) {
            throw new IllegalArgumentException("MCP 服务器 " + name + "：传输方式不能为空");
        }
        if (transport == McpTransport.STDIO) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("MCP 服务器 " + name + "：stdio 传输必须给 command");
            }
        } else if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MCP 服务器 " + name + "：http 传输必须给 url");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        toolExposure = toolExposure == null ? Map.of() : Map.copyOf(toolExposure);
        exposure = exposure == null ? McpExposure.DEFERRED : exposure;
    }

    /**
     * stdio 服务器。
     *
     * @param name 服务器名
     * @param command 启动命令
     * @param args 参数
     */
    public static McpServerConfig stdio(String name, String command, List<String> args) {
        return new McpServerConfig(name, McpTransport.STDIO, command, args, Map.of(), null, null,
                Map.of(), McpExposure.DEFERRED, Map.of(), false, null);
    }

    /**
     * streamable-http 服务器。
     *
     * @param name 服务器名
     * @param url 端点
     */
    public static McpServerConfig http(String name, String url) {
        return new McpServerConfig(name, McpTransport.STREAMABLE_HTTP, null, List.of(), Map.of(), null,
                url, Map.of(), McpExposure.DEFERRED, Map.of(), false, null);
    }

    /** 覆盖服务器默认曝光档。 */
    public McpServerConfig withExposure(McpExposure value) {
        return new McpServerConfig(name, transport, command, args, env, dir, url, headers, value,
                toolExposure, disabled, tokenProvider);
    }

    /**
     * 逐工具覆盖曝光档。
     *
     * @param tool 上游工具名（或 {@code "*"} 表示兜底）
     * @param value 曝光档
     */
    public McpServerConfig withToolExposure(String tool, McpExposure value) {
        Map<String, McpExposure> next = new LinkedHashMap<>(toolExposure);
        next.put(tool, value);
        return new McpServerConfig(name, transport, command, args, env, dir, url, headers, exposure,
                next, disabled, tokenProvider);
    }

    /**
     * 装访问凭据来源（仅 streamable-http 生效）。
     *
     * @param provider 凭据来源（可空 = 不注入凭据）
     * @return this
     */
    public McpServerConfig withTokenProvider(McpTokenProvider provider) {
        return new McpServerConfig(name, transport, command, args, env, dir, url, headers, exposure,
                toolExposure, disabled, provider);
    }

    /** 标记为禁用（不连接，但快照要写明）。 */
    public McpServerConfig asDisabled() {
        return new McpServerConfig(name, transport, command, args, env, dir, url, headers, exposure,
                toolExposure, true, tokenProvider);
    }

    /**
     * 解析某个上游工具的曝光档。
     *
     * <p>顺序（对齐 Go 参考实现）：<b>精确工具名 &gt; {@code "*"} &gt; 服务器默认</b>。
     * 精确优先于通配，是为了让宿主能对个别工具开口子，而不必改整台服务器的策略。
     */
    public McpExposure exposureOf(String toolName) {
        McpExposure exact = toolExposure.get(toolName);
        if (exact != null) {
            return exact;
        }
        McpExposure wildcard = toolExposure.get("*");
        if (wildcard != null) {
            return wildcard;
        }
        return exposure;
    }
}
