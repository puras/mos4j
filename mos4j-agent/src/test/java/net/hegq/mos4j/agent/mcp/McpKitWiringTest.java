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
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.mcp.McpCallResult;
import net.hegq.mos4j.mcp.McpClient;
import net.hegq.mos4j.mcp.McpClientFactory;
import net.hegq.mos4j.mcp.McpContent;
import net.hegq.mos4j.mcp.McpKit;
import net.hegq.mos4j.mcp.McpResourceContent;
import net.hegq.mos4j.mcp.McpResourceInfo;
import net.hegq.mos4j.mcp.McpResourceTemplateInfo;
import net.hegq.mos4j.mcp.McpServerConfig;
import net.hegq.mos4j.mcp.McpSnapshot;
import net.hegq.mos4j.mcp.McpToolInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 与系统提示词 / Describe 快照的接线测试。
 *
 * <p>守护的口径：MCP 的装配事实（几台连上、哪台失败、工具多少）必须出现在快照里；
 * 模型要知道有哪些服务器（{@code mcp_servers} 分节）。
 */
class McpKitWiringTest {

    /** 最小模型替身（智能体要求模型：fail-closed）。 */
    private static final class StubModel implements ChatModel {

        @Override
        public Response chat(Request request) {
            return new Response(Message.assistant("好"), new Usage(0, 0, 0, 0, null),
                    Response.FINISH_END_TURN);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of();
        }
    }

    /** 假客户端：一台正常、一台失败。 */
    private static final class FakeClient implements McpClient {

        private final boolean fail;

        FakeClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public List<McpToolInfo> listTools() {
            if (fail) {
                throw new IllegalStateException("连不上");
            }
            return List.of(new McpToolInfo("echo", "回声", null));
        }

        @Override
        public McpCallResult callTool(String name, String argumentsJson) {
            return new McpCallResult(List.of(McpContent.text("ok")), null, false);
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
            return new FakeClient("bad".equals(config.name()));
        }
    }

    @Test
    void theServersSectionNamesTheServers() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("good", "http://localhost:1/")), new FakeFactory())) {

            String section = kit.serversSection();

            assertTrue(section.contains("good"), section);
            assertTrue(section.contains("mcp__"), section);
        }
    }

    @Test
    void theDescribeSnapshotCarriesTheMcpFacts() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry, List.of(
                McpServerConfig.http("good", "http://localhost:1/"),
                McpServerConfig.http("bad", "http://localhost:2/")), new FakeFactory())) {

            List<String> features = new ArrayList<>();
            List<String> caveats = new ArrayList<>();
            kit.describeContributor().contribute(features, caveats);

            assertEquals(1, features.size(), features.toString());
            assertTrue(features.get(0).contains("MCP"), features.get(0));
            assertEquals(1, caveats.size(), caveats.toString());
            assertTrue(caveats.get(0).contains("bad"), caveats.get(0));
        }
    }

    @Test
    void theAgentSnapshotIncludesTheMcpFacts() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("good", "http://localhost:1/")), new FakeFactory())) {

            Agent agent = Agent.of(Config.builder()
                    .model(new StubModel())
                    .tools(registry)
                    .describeContributor(kit.describeContributor())
                    .closer(kit)
                    .build());

            String snapshot = agent.describe().toString();

            assertTrue(snapshot.contains("MCP"), snapshot);
        }
    }

    @Test
    void theKitIsClosedThroughTheAgent() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("good", "http://localhost:1/")), new FakeFactory());

        Agent agent = Agent.of(Config.builder()
                .model(new StubModel())
                .tools(registry)
                .closer(kit)
                .build());

        agent.close();

        assertFalse(kit.snapshot().connectedServers().size() > 0
                && kit.manager().snapshot().servers().stream()
                .anyMatch(status -> status.status() == McpSnapshot.Status.CONNECTED
                        && status.detail() != null && status.detail().contains("closed")),
                "关闭后不应留下还连着的服务器");
    }
}
