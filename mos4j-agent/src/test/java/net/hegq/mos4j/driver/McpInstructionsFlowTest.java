package net.hegq.mos4j.driver;

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
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务器说明的注入链路（每会话一次，走 user 侧）。
 *
 * <p>守护的口径：注入一次就不重复（刷屏）、走 user 侧而不是系统提示词（保 prompt cache）、
 * 没装 MCP 时不多发消息。
 */
class McpInstructionsFlowTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 回一句文本的模型替身，顺带记下它每次看到的消息。 */
    private static final class TextModel implements ChatModel {

        final List<List<Message>> requests = new ArrayList<>();

        @Override
        public Response chat(Request request) {
            requests.add(request.messages());
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

    private static final String TEXT = "[MCP 服务器说明]\n以下说明来自已连接的 MCP 服务器（连接建立时注入一次）：\n- a: 甲服务器的说明\n";

    private static List<String> userTexts(Session session) {
        List<String> out = new ArrayList<>();
        for (Event event : session.events()) {
            if (event.kind() == EventKind.USER && event.text() != null) {
                out.add(event.text());
            }
        }
        return out;
    }

    @Test
    void instructionsAreInjectedOncePerSession() throws IOException {
        TextModel model = new TextModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .mcpInstructions(() -> TEXT)
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "第一问");
        agent.run(session, "第二问");

        List<String> userTexts = userTexts(session);
        long injected = userTexts.stream().filter(text -> text.contains("甲服务器的说明")).count();
        assertEquals(1, injected, "每会话只注入一次：\n" + userTexts);
        assertEquals(3, userTexts.size(), "两条用户输入 + 一条说明");
    }

    @Test
    void theInstructionsReachTheModelAsAUserMessage() throws IOException {
        TextModel model = new TextModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .mcpInstructions(() -> TEXT)
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        boolean found = false;
        for (Message message : model.requests.get(0)) {
            if (message.role() == net.hegq.mos4j.model.Role.USER
                    && message.content() != null && message.content().contains("甲服务器的说明")) {
                found = true;
            }
        }
        assertTrue(found, "说明要作为 user 侧消息进入模型上下文");
    }

    @Test
    void theInstructionsDoNotLeakIntoTheSystemPrompt() throws IOException {
        TextModel model = new TextModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .mcpInstructions(() -> TEXT)
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        for (Message message : model.requests.get(0)) {
            if (message.role() == net.hegq.mos4j.model.Role.SYSTEM) {
                assertFalse(message.content() != null && message.content().contains("甲服务器的说明"),
                        "说明走 user 侧：系统提示词是装配期渲染一次的静态内容（保 prompt cache）");
            }
        }
    }

    @Test
    void noInstructionsMeansNoExtraUserMessage() throws IOException {
        TextModel model = new TextModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .mcpInstructions(() -> "")
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        assertEquals(1, userTexts(session).size(), "没有说明就不多发消息");
    }

    @Test
    void withoutTheProviderNothingIsInjected() throws IOException {
        TextModel model = new TextModel();
        Agent agent = Agent.of(Config.builder().model(model).build());
        Session session = new Session("s1", null);

        agent.run(session, "你好");

        assertEquals(1, userTexts(session).size());
    }
}
