package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.hook.GuardBus;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.policy.ToolNamePolicy;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略可见性接线的守护测试。
 *
 * <p>守护的不变式：**可见性与运行时判定读同一份名单**——
 * 被拒的工具既不进模型清单，也调不动；策略热替换时两处同时跟随。
 */
class PolicyVisibilityTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 回一句文本的模型替身，并记下它每次看到的工具名。 */
    private static final class ScriptedModel implements ChatModel {

        final List<List<String>> seenTools = new ArrayList<>();

        @Override
        public Response chat(Request request) {
            seenTools.add(request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList());
            return new Response(Message.assistant("好的"), NO_USAGE, Response.FINISH_END_TURN);
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

    /** 空参数工具。 */
    public static final class NoArgs {
    }

    private static ToolRegistry registryWith(String name) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of(name, "工具 " + name, NoArgs.class, args -> "ok"));
        return registry;
    }

    @Test
    void aDeniedToolIsNotInTheModelToolList() throws IOException {
        ScriptedModel model = new ScriptedModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registryWith("read"))
                .policy(ToolNamePolicy.of(List.of("write"), List.of()))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        assertTrue(model.seenTools.get(0).isEmpty(),
                "被策略拒的工具不进模型清单：\n" + model.seenTools.get(0));
    }

    @Test
    void anAllowedToolStaysVisible() throws IOException {
        ScriptedModel model = new ScriptedModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registryWith("read"))
                .policy(ToolNamePolicy.of(List.of("read"), List.of()))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        assertEquals(List.of("read"), model.seenTools.get(0));
    }

    @Test
    void theSamePolicyAlsoBlocksTheCallAtRuntime() {
        ToolRegistry registry = registryWith("read");
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel())
                .tools(registry)
                .policy(ToolNamePolicy.of(List.of("write"), List.of()))
                .build());

        // 可见性挡住了，但宿主点名调用仍要过守卫（模型幻觉出的名字走的是这条路）。
        assertFalse(registry.visibleNames().contains("read"));
        var decision = agent.runContext().security().guards().check(
                new net.hegq.mos4j.hook.GuardEvent("read", "{}", "c1"));
        assertFalse(decision.permits(), "执行期也要被同一份名单拦下");
        assertTrue(decision.reason().contains("策略"), "拒绝理由要说清是策略拦的");
    }

    @Test
    void aHotSwapMovesBothTheListAndTheGuard() {
        ToolRegistry registry = registryWith("read");
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel())
                .tools(registry)
                .policy(ToolNamePolicy.of(List.of("read"), List.of()))
                .build());

        assertTrue(registry.visibleNames().contains("read"));

        agent.setPolicy(ToolNamePolicy.of(List.of("write"), List.of()));

        assertFalse(registry.visibleNames().contains("read"), "换策略后清单立刻跟随");
        var decision = agent.runContext().security().guards().check(
                new net.hegq.mos4j.hook.GuardEvent("read", "{}", "c1"));
        assertFalse(decision.permits(), "换策略后守卫也立刻跟随");
    }

    @Test
    void withoutAPolicyNothingIsHidden() {
        ToolRegistry registry = registryWith("read");
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel())
                .tools(registry)
                .build());

        assertTrue(registry.visibleNames().contains("read"), "没配策略 = 不收紧（零开销降级）");
    }

    @Test
    void aHostSuppliedGuardBusStillGetsThePolicy() {
        GuardBus guards = new GuardBus();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel())
                .tools(registryWith("read"))
                .guards(guards)
                .policy(ToolNamePolicy.of(List.of("write"), List.of()))
                .build());

        assertEquals(1, guards.size(), "宿主自己的守卫 + 策略守卫都进同一条总线");
        assertTrue(agent.runContext().security().guards() == guards);
    }

    @Test
    void aDenyListAlsoHidesTheTool() throws IOException {
        ScriptedModel model = new ScriptedModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registryWith("read"))
                .policy(ToolNamePolicy.unrestricted().withDeny(List.of("read")))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        assertTrue(model.seenTools.get(0).isEmpty());
    }
}
