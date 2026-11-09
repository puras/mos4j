package net.hegq.mos4j.agent.mcp;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.hook.GuardEvent;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.policy.ToolNamePolicy;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * policy / guard 与 MCP 工具的联动测试。
 *
 * <p>守护的口径：被策略拒的 MCP 工具**不进模型清单**，点名调用也**被守卫拦下**——
 * 两处读同一份名单（可见性槽 + 运行时守卫），不会出现「看得见却调不动」或「看不见却能调」。
 */
class McpPolicyIntegrationTest {

    /** 最小模型替身。 */
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
            return new FakeClient();
        }
    }

    @Test
    void aDeniedMcpToolIsInvisibleAndUncallable() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("a", "http://localhost:1/").withExposure(McpExposure.DIRECT)), new FakeFactory())) {

            Agent agent = Agent.of(Config.builder()
                    .model(new StubModel())
                    .tools(registry)
                    .policy(ToolNamePolicy.of(List.of("mcp__a__ok"), List.of()))
                    .closer(kit)
                    .build());

            List<String> visible = registry.visibleNames();
            assertTrue(visible.contains("mcp__a__ok"), visible.toString());
            assertFalse(visible.contains("mcp__a__secret"),
                    "被策略拒的 MCP 工具不进模型清单：" + visible);

            var decision = agent.runContext().security().guards()
                    .check(new GuardEvent("mcp__a__secret", "{}", "c1"));
            assertFalse(decision.permits(), "点名调用也被守卫拦下");
        }
    }

    @Test
    void anAllowedMcpToolStaysVisible() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("a", "http://localhost:1/").withExposure(McpExposure.DIRECT)), new FakeFactory())) {

            Agent.of(Config.builder()
                    .model(new StubModel())
                    .tools(registry)
                    .policy(ToolNamePolicy.of(List.of("mcp__*"), List.of()))
                    .closer(kit)
                    .build());

            List<String> visible = new ArrayList<>(registry.visibleNames());
            assertTrue(visible.contains("mcp__a__ok"), visible.toString());
            assertTrue(visible.contains("mcp__a__secret"), visible.toString());
        }
    }

    @Test
    void aHotPolicySwapAlsoMovesTheMcpTools() {
        ToolRegistry registry = new ToolRegistry();
        try (McpKit kit = McpKit.attach(registry,
                List.of(McpServerConfig.http("a", "http://localhost:1/").withExposure(McpExposure.DIRECT)), new FakeFactory())) {

            Agent agent = Agent.of(Config.builder()
                    .model(new StubModel())
                    .tools(registry)
                    .policy(ToolNamePolicy.of(List.of("mcp__a__ok"), List.of()))
                    .closer(kit)
                    .build());

            assertFalse(registry.visibleNames().contains("mcp__a__secret"));

            agent.setPolicy(ToolNamePolicy.of(List.of("mcp__a__secret"), List.of()));

            List<String> visible = registry.visibleNames();
            assertTrue(visible.contains("mcp__a__secret"), visible.toString());
            assertFalse(visible.contains("mcp__a__ok"), visible.toString());
        }
    }
}
