package net.hegq.mos4j.agent.mcp;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.mcp.McpCallResult;
import net.hegq.mos4j.mcp.McpClient;
import net.hegq.mos4j.mcp.McpClientFactory;
import net.hegq.mos4j.mcp.McpContent;
import net.hegq.mos4j.mcp.McpExposure;
import net.hegq.mos4j.mcp.McpKit;
import net.hegq.mos4j.mcp.McpResourceContent;
import net.hegq.mos4j.mcp.McpResourceInfo;
import net.hegq.mos4j.mcp.McpResourceTemplateInfo;
import net.hegq.mos4j.mcp.McpServerConfig;
import net.hegq.mos4j.mcp.McpToolInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具的 deferred 曝光链路：搜得到 → 激活 → **同一轮的下一次请求**就声明。
 *
 * <p>守护的口径（对齐 Go 的 {@code agent/driver/react/react_deferred_test.go:133
 * TestActivatedToolVisibleInSameTurn}）：
 * <ul>
 *   <li>DEFERRED 档的 MCP 工具**不进模型清单**，但**能被 tool_search 搜到**（目录里有登记）；</li>
 *   <li>激活发生在第 1 轮的执行期；</li>
 *   <li>第 2 轮（同一次 run 内）的请求清单里就该出现它——不是等下一个 turn；</li>
 *   <li>出现后能真的调用上游（tool/result 有内容）。</li>
 * </ul>
 */
class McpDeferredVisibilityTest {

    /** 按轮次脚本化响应、并记录每轮收到的工具清单的模型替身。 */
    private static final class ScriptedModel implements ChatModel {
        private final List<Response> steps;
        private final List<List<String>> seenToolNames = new ArrayList<>();
        private final AtomicInteger round = new AtomicInteger();

        ScriptedModel(List<Response> steps) {
            this.steps = steps;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, (CancellationToken) null);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            List<String> names = request.tools() == null ? List.of()
                    : request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList();
            seenToolNames.add(names);
            return steps.get(round.getAndIncrement());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request, (CancellationToken) null);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    /** 带两个上游工具的假客户端。 */
    private static final class FakeClient implements McpClient {

        @Override
        public List<McpToolInfo> listTools() {
            return List.of(
                    new McpToolInfo("ok", "正常工具", null),
                    new McpToolInfo("secret", "敏感工具", null));
        }

        @Override
        public McpCallResult callTool(String name, String argumentsJson) {
            return new McpCallResult(List.of(McpContent.text("upstream:" + name)), null, false);
        }

        @Override
        public List<McpResourceInfo> listResources() {
            return List.of();
        }

        @Override
        public List<McpResourceTemplateInfo> listResourceTemplates() {
            return List.of();
        }

        @Override
        public List<McpResourceContent> readResource(String uri) {
            return List.of();
        }

        @Override
        public String instructions() {
            return "";
        }

        @Override
        public String protocolVersion() {
            return "2025-06-18";
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeFactory implements McpClientFactory {

        @Override
        public McpClient connect(McpServerConfig config) {
            return new FakeClient();
        }
    }

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    private static Response toolCall(String id, String name, String args) {
        return new Response(Message.assistant("", List.of(new ToolCall(id, name, args))), NO_USAGE,
                "tool_calls");
    }

    private static Response text(String content) {
        return new Response(Message.assistant(content), NO_USAGE, "stop");
    }

    @Test
    void aDeferredMcpToolIsSearchableAndDeclaredAfterActivation() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("a", "http://localhost:1/")
                        .withExposure(McpExposure.DEFERRED)), new FakeFactory())) {

            // 第 1 轮：模型激活 mcp__a__ok；第 2 轮：模型调用它；第 3 轮：收尾
            ScriptedModel model = new ScriptedModel(List.of(
                    toolCall("c1", "activate", "{\"tools\":[\"mcp__a__ok\"]}"),
                    toolCall("c2", "mcp__a__ok", "{}"),
                    text("完成")));

            Agent agent = Agent.of(Config.builder()
                    .model(model)
                    .tools(registry)
                    .closer(kit)
                    .build());
            Session session = new Session("s1", null);
            agent.run(session, "用一下 ok 工具");

            List<String> round1 = model.seenToolNames.get(0);
            assertFalse(round1.contains("mcp__a__ok"),
                    "DEFERRED 档不进模型清单: " + round1);
            assertTrue(round1.contains("tool_search"), "deferred 非空时自动注册 tool_search: " + round1);
            assertTrue(round1.contains("activate"), "deferred 非空时自动注册 activate: " + round1);

            // 第 2 轮（同一次 run 内）：激活的工具进入请求清单。
            List<String> round2 = model.seenToolNames.get(1);
            assertTrue(round2.contains("mcp__a__ok"),
                    "激活后同一轮的下一次请求就该声明: " + round2);

            // 真的调到了上游。
            assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.TOOL_RESULT
                            && e.toolResult() != null
                            && e.toolResult().content().contains("upstream:ok")),
                    "激活后的 MCP 工具能真的调用上游（上游用的是原名 ok）");
        }
    }

    @Test
    void aDeferredMcpToolIsFoundByToolSearch() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("a", "http://localhost:1/")
                        .withExposure(McpExposure.DEFERRED)), new FakeFactory())) {

            Object out = registry.call("tool_search", "{\"query\":\"正常工具\"}");
            assertTrue(String.valueOf(out).contains("mcp__a__ok"),
                    "DEFERRED 档的 MCP 工具应能被 tool_search 搜到: " + out);
        }
    }
}
