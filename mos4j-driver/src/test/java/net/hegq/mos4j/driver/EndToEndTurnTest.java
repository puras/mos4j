package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StreamChunk;
import net.hegq.mos4j.llm.StreamEmitter;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.llm.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：一个完整 turn（读文件 → 作答）。
 *
 * <p>这是 J1 的验收主线——<b>一个 turn 的事件序列必须合法</b>：
 * {@code turn/start → user → assistant → tool/call → tool/result → assistant → turn/end}，
 * 且投影里的 assistant.toolCalls 与 tool 消息按 CallID 成对（服务商才肯收这段历史）。</p>
 */
class EndToEndTurnTest {

    /** 假模型：第一次要工具，第二次作答。 */
    private static final class ScriptedModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<Request> requests = new ArrayList<>();

        @Override
        public Response chat(Request request) {
            requests.add(request);
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new Response(
                        new Message(Role.ASSISTANT, "我先读文件", null,
                                List.of(new ToolCall("call_1", "read", "{\"path\":\"hello.txt\"}")),
                                null, null, null),
                        new Usage(10, 10, 5, 15, null),
                        "tool_calls");
            }
            return new Response(
                    Message.assistant("文件内容是：你好，世界"),
                    new Usage(20, 20, 8, 28, null),
                    "stop");
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            throw new UnsupportedOperationException("本测试走非流式");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.FUNCTION_CALLING, Capability.STREAMING);
        }
    }

    private static Path writeHelloFile() throws IOException {
        Files.createDirectories(Path.of("target", "test-tmp"));
        Path file = Path.of("target", "test-tmp", "hello.txt");
        Files.writeString(file, "你好，世界", StandardCharsets.UTF_8);
        return file;
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("read", "读取文件", null, args -> {
            var node = new tools.jackson.databind.ObjectMapper().readTree(args);
            String path = node.path("path").asText();
            Path target = Path.of("target", "test-tmp", path);
            return Files.readString(target, StandardCharsets.UTF_8);
        }));
        return registry;
    }

    @Test
    void readFileThenAnswer() throws IOException {
        writeHelloFile();
        ScriptedModel model = new ScriptedModel();
        Agent agent = Agent.of(Config.builder().model(model).tools(registry()).build());
        Session session = new Session("s1", null);

        String answer = agent.run(session, "读一下 hello.txt");

        assertEquals("文件内容是：你好，世界", answer);

        List<EventKind> kinds = session.events().stream().map(Event::kind).toList();
        assertEquals(List.of(EventKind.TURN_START, EventKind.USER, EventKind.ASSISTANT,
                EventKind.TOOL_CALL, EventKind.TOOL_RESULT, EventKind.ASSISTANT, EventKind.TURN_END), kinds,
                "事件序列必须合法（冻结）");

        // 投影：assistant 的工具调用与 tool 消息按 CallID 成对
        List<Message> messages = session.messages();
        assertEquals(4, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals("call_1", messages.get(1).toolCalls().get(0).id());
        assertEquals(Role.TOOL, messages.get(2).role());
        assertEquals("call_1", messages.get(2).toolCallId());
        assertEquals("你好，世界", messages.get(2).content());
        assertEquals(Role.ASSISTANT, messages.get(3).role());

        // 第二轮请求带上了工具结果（历史由投影派生）
        assertEquals(2, model.requests.size());
        assertTrue(model.requests.get(1).messages().stream()
                .anyMatch(m -> m.role() == Role.TOOL && "你好，世界".equals(m.content())));
    }

    @Test
    void missingToolRegistryFailsClosedWithoutAssistantEvent() {
        ScriptedModel model = new ScriptedModel();
        Agent agent = Agent.of(Config.builder().model(model).build());
        Session session = new Session("s1", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> agent.run(session, "读一下 hello.txt"));

        assertTrue(error.getMessage().contains("未装配"), error.getMessage());

        List<EventKind> kinds = session.events().stream().map(Event::kind).toList();
        assertFalse(kinds.contains(EventKind.ASSISTANT),
                "能力缺口 fail-closed 且不写 assistant 事件（否则日志里留下无 tool/result 的非法历史）");
        assertEquals(EventKind.TURN_END, kinds.get(kinds.size() - 1));
        assertEquals("error", session.events().get(kinds.size() - 1).reason());
    }

    @Test
    void describeStatesWhatIsNotInstalled() {
        Agent agent = Agent.of(Config.builder().model(new ScriptedModel()).tools(registry()).build());

        var describe = agent.describe();

        assertEquals("MoS4J", describe.framework());
        assertTrue(describe.tools().contains("read"));
        assertTrue(describe.caveats().stream().anyMatch(c -> c.contains("取消是协作式")),
                "降级必须写进快照：" + describe.caveats());
        assertTrue(describe.caveats().stream().anyMatch(c -> c.contains("未装配守卫")),
                "未装配的守卫必须写进快照：" + describe.caveats());
        assertTrue(describe.caveats().stream().anyMatch(c -> c.contains("未装配钩子")),
                "未装配的钩子必须写进快照：" + describe.caveats());
        assertTrue(describe.caveats().stream().anyMatch(c -> c.contains("未装配审批通道")),
                "未装配的审批通道必须写进快照：" + describe.caveats());
    }
}
