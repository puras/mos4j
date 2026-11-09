package net.hegq.mos4j.context;

import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.Message;

import java.util.List;

/**
 * 用模型生成摘要的默认实现。
 *
 * <p><b>不传工具定义</b>：摘要器只要写摘要。给了工具它就会去调——
 * 摘要这一步的输出必须是文本，工具调用会让「摘要」变成一次新的任务执行。
 *
 * <p><b>指令是冻结的</b>：「不要续写对话」「不要调用工具」这两句是安全语义，
 * 不是措辞偏好——摘要器最容易犯的错就是接着历史往下写，
 * 于是「历史」里混进了它自己编的内容，下一轮模型分不清哪句是真话。
 */
public final class ChatModelSummaryGenerator implements SummaryGenerator {

    /** 摘要器的系统指令（冻结）。 */
    public static final String SYSTEM_PROMPT = """
            你是对话摘要器。把给定的历史对话压缩成一份事实性摘要，供后续对话继续使用。

            规则：
            1. 只输出摘要本身。不要续写对话，不要扮演对话里的任何角色，不要问问题。
            2. 不要调用工具，不要请求工具。
            3. 必须保留：用户的明确要求、已作出的决定与理由、已确认的事实、尚未完成的事项、
               文件路径与标识符、错误信息与工具返回的关键结果。
            4. 可以丢弃：寒暄、重复内容、与任务无关的中间推理。
            5. 用与对话相同的语言输出，控制在 600 字以内。
            """;

    private final ChatModel model;

    /**
     * @param model 用于生成摘要的模型
     */
    public ChatModelSummaryGenerator(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("context: 摘要生成器需要模型");
        }
        this.model = model;
    }

    @Override
    public Summary generate(String transcript, String previousSummary) {
        Request request = Request.of(List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user(transcript == null ? "" : transcript)));
        // 注意：不传 tools —— 摘要器不调用工具
        Response response = model.chat(request);
        String text = response == null || response.message() == null
                ? null : response.message().content();
        return new Summary(text, response == null ? null : response.usage());
    }
}
