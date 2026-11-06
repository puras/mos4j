package net.hegq.mos4j.context;

import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StreamEmitter;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.5/T4.6 摘要生成与旧摘要折叠（L2 场景 L2-12）。
 */
class PreviousSummaryTest {

    @Test
    @DisplayName("L2-12 连续两轮压缩：上一轮摘要折叠进新摘要，不滚雪球")
    void previousSummaryIsFoldedNotAccumulated() throws IOException {
        Session session = new Session("s1", null);
        session.append(Event.builder(EventKind.USER).text("第一轮问题").build());
        session.append(Event.builder(EventKind.ASSISTANT)
                .text("第一轮回答")
                .usage(new Usage(500, 500, 50, 550, null))
                .build());

        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.text = "第一轮摘要";
        CompactionService service = new CompactionService(
                CompactionPolicy.of(400, 0, 0), summarizer);

        CompactionResult first = service.maybeCompact(session);
        assertTrue(first.compacted(), first.warnings().toString());
        assertEquals("第一轮摘要", first.summary());

        // 第二轮：再堆一点历史，继续超预算
        session.append(Event.builder(EventKind.USER).text("第二轮问题").build());
        session.append(Event.builder(EventKind.ASSISTANT)
                .text("第二轮回答")
                .usage(new Usage(500, 500, 50, 550, null))
                .build());

        summarizer.text = "第二轮摘要";
        CompactionResult second = service.maybeCompact(session);
        assertTrue(second.compacted(), second.warnings().toString());

        assertEquals(2, summarizer.transcripts.size());
        String secondTranscript = summarizer.transcripts.get(1);
        assertEquals("第一轮摘要", summarizer.previousSummaries.get(1),
                "上一轮摘要要单独传给摘要器");
        assertTrue(secondTranscript.contains("<previous-summary>"), secondTranscript);
        assertTrue(secondTranscript.contains("第一轮摘要"), secondTranscript);
        // 旧摘要不能又以对话行的身份出现一遍
        assertFalse(secondTranscript.contains("[User]: 第一轮摘要"), secondTranscript);
        assertTrue(secondTranscript.contains("[User]: 第二轮问题"), secondTranscript);
    }

    @Test
    @DisplayName("摘要输入格式是冻结的：标签固定，外层 <conversation> 包裹")
    void transcriptFormatIsFrozen() {
        String rendered = Transcript.render(List.of(), null);

        assertTrue(rendered.startsWith("<conversation>"), rendered);
        assertTrue(rendered.endsWith("</conversation>"), rendered);
    }

    @Test
    @DisplayName("摘要器不续写、不调工具：请求里没有工具定义")
    void summaryRequestCarriesNoTools() {
        CapturingModel model = new CapturingModel();
        ChatModelSummaryGenerator generator = new ChatModelSummaryGenerator(model);

        generator.generate("<conversation>\n[User]: 你好\n</conversation>", null);

        assertNull(model.request.tools(), "摘要请求不允许带工具定义");
        assertTrue(model.request.messages().get(0).content().contains("不要续写对话"));
        assertTrue(model.request.messages().get(0).content().contains("不要调用工具"));
    }

    /** 记录请求的假模型。 */
    private static final class CapturingModel implements ChatModel {
        Request request;

        @Override
        public Response chat(Request request) {
            this.request = request;
            return new Response(Message.assistant("摘要"), new Usage(10, 10, 5, 15, null), null);
        }

        @Override
        public Response chat(Request request, net.hegq.mos4j.core.CancellationToken cancellation) {
            return chat(request);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter,
                                   net.hegq.mos4j.core.CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Set<Capability> capabilities() {
            return java.util.Set.of();
        }
    }
}
