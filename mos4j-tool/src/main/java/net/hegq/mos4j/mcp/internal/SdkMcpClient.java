package net.hegq.mos4j.mcp.internal;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import net.hegq.mos4j.mcp.McpCallResult;
import net.hegq.mos4j.mcp.McpError;
import net.hegq.mos4j.mcp.McpContent;
import net.hegq.mos4j.mcp.McpResourceContent;
import net.hegq.mos4j.mcp.McpResourceInfo;
import net.hegq.mos4j.mcp.McpResourceTemplateInfo;
import net.hegq.mos4j.mcp.McpServerConfig;
import net.hegq.mos4j.mcp.McpToolInfo;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 MCP Java SDK 的客户端实现。
 *
 * <p><b>放在 internal 包</b>：官方 SDK 的类型（{@code McpSyncClient} 等）不外泄到对外契约，
 * 宿主换 SDK 版本时不用改自己的代码（对齐 Go 参考实现「类型不外泄」纪律）。
 *
 * <p>三条实现口径：
 * <ol>
 *   <li><b>客户端能力显式置空</b>（{@code new ClientCapabilities()}）：SDK 默认会声明 roots 能力，
 *       而本框架不实现 roots（不碰宿主文件系统边界）——声明了却不做是撒谎；</li>
 *   <li><b>协议版本协商交给 SDK</b>：不自己维护第二套协商逻辑；</li>
 *   <li><b>stdio 无工作目录</b>：官方 SDK 未暴露 {@code ProcessBuilder.directory}，
 *       Go 侧的 {@code Dir} 字段在 Java 侧不可配置（有意差异 D-MCP-1）。</li>
 * </ol>
 */
public final class SdkMcpClient implements net.hegq.mos4j.mcp.McpClient {

    /** 连接与调用超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final McpServerConfig config;
    private final McpSyncClient client;
    private final JacksonMcpJsonMapper mapper;

    private SdkMcpClient(McpServerConfig config, McpSyncClient client, JacksonMcpJsonMapper mapper) {
        this.config = config;
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * 建立连接并完成初始化。
     *
     * @param config 服务器配置
     * @return 已连接的客户端
     * @throws McpError 连不上或握手失败（传输层）
     */
    public static SdkMcpClient connect(McpServerConfig config) throws McpError {
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        McpSyncClient client;
        try {
            client = io.modelcontextprotocol.client.McpClient.sync(transport(config, mapper))
                    .capabilities(McpSchema.ClientCapabilities.builder().build())
                    .requestTimeout(DEFAULT_TIMEOUT)
                    .initializationTimeout(DEFAULT_TIMEOUT)
                    .build();
            client.initialize();
        } catch (Exception e) {
            throw classify(config, e, "MCP 服务器 " + config.name() + " 连接失败：");
        }
        return new SdkMcpClient(config, client, mapper);
    }

