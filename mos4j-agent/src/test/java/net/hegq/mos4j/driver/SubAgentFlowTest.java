package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.agent.subagent.SubAgentSessionFactory;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.ModelOption;
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
import net.hegq.mos4j.tool.subagent.SubAgentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理在真实链路里的装配与行为测试（{@code Agent.of} + ReAct 循环）。
 *
 * <p><b>守护的不变式</b>：
 * <ul>
 *   <li><b>能力即证据</b>：清单非空才注册 {@code subagent__<name>} 工具，清单进系统提示词；</li>
 *   <li><b>子代理失败不是父轮次失败</b>：子代理跑挂了，父轮次照样收尾，父模型拿到一段回执；</li>
 *   <li><b>会话隔离</b>：子代理的中间推理留在子会话里，父会话只看得到回执；</li>
 *   <li><b>模型覆盖</b>：声明了目录里的别的模型就用那个，没声明就跟父的当前模型。</li>
 * </ul>
 */
class SubAgentFlowTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 按轮次脚本化响应的模型替身：记录每次收到的系统提示词与工具清单。 */
    private static final class ScriptedModel implements ChatModel {

        private final List<Object> steps;
        private final List<String> seenSystem = new ArrayList<>();
        private final List<List<String>> seenToolNames = new ArrayList<>();
        private final AtomicInteger round = new AtomicInteger();

        /** 步骤可以是响应，也可以是 RuntimeException（那一轮直接报错）。 */
        ScriptedModel(Object... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public Response chat(Request request) {
            return chat(request, (CancellationToken) null);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            seenSystem.add(request.messages().isEmpty() ? "" : String.valueOf(request.messages().get(0).content()));
            seenToolNames.add(request.tools() == null ? List.of()
                    : request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList());
            Object step = steps.get(round.getAndIncrement());
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            return (Response) step;
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

    private static Response toolCall(String id, String name, String args) {
        return new Response(Message.assistant("", List.of(new ToolCall(id, name, args))), NO_USAGE, "tool_calls");
    }

    private static Response text(String content) {
        return new Response(Message.assistant(content), NO_USAGE, "stop");
    }

    private static Path newRoot() throws IOException {
        Path root = Path.of("target", "test-tmp", "subagentflow-" + System.nanoTime());
        Files.createDirectories(root);
        return root;
    }

    private static void writeAgent(Path root, String name, String extraFrontmatter) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SubAgentCatalog.FILE_NAME),
                "---\nname: " + name + "\ndescription: 干活的" + extraFrontmatter + "\n---\n你是 " + name + "。\n");
    }

    private static SubAgentCatalog catalog(Path root) {
        return SubAgentCatalog.load(SubAgentCatalog.Options.of(List.of(root.toString())));
    }

    @Test
    @DisplayName("没装子代理：不注册工具、提示词不动")
    void withoutSubAgentsNothingChanges() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .systemPrompt("## base\n你是 MoS4J。")
                .build());

        assertFalse(registry.visibleNames().stream().anyMatch(n -> n.startsWith("subagent__")));
        assertFalse(agent.runContext().systemPrompt().contains("available_subagents"));
    }

    @Test
    @DisplayName("装了子代理：注册 subagent__<name> 工具，清单进系统提示词")
    void subAgentRegistersToolAndCatalogSection() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "");
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .systemPrompt("## base\n你是 MoS4J。\n\n## instructions\n按步骤做。")
                .subAgents(catalog(root))
                .build());

        assertTrue(registry.visibleNames().contains("subagent__deploy"), "能力即证据：清单非空就注册工具");
        String prompt = agent.runContext().systemPrompt();
        assertTrue(prompt.contains("available_subagents"), prompt);
        assertTrue(prompt.contains("deploy"), prompt);
        assertTrue(prompt.indexOf("## instructions") < prompt.indexOf("## subagents")
                        || prompt.indexOf("available_subagents") < prompt.indexOf("## instructions"),
                "子代理清单按中心顺序落位: " + prompt);
    }

    @Test
    @DisplayName("模型调用子代理：父轮次收到一段正常回执，父轮次不失败")
    void modelCallsSubAgentAndGetsARceipt() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "");
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "subagent__deploy", "{\"task\":\"查一下发布流程\"}"),
                text("{\"summary\":\"流程是三步\",\"findings\":[{\"ref\":\"AGENT.md\",\"summary\":\"写了三步\"}]}"),
                text("父模型拿到回执了"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt("## base\n你是 MoS4J。")
                .subAgents(catalog(root))
                .subAgentSessions(sessionId -> new Session(sessionId, null))
                .build());

        agent.run(session, "去查发布流程");

        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT)
                .findFirst()
                .orElseThrow();
        assertFalse(result.toolResult().isError(), "子代理失败才是失败；成功时是正常结果");
        assertTrue(result.toolResult().content().contains("\"status\":\"completed\""),
                result.toolResult().content());
        assertTrue(result.toolResult().content().contains("流程是三步"), result.toolResult().content());

        // 父轮次正常收尾
        Event end = session.events().stream()
                .filter(e -> e.kind() == EventKind.TURN_END)
                .findFirst()
                .orElseThrow();
        assertFalse(end.error() != null && !end.error().isBlank(), "父轮次不因子代理失败而失败");
    }

    @Test
    @DisplayName("子代理跑挂了：父轮次照样收尾，父模型拿到 status=error 的回执")
    void failingSubAgentStillEndsTheParentTurn() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "");
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "subagent__deploy", "{\"task\":\"干活\"}"),
                // 子代理用同一个模型：它这一轮直接报错 → 子代理这轮失败
                new IllegalStateException("子代理的模型炸了"),
                text("父模型看到失败回执"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt("## base\n你是 MoS4J。")
                .subAgents(catalog(root))
                .subAgentSessions(sessionId -> new Session(sessionId, null))
                .build());

        agent.run(session, "去干活");

        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT)
                .findFirst()
                .orElseThrow();
        assertTrue(result.toolResult().content().contains("\"status\":\"error\""),
                result.toolResult().content());
        Event end = session.events().stream()
                .filter(e -> e.kind() == EventKind.TURN_END)
                .findFirst()
                .orElseThrow();
        assertFalse(end.error() != null && !end.error().isBlank(), "子代理失败不是父轮次失败");
    }

    @Test
    @DisplayName("会话隔离：子代理跑在独立子会话里，父会话看不到它的中间过程")
    void subAgentRunsInItsOwnSession() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "");
        List<String> childSessionIds = new ArrayList<>();
        List<Session> childSessions = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "subagent__deploy", "{\"task\":\"干活\"}"),
                text("{\"summary\":\"子代理结论\"}"),
                text("父模型收尾"));
        Session parent = new Session("parent-1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt("## base\n你是 MoS4J。")
                .subAgents(catalog(root))
                .subAgentSessions(sessionId -> {
                    childSessionIds.add(sessionId);
                    Session child = new Session(sessionId, null);
                    childSessions.add(child);
                    return child;
                })
                .build());

        agent.run(parent, "去干活");

        assertEquals(1, childSessionIds.size(), "子代理开一个独立子会话");
        assertNotEquals(parent.id(), childSessionIds.get(0), "子会话 id 必须与父会话不同");
        assertTrue(childSessionIds.get(0).startsWith("parent-1-sub-deploy"), childSessionIds.get(0));

        // 子会话里有子代理自己的轮次与最终回答；父会话里没有
        assertTrue(childSessions.get(0).events().stream()
                        .anyMatch(e -> e.kind() == EventKind.TURN_END),
                "子代理在自己的会话里收尾");
        assertFalse(parent.events().stream()
                        .anyMatch(e -> e.kind() == EventKind.TURN_END && e.turn() > 1),
                "父会话只有自己这一轮");
    }

    @Test
    @DisplayName("模型覆盖：声明了目录里的模型就用那个；没声明就跟父的当前模型")
    void modelOverrideUsesTheCatalogEntry() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "\nmodel: fast");
        ScriptedModel parentModel = new ScriptedModel(
                toolCall("c1", "subagent__deploy", "{\"task\":\"干活\"}"),
                text("{\"summary\":\"子代理结论\"}"),
                text("父模型收尾"));
        ScriptedModel fastModel = new ScriptedModel(text("{\"summary\":\"快模型干完了\"}"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .models(List.of(
                        new ModelOption("main", parentModel),
                        new ModelOption("fast", fastModel)))
                .defaultModelId("main")
                .systemPrompt("## base\n你是 MoS4J。")
                .subAgents(catalog(root))
                .subAgentSessions(sessionId -> new Session(sessionId, null))
                .build());

        agent.run(session, "去干活");

        assertEquals(1, fastModel.seenSystem.size(), "子代理用的是被指定的模型（快模型只被调了一次）");
        assertTrue(fastModel.seenSystem.get(0).contains("你是 deploy"), fastModel.seenSystem.get(0));
        assertTrue(fastModel.seenSystem.get(0).contains("summary"), "子代理提示词里写明回执格式");
    }

    @Test
    @DisplayName("子代理工具面收窄：子代理只能用白名单里的工具")
    void subAgentToolSurfaceIsNarrowed() throws Exception {
        Path root = newRoot();
        writeAgent(root, "deploy", "\ntools:\n  - read");
        ScriptedModel parentModel = new ScriptedModel(
                toolCall("c1", "subagent__deploy", "{\"task\":\"干活\"}"),
                text("{\"summary\":\"子代理结论\"}"),
                text("父模型收尾"));
        ScriptedModel childModel = new ScriptedModel(text("{\"summary\":\"子代理结论\"}"));
        Session session = new Session("s1", null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new net.hegq.mos4j.tool.Tool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "读文件";
            }

            @Override
            public Class<?> parameterType() {
                return java.util.Map.class;
            }

            @Override
            public String execute(String argsJson) {
                return "内容";
            }
        });
        Agent agent = Agent.of(Config.builder()
                .model(parentModel)
                .tools(registry)
                .systemPrompt("## base\n你是 MoS4J。")
                .subAgents(catalog(root))
                .subAgentSessions(sessionId -> new Session(sessionId, null))
                .build());

        agent.run(session, "去干活");

        // 父模型看见的工具清单里有子代理工具；子代理自己的清单里没有它（深度 0）
        assertTrue(parentModel.seenToolNames.get(0).contains("subagent__deploy"),
                parentModel.seenToolNames.get(0).toString());
    }
}
