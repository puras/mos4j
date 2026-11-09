package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.context.CompactionPolicy;
import net.hegq.mos4j.context.CompactionService;
import net.hegq.mos4j.context.SpillStore;
import net.hegq.mos4j.context.SummaryGenerator;
import net.hegq.mos4j.context.ToolOutputLimiter;
import net.hegq.mos4j.context.Truncation;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.FunctionTool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J4 上下文工程在真实链路里的接线：输出限流、落盘逃生通道、压缩触发。
 */
class ContextFlowTest {

    /** 假模型：第一次要工具，第二次作答。 */
    private static final class ScriptedModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final String toolName;

        ScriptedModel(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public Response chat(Request request) {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new Response(
                        new Message(Role.ASSISTANT, "我先执行", null,
                                List.of(new ToolCall("call_1", toolName, "{}")),
                                null, null, null),
                        new Usage(10, 10, 5, 15, null),
                        "tool_calls");
            }
            return new Response(Message.assistant("好的"), new Usage(20, 20, 8, 28, null), "stop");
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
            return Set.of(Capability.FUNCTION_CALLING);
        }
    }

    private static ToolRegistry registry(String toolName, String output) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of(toolName, "产出大输出", null, args -> output));
        return registry;
    }

    @Test
    @DisplayName("装配限流器：工具输出按行数截断，标记进 tool/result")
    void toolOutputIsTruncatedInTheRealChain() throws IOException {
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10"))
                .outputLimiter(ToolOutputLimiter.of(
                        Truncation.builder().maxLines(3).maxBytes(100_000).build(), null))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "跑一下");

        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        assertTrue(result.toolResult().content().contains("已截断"),
                result.toolResult().content());
        assertTrue(result.toolResult().content().startsWith("1\n2\n3"),
                result.toolResult().content());
    }

    @Test
    @DisplayName("未装配限流器：工具输出原样进日志（零开销降级）")
    void withoutLimiterTheOutputIsUntouched() throws IOException {
        String output = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", output))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "跑一下");

        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        assertEquals(output, result.toolResult().content());
    }

    @Test
    @DisplayName("装配落盘通道：完整内容落盘，回执里给出路径")
    void spilledOutputPointsToTheFile() throws IOException {
        Path base = Files.createDirectories(
                Path.of("target", "test-tmp", "driver-spill-" + System.nanoTime()).toAbsolutePath());
        Files.createDirectories(base.resolve("spill"));
        SandboxedFs fs = new SandboxedFs(RootSet.of(base));
        SpillStore spill = SpillStore.of(fs, "spill");
        String output = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";

        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", output))
                .outputLimiter(ToolOutputLimiter.of(
                        Truncation.builder().maxLines(3).maxBytes(100_000).build(), spill))
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "跑一下");

        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT).findFirst().orElseThrow();
        String content = result.toolResult().content();
        assertTrue(content.contains("完整输出已落盘"), content);
        assertTrue(content.contains("spill/"), content);
        // 完整内容确实在文件里
        String path = content.substring(content.indexOf("路径：") + "路径：".length())
                .split("\\R")[0].trim();
        Path written = base.resolve(path);
        assertTrue(Files.exists(written), written.toString());
        assertEquals(output, Files.readString(written));
    }

    @Test
    @DisplayName("装配压缩：超预算时先压缩再问模型，旧历史只遮蔽不删日志")
    void compactionRunsBeforeTheModelCall() throws IOException {
        SummaryGenerator generator = (transcript, previousSummary) ->
                new SummaryGenerator.Summary("摘要", new Usage(10, 10, 5, 15, null));
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "输出"))
                .compaction(new CompactionService(CompactionPolicy.of(100, 0, 0), generator))
                .build());
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("很长的历史".repeat(200)).build());

        agent.run(session, "跑一下");

        assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.COMPACTION),
                "压缩事件要落日志");
        Event compaction = session.events().stream()
                .filter(e -> e.kind() == EventKind.COMPACTION).findFirst().orElseThrow();
        assertEquals(1, compaction.turn(), "压缩事件归属进行中的那一轮");
        assertTrue(session.messages().get(0).content().startsWith("[历史摘要]"),
                session.messages().get(0).content());
        assertTrue(session.messages().stream().noneMatch(m -> m.content() != null
                        && m.content().contains("很长的历史")),
                "旧历史不再投影（但仍在日志里）");
        assertTrue(session.events().stream().anyMatch(e -> e.kind() == EventKind.USER
                && e.text().contains("很长的历史")), "旧历史仍在日志里");
    }

    @Test
    @DisplayName("压缩失败：本轮以 turn/end{error} 收尾再抛（不留孤儿 turn）")
    void compactionFailureClosesTheTurn() throws IOException {
        SummaryGenerator failing = (transcript, previousSummary) -> {
            throw new IllegalStateException("模型挂了");
        };
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "输出"))
                .compaction(new CompactionService(CompactionPolicy.of(100, 0, 0), failing))
                .build());
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("很长的历史".repeat(200)).build());

        assertThrows(IllegalStateException.class, () -> agent.run(session, "跑一下"));

        List<EventKind> kinds = session.events().stream().map(Event::kind).toList();
        assertTrue(kinds.contains(EventKind.TURN_END), kinds.toString());
        Event end = session.events().stream()
                .filter(e -> e.kind() == EventKind.TURN_END).findFirst().orElseThrow();
        assertEquals("error", end.reason());
        assertFalse(kinds.contains(EventKind.COMPACTION), "压缩失败不能留下压缩事件");
    }

    @Test
    @DisplayName("压缩失败且策略为跳过并继续：本轮正常收尾，宿主收到一次通知")
    void compactionFailureCanSkipAndContinue() throws IOException {
        SummaryGenerator failing = (transcript, previousSummary) -> {
            throw new IllegalStateException("模型挂了");
        };
        java.util.List<Throwable> notified = new java.util.ArrayList<>();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "输出"))
                .compaction(new CompactionService(CompactionPolicy.of(100, 0, 0), failing))
                .compactionFailurePolicy(
                        net.hegq.mos4j.context.CompactionFailurePolicy.SKIP_AND_CONTINUE)
                .onCompactionFailure(notified::add)
                .build());
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("很长的历史".repeat(200)).build());

        String answer = agent.run(session, "跑一下");

        assertEquals("好的", answer, "跳过压缩后本轮继续走完");
        assertEquals(1, notified.size(), "宿主通知恰好一次");
        assertTrue(notified.get(0).getMessage().contains("摘要生成异常"), notified.get(0).getMessage());
        List<EventKind> kinds = session.events().stream().map(Event::kind).toList();
        assertFalse(kinds.contains(EventKind.COMPACTION), "压缩失败不能留下压缩事件");
        Event end = session.events().stream()
                .filter(e -> e.kind() == EventKind.TURN_END).findFirst().orElseThrow();
        assertEquals("completed", end.reason(), "本轮正常收尾，不是 error");
    }

    @Test
    @DisplayName("压缩摘要超时：按 fail-closed 收尾（本轮以 turn/end{error} 结束）")
    void compactionSummaryTimeoutFailsClosed() throws IOException {
        SummaryGenerator hanging = (transcript, previousSummary) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return new SummaryGenerator.Summary("摘要", new Usage(1, 1, 1, 2, null));
        };
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "输出"))
                .compaction(new CompactionService(
                        CompactionPolicy.of(100, 0, 0), hanging, java.time.Duration.ofMillis(50)))
                .build());
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("很长的历史".repeat(200)).build());

        assertThrows(IllegalStateException.class, () -> agent.run(session, "跑一下"));

        List<EventKind> kinds = session.events().stream().map(Event::kind).toList();
        assertTrue(kinds.contains(EventKind.TURN_END), kinds.toString());
        Event end = session.events().stream()
                .filter(e -> e.kind() == EventKind.TURN_END).findFirst().orElseThrow();
        assertEquals("error", end.reason());
        assertFalse(kinds.contains(EventKind.COMPACTION), "超时不能留下压缩事件");
    }

    @Test
    @DisplayName("Describe 声明限流与压缩装没装（降级必须写进快照）")
    void describeDeclaresTheLimiterAndCompaction() {
        SummaryGenerator generator = (transcript, previousSummary) ->
                new SummaryGenerator.Summary("摘要", null);
        Agent installed = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .tools(registry("read", "输出"))
                .outputLimiter(ToolOutputLimiter.of(Truncation.defaultPolicy(), null))
                .compaction(new CompactionService(CompactionPolicy.of(100, 0, 0), generator))
                .build());

        assertTrue(installed.describe().features().stream().anyMatch(f -> f.contains("工具输出限流")),
                installed.describe().features().toString());
        assertTrue(installed.describe().features().stream().anyMatch(f -> f.contains("上下文压缩")),
                installed.describe().features().toString());
        assertTrue(installed.describe().caveats().stream().anyMatch(c -> c.contains("未装配落盘通道")),
                installed.describe().caveats().toString());

        Agent bare = Agent.of(Config.builder()
                .model(new ScriptedModel("read"))
                .build());

        assertTrue(bare.describe().caveats().stream().anyMatch(c -> c.contains("未装配输出限流")),
                bare.describe().caveats().toString());
        assertTrue(bare.describe().caveats().stream().anyMatch(c -> c.contains("未装配上下文压缩")),
                bare.describe().caveats().toString());
    }
}
