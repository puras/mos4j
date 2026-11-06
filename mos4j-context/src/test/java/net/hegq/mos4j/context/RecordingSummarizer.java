package net.hegq.mos4j.context;

import net.hegq.mos4j.llm.model.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身：记录摘要器收到的输入，并返回固定摘要。
 *
 * <p>压缩流程（触发、切割、落库）必须能在**不调模型**的情况下测——
 * 否则每个用例都要起一个假 HTTP 服务，慢且脆。
 */
final class RecordingSummarizer implements SummaryGenerator {

    /** 收到的对话文本（按次）。 */
    final List<String> transcripts = new ArrayList<>();

    /** 收到的上一轮摘要（按次）。 */
    final List<String> previousSummaries = new ArrayList<>();

    /** 返回的摘要文本。 */
    String text = "摘要内容";

    /** 返回的用量。 */
    Usage usage = new Usage(100, 100, 20, 120, null);

    /** 抛异常时的替身行为（null = 不抛）。 */
    RuntimeException failure;

    @Override
    public Summary generate(String transcript, String previousSummary) {
        transcripts.add(transcript);
        previousSummaries.add(previousSummary);
        if (failure != null) {
            throw failure;
        }
        return new Summary(text, usage);
    }
}
