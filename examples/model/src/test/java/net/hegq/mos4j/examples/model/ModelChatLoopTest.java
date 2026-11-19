package net.hegq.mos4j.examples.model;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.model.StatusError;
import net.hegq.mos4j.model.chat.Capability;
import net.hegq.mos4j.model.chat.ChatModel;
import net.hegq.mos4j.model.chat.Message;
import net.hegq.mos4j.model.chat.Request;
import net.hegq.mos4j.model.chat.Response;
import net.hegq.mos4j.model.chat.Role;
import net.hegq.mos4j.model.chat.StreamEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环对话的用例：历史怎么攒、失败怎么不退化成半轮、命令词怎么走。
 *
 * <p>全部跑在脚本化替身 {@link StubModel} 上，<b>不碰网络</b>；输入输出都是注入的流，
 * 不需要真终端。
 */
class ModelChatLoopTest {

    // ------------------------------------------------------------ 历史归宿主

    @Test
    @DisplayName("循环对话：每轮把整段历史带上，模型看见的轮数一次比一次多")
    void eachRoundCarriesTheWholeHistory() {
        StubModel model = StubModel.of(
                StubModel.text("第一答"),
                StubModel.text("第二答"),
                StubModel.text("第三答"));
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);

        assertEquals("第一答", loop.ask(model, "第一问"));
        assertEquals("第二答", loop.ask(model, "第二问"));
        assertEquals("第三答", loop.ask(model, "第三问"));

        assertEquals(3, model.requests().size(), "三次问答 = 三次独立调用");
        List<Message> first = model.requests().get(0).messages();
        assertEquals(List.of(Role.SYSTEM, Role.USER), roles(first), "第一轮：system + user");

