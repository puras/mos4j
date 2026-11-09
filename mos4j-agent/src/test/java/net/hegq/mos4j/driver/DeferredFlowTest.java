package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.7 接线：deferred 工具的激活在下一轮生效（真实 ReAct 链路）。
 */
class DeferredFlowTest {

    /** 按轮次脚本化响应的模型替身：记录每轮收到的工具清单。 */
    private static final class ScriptedModel implements ChatModel {
        private final List<Step> steps;
        private final List<List<String>> seenToolNames = new java.util.ArrayList<>();
        private final AtomicInteger round = new AtomicInteger();

        private record Step(Response response) {
        }

        ScriptedModel(List<Step> steps) {
            this.steps = steps;
        }

        @Override
        public Response chat(Request request) {
            return chat(request, (net.hegq.mos4j.core.CancellationToken) null);
        }

        @Override
        public Response chat(Request request, net.hegq.mos4j.core.CancellationToken cancellation) {
            List<String> names = request.tools() == null ? List.of()
                    : request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList();
            seenToolNames.add(names);
            return steps.get(round.getAndIncrement()).response();
        }

        @Override
        public Response chatStream(Request request, net.hegq.mos4j.model.StreamEmitter emitter) {
            return chat(request, (net.hegq.mos4j.core.CancellationToken) null);
        }

        @Override
        public Response chatStream(Request request, net.hegq.mos4j.model.StreamEmitter emitter,
                                   net.hegq.mos4j.core.CancellationToken cancellation) {
            return chat(request, cancellation);
        }

        @Override
        public java.util.Set<net.hegq.mos4j.model.Capability> capabilities() {
            return java.util.Set.of();
        }
    }

    private static final Usage NO_USAGE =
            new Usage(0, 0, 0, 0, null);

    private static Response toolCall(String id, String name, String args) {
        return new Response(
                Message.assistant("", List.of(new ToolCall(id, name, args))),
                NO_USAGE,
                "tool_calls");
    }

    private static Response text(String content) {
        return new Response(Message.assistant(content), NO_USAGE, "stop");
    }

    @Test
    @DisplayName("tool_search → activate 后，下一轮清单里出现激活的工具")
    void activationTakesEffectNextRound() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("visible", "始终可见的工具", Map.class, args -> "ok"));
        registry.registerDeferred(FunctionTool.of("late_tool", "延后加载：处理延迟任务",
                Map.class, args -> "晚到但有用"), "延后加载：处理延迟任务");

        // 第 1 轮：模型搜工具并激活；第 2 轮：模型调用激活的工具；第 3 轮：收尾
        ScriptedModel model = new ScriptedModel(List.of(
                new ScriptedModel.Step(toolCall("c1", "tool_search", "{\"query\":\"延迟任务\"}")),
                new ScriptedModel.Step(toolCall("c2", "activate", "{\"tools\":[\"late_tool\"]}")),
                new ScriptedModel.Step(toolCall("c3", "late_tool", "{}")),
                new ScriptedModel.Step(text("完成"))));

        Agent agent = Agent.of(Config.builder().model(model).tools(registry).build());
        Session session = new Session("s1", null);
        agent.run(session, "帮我处理延迟任务");

        // 第 1 轮清单：无 late_tool，有 tool_search/activate
        List<String> round1 = model.seenToolNames.get(0);
        assertFalse(round1.contains("late_tool"), "激活前不出现");
        assertTrue(round1.contains("tool_search"), round1.toString());
        // 第 2 轮清单（模型在第 1 轮调了 activate，但本轮清单纯净）：
        List<String> round2 = model.seenToolNames.get(1);
        assertFalse(round2.contains("late_tool"),
                "activate 当轮生效 = 时序错乱；下一轮才生效: " + round2);
        // 第 3 轮清单：激活的工具出现
        List<String> round3 = model.seenToolNames.get(2);
        assertTrue(round3.contains("late_tool"), "下一轮生效: " + round3);

        // late_tool 真的执行了
        assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.TOOL_CALL
                        && e.toolCall() != null && "late_tool".equals(e.toolCall().name())),
                "激活的工具被真实调用");
    }

    @Test
    @DisplayName("历史工具名并入：模型直接调未激活 deferred 工具也能执行（兜底）")
    void historicalCallIsAdmitted() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.registerDeferred(FunctionTool.of("bonus", "额外工具", Map.class,
                args -> "兜底成功"), "额外工具");

        // 第 1 轮模型直接调 bonus（清单里没有它——历史并入兜底让它能执行）；第 2 轮收尾
        ScriptedModel model = new ScriptedModel(List.of(
                new ScriptedModel.Step(toolCall("c1", "bonus", "{}")),
                new ScriptedModel.Step(text("完成"))));

        Agent agent = Agent.of(Config.builder().model(model).tools(registry).build());
        Session session = new Session("s1", null);
        agent.run(session, "直接调用");

        assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.TOOL_RESULT
                        && e.toolResult() != null
                        && e.toolResult().content().contains("兜底成功")),
                "历史并入后调用能执行");
        assertTrue(registry.list().stream().anyMatch(t -> t.name().equals("bonus")),
                "并入后进入清单（下一轮）");
    }
}