    /** 把 SDK 抛出的异常还原成 MoS4J 的分层（认证类单独归类）。 */
    private static McpError classify(McpServerConfig config, Exception e, String prefix) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthFailure failure) {
                return McpError.auth("MCP 服务器 " + config.name() + " 认证失败：" + failure.getMessage(),
                        e);
            }
            if (cause instanceof McpError mcpError) {
                return mcpError;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return McpError.transport(prefix + e.getMessage(), e);
    }

    private static io.modelcontextprotocol.spec.McpClientTransport transport(
            McpServerConfig config, JacksonMcpJsonMapper mapper) {
        if (config.transport() == net.hegq.mos4j.mcp.McpTransport.STDIO) {
            ServerParameters.Builder builder = new ServerParameters.Builder(config.command())
                    .args(config.args())
                    .env(config.env());
            return new StdioClientTransport(builder.build(), mapper);
        }
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(config.url())
                .jsonMapper(mapper)
                .connectTimeout(DEFAULT_TIMEOUT);
        if (!config.headers().isEmpty()) {
            builder.addHttpRequestCustomizer((request, method, uri, sessionId, context) ->
                    config.headers().forEach(request::header));
        }
        if (config.tokenProvider() != null) {
            // 凭据**每次请求取一次**：customizer 在每次发请求前被调用，
            // 所以 token 是新鲜的（过期与刷新归宿主）。
            builder.addHttpRequestCustomizer((request, method, uri, sessionId, context) -> {
                String token;
                try {
                    token = config.tokenProvider().token();
                } catch (McpError e) {
                    throw new AuthFailure(e.getMessage());
                } catch (Exception e) {
                    throw new AuthFailure("取访问凭据失败：" + e.getMessage());
                }
                if (token != null && !token.isEmpty()) {
                    request.header("Authorization", "Bearer " + token);
                }
            });
            // 401/403 → 认证类错误（不把响应交出去）：这台服务器现在不可用（缺凭据），
            // 不该让上层把它当协议错误继续往下走。
            builder.authorizationErrorHandler(
                    io.modelcontextprotocol.client.transport.customizer.McpHttpClientAuthorizationErrorHandler
                            .fromSync((info, context) -> {
                                int status = info.statusCode();
                                if (status == 401 || status == 403) {
                                    throw new AuthFailure("HTTP " + status + " " + reason(status));
                                }
                                return false;
                            }));
        }
        return builder.build();
    }

    private static String reason(int status) {
        return status == 401 ? "Unauthorized" : "Forbidden";
    }

    /** 内部标记：认证类失败（给 SDK 包一层，出来时还原成 {@link McpError.Kind#AUTH}）。 */
    private static final class AuthFailure extends RuntimeException {
        AuthFailure(String message) {
            super(message);
        }
    }

    @Override
    public List<McpToolInfo> listTools() throws McpError {
        try {
            List<McpToolInfo> tools = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                tools.add(new McpToolInfo(tool.name(), tool.description(), tool.inputSchema()));
            }
            return tools;
        } catch (Exception e) {
            throw McpError.protocol("MCP 服务器 " + config.name() + " 列工具失败：" + e.getMessage(), e);
        }
    }

    @Override
    public McpCallResult callTool(String name, String argumentsJson) throws McpError {
        try {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(name, parseArguments(argumentsJson)));
            List<McpContent> content = new ArrayList<>();
            for (McpSchema.Content part : result.content()) {
                if (part instanceof McpSchema.TextContent text) {
                    content.add(McpContent.text(text.text()));
                } else {
                    content.add(McpContent.other(part.type(), mapper.writeValueAsString(part)));
                }
            }
            return new McpCallResult(content, result.structuredContent(),
                    Boolean.TRUE.equals(result.isError()));
        } catch (Exception e) {
            throw classify(config, e, "MCP 服务器 " + config.name() + " 调用工具 " + name + " 失败：");
        }
    }

    @Override
    public List<McpResourceInfo> listResources() throws McpError {
        try {
            List<McpResourceInfo> resources = new ArrayList<>();
            for (McpSchema.Resource resource : client.listResources().resources()) {
                resources.add(new McpResourceInfo(resource.uri(), resource.name(),
                        resource.description(), resource.mimeType(), resource.size()));
            }
            return resources;
        } catch (Exception e) {
            throw classify(config, e, "MCP 服务器 " + config.name() + " 列资源失败：");
        }
    }

    @Override
    public List<McpResourceTemplateInfo> listResourceTemplates() throws McpError {
        try {
            List<McpResourceTemplateInfo> templates = new ArrayList<>();
            for (McpSchema.ResourceTemplate template : client.listResourceTemplates().resourceTemplates()) {
                templates.add(new McpResourceTemplateInfo(template.uriTemplate(), template.name(),
                        template.description(), template.mimeType()));
            }
            return templates;
        } catch (Exception e) {
            throw classify(config, e, "MCP 服务器 " + config.name() + " 列资源模板失败：");
        }
    }

    @Override
    public List<McpResourceContent> readResource(String uri) throws McpError {
        try {
            McpSchema.ReadResourceResult result = client.readResource(new McpSchema.ReadResourceRequest(uri));
            List<McpResourceContent> contents = new ArrayList<>();
            for (McpSchema.ResourceContents content : result.contents()) {
                if (content instanceof McpSchema.TextResourceContents text) {
                    contents.add(new McpResourceContent(text.uri(), text.text(), text.mimeType(), 0));
                } else if (content instanceof McpSchema.BlobResourceContents blob) {
                    // 二进制只给长度：模型读不了 base64（与工具结果的图像/资源降级同一条路）。
                    contents.add(new McpResourceContent(blob.uri(), null, blob.mimeType(),
                            binaryBytes(blob.blob())));
                } else {
                    contents.add(new McpResourceContent(null, null, null, 0));
                }
            }
            return contents;
        } catch (Exception e) {
            throw classify(config, e, "MCP 服务器 " + config.name() + " 读资源 " + uri + " 失败：");
        }
    }

    /** 二进制资源的字节数（上游给的是 base64 文本，这里只算长度，不进模型上下文）。 */
    private static int binaryBytes(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return 0;
        }
        try {
            return java.util.Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            // 不是合法 base64（上游给的是别的编码）：报个保守长度，不假装知道。
            return base64.length();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) throws Exception {        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        return (Map<String, Object>) (Map<?, ?>) mapper.readValue(argumentsJson, Map.class);
    }

    @Override
    public String instructions() {
        String text = client.getServerInstructions();
        return text == null ? "" : text;
    }

    @Override
    public String protocolVersion() {
        try {
            McpSchema.InitializeResult result = client.getCurrentInitializationResult();
            return result == null || result.protocolVersion() == null ? "" : result.protocolVersion();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (RuntimeException e) {
            // 关闭失败不该盖掉真正的错误：连接已经断了也要让宿主知道「关不掉」。
            client.close();
        }
    }
}