        List<Message> second = model.requests().get(1).messages();
        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), roles(second),
                "第二轮带回了上一轮的问答");
        assertEquals("第一答", second.get(2).content(), "带回去的是原样的助手回答");

        List<Message> third = model.requests().get(2).messages();
        assertEquals(6, third.size(), "第三轮请求：system + 前两轮问答 + 这次提问");
        assertEquals("第三问", third.get(5).content());
        assertEquals(7, loop.history().size(), "回答之后历史里才是 system + 3 轮 × 2 条");
    }

    @Test
    @DisplayName("历史是副本：外部改不动内部状态，轮数按提问算")
    void historyIsACopyAndRoundsCountQuestions() {
        StubModel model = StubModel.of(StubModel.text("答"));
        ModelChatLoop loop = new ModelChatLoop("系统提示词");

        loop.ask(model, "问");

        assertEquals(3, loop.history().size());
        assertEquals(1, loop.rounds(), "一轮问答 = 一个用户提问");
        assertThrows(UnsupportedOperationException.class,
                () -> loop.history().add(Message.user("偷偷加一条")),
                "拿到的历史是只读副本");
        assertEquals(3, loop.history().size(), "内部状态没被改");
    }

    @Test
    @DisplayName("清空历史：system 留着（它是装配的一部分，不是对话的一部分）")
    void resetKeepsTheSystemPrompt() {
        StubModel model = StubModel.of(StubModel.text("答"), StubModel.text("又问一次"));
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);
        loop.ask(model, "问");
        assertEquals(3, loop.history().size());

        loop.reset();

        assertEquals(1, loop.history().size(), "只剩系统提示词");
        assertEquals(Role.SYSTEM, loop.history().get(0).role());
        assertEquals(0, loop.rounds());

        loop.ask(model, "又问一次");
        assertEquals(List.of(Role.SYSTEM, Role.USER), roles(model.requests().get(1).messages()),
                "清空后再问，历史是干净的一段");
    }

    @Test
    @DisplayName("空提示词就不带 system 消息（不是带一条空的）")
    void blankSystemPromptIsOmitted() {
        StubModel model = StubModel.of(StubModel.text("答"));
        ModelChatLoop loop = new ModelChatLoop("   ");

        loop.ask(model, "问");

        assertEquals(List.of(Role.USER), roles(model.requests().get(0).messages()),
                "空提示词不该变成一条空 content 的 system 消息");
    }

    // ---------------------------------------------------------- 失败不退化成半轮

    @Test
    @DisplayName("这一轮失败：历史不变（问题不进历史，宿主可以原样重问）")
    void failedRoundLeavesHistoryUntouched() {
        StubModel model = StubModel.of(
                new StatusError("openai", 503, "服务端炸了"),
                StubModel.text("这次成了"));
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);

        assertThrows(StatusError.class, () -> loop.ask(model, "会失败的问题"));
        assertEquals(1, loop.history().size(), "失败的一轮没有留下半条记录（只剩 system）");

        assertEquals("这次成了", loop.ask(model, "会失败的问题"));
        assertEquals(List.of(Role.SYSTEM, Role.USER), roles(model.requests().get(1).messages()),
                "重问时历史干净：只有一个 user，没有重复的问题");
        assertEquals(3, loop.history().size());
    }

    @Test
    @DisplayName("空正文不算一轮：抛错且不追加（空 assistant 消息会把下一轮请求弄脏）")
    void blankAnswerIsNotARound() {
        StubModel model = StubModel.of(StubModel.text("   "));
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> loop.ask(model, "问"));

        assertTrue(error.getMessage().contains("空正文"), error.getMessage());
        assertEquals(1, loop.history().size(), "历史没变");
    }

    @Test
    @DisplayName("空问题直接拒绝：不发空消息也不改历史")
    void blankQuestionIsRefused() {
        StubModel model = StubModel.of(StubModel.text("答"));
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);

        assertThrows(IllegalStateException.class, () -> loop.ask(model, "  "));

        assertTrue(model.requests().isEmpty(), "压根没发请求");
        assertEquals(1, loop.history().size());
    }

    @Test
    @DisplayName("流式循环：增量到达宿主，历史照样攒（不支持流式则 fail-closed）")
    void streamingLoopRecordsHistoryToo() {
        StubModel inner = StubModel.of(StubModel.text("流式回答"));
        ChatModel streaming = new StreamingStub(inner);
        ModelChatLoop loop = new ModelChatLoop(ModelConfig.SYSTEM_PROMPT);
        StringBuilder deltas = new StringBuilder();

        String answer = loop.askStream(streaming, "问", chunk -> {
            if (chunk.delta() != null) {
                deltas.append(chunk.delta());
            }
        });

        assertEquals("流式回答", answer);
        assertEquals("流式回答", deltas.toString(), "增量真的到了宿主给的 emitter");
        assertEquals(3, loop.history().size(), "流式的这一轮也进了历史");
        assertEquals(Role.ASSISTANT, loop.history().get(2).role());

        StubModel noStream = StubModel.of(StubModel.text("不该被看到"));
        assertThrows(IllegalStateException.class,
                () -> new ModelChatLoop(ModelConfig.SYSTEM_PROMPT)
                        .askStream(noStream, "问", chunk -> { }));
    }

    // ------------------------------------------------------------- 主程序的循环

    @Test
    @DisplayName("交互循环：读几行问几次，命令词生效，EOF 收尾（不碰真终端）")
    void interactiveLoopHandlesCommandsAndEof() throws IOException {
        StubModel model = StubModel.of(
                StubModel.text("答一"),
                StubModel.text("答二"),
                StubModel.text("答三"));
        String input = String.join("\n",
                "问一",
                "",
                ModelConfig.CMD_HISTORY,
                "问二",
                ModelConfig.CMD_RESET,
                ModelConfig.CMD_HISTORY,
                "问三",
                ModelConfig.CMD_EXIT,
                "这行不该被读到") + "\n";
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int rounds = ModelExample.runLoop(model, reader(input), out(sink));

        assertEquals(3, rounds, "三个真问题 = 三轮");
        assertEquals(3, model.requests().size());
        String printed = sink.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("答一") && printed.contains("答三"), printed);
        assertTrue(printed.contains("[已清空]"), "reset 命令有回执: " + printed);
        assertTrue(printed.contains("[历史] 3 条消息，1 轮问答"), "history 命令报条数: " + printed);
        assertFalse(printed.contains("这行不该被读到"), "退出命令之后不再读输入: " + printed);
    }

    @Test
    @DisplayName("交互循环：单轮失败不带走整个进程（打印原因，继续读下一行）")
    void oneFailedRoundDoesNotEndTheLoop() throws IOException {
        StubModel model = StubModel.of(
                new StatusError("openai", 500, "服务端炸了"),
                StubModel.text("这一轮好了"));
        String input = "会失败的问题\n再问一次\n" + ModelConfig.CMD_QUIT + "\n";
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int rounds = ModelExample.runLoop(model, reader(input), out(sink));

        assertEquals(1, rounds, "失败那轮不算完成轮");
        String printed = sink.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("[这一轮失败]"), printed);
        assertTrue(printed.contains("服务端炸了"), "失败原因要透出来: " + printed);
        assertTrue(printed.contains("这一轮好了"), "失败之后的这一轮照常跑: " + printed);
    }

    @Test
    @DisplayName("交互循环：读到 EOF 就收尾（Ctrl+D 不该抛异常）")
    void eofEndsTheLoopQuietly() throws IOException {
        StubModel model = StubModel.of(StubModel.text("答"));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int rounds = ModelExample.runLoop(model, reader("问一\n"), out(sink));

        assertEquals(1, rounds);
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("[结束]"),
                "EOF 也要有收尾回执");
    }

    @Test
    @DisplayName("交互循环：流式模式下增量真的到了注入的 out，且不再重复正文")
    void interactiveLoopStreamsDeltasIntoTheGivenStream() throws IOException {
        StubModel inner = StubModel.of(StubModel.text("流式答一"), StubModel.text("流式答二"));
        ChatModel streaming = new StreamingStub(inner);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int rounds = ModelExample.runLoop(streaming, reader("问一\n问二\n" + ModelConfig.CMD_EXIT + "\n"),
                out(sink));

        assertEquals(2, rounds);
        String printed = sink.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("流式答一") && printed.contains("流式答二"), printed);
        assertEquals(1, countOccurrences(printed, "流式答一"),
                "增量打完就把正文写完了，收尾不能再打一遍: " + printed);
        assertTrue(printed.contains("[模式] 流式"), "开头要说清楚跑的哪种模式: " + printed);
    }

    @Test
    @DisplayName("交互循环：不支持流式时退回非流式（不静默换挡，开头就说清楚）")
    void loopFallsBackToNonStreamingWhenCapabilityIsMissing() throws IOException {
        // 替身不声明任何能力 = 走自动退回那一支
        StubModel model = StubModel.of(StubModel.text("非流式答"));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        int rounds = ModelExample.runLoop(model, reader("问\n" + ModelConfig.CMD_EXIT + "\n"), out(sink));

        assertEquals(1, rounds, "退回后照常完成一轮");
        assertEquals(1, model.requests().size());
        String printed = sink.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("非流式答"), printed);
        assertTrue(printed.contains("已退回非流式"), "退回要说出来: " + printed);
        assertFalse(printed.contains("[这一轮失败]"), "退回不是失败: " + printed);
    }

    // ------------------------------------------------------------------ 替身

    private static BufferedReader reader(String content) {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    private static PrintStream out(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static List<Role> roles(List<Message> messages) {
        return messages.stream().map(Message::role).toList();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /** 给替身补上流式能力位（替身本身不声明能力；包装后才能过 fail-closed 检查）。 */
    private static final class StreamingStub implements ChatModel {
        private final StubModel delegate;

        StreamingStub(StubModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response chat(Request request) {
            return delegate.chat(request);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            return delegate.chat(request, cancellation);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return delegate.chatStream(request, emitter);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return delegate.chatStream(request, emitter, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.STREAMING);
        }
    }
}
