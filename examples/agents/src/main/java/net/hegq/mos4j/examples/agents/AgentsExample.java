package net.hegq.mos4j.examples.agents;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.session.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MoS4J 多智能体示例：拆解 → 执行 → 验收。
 *
 * <p><b>这一例演示的事</b>：MoS4J 给的是**一个能跑的智能体**，多智能体是宿主用几个
 * 智能体拼出来的编排——框架不替你定「有几个角色、怎么分工」，那是业务的事。
 *
 * <p><b>每个角色一个会话</b>：拆解的中间过程、执行的动作、验收的意见各在各的日志里，
 * 互不污染；宿主拿到的只是「上一环的结果」这一段文本。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.agents.AgentsExample "写一篇项目周报"
 * </pre>
 */
public final class AgentsExample {

    /** 拆解专家的提示词。 */
    public static final String PLANNER_PROMPT =
            "你是拆解专家。把任务拆成 3 到 5 条可执行的子任务，每条一行，不要解释。";

    /** 执行专家的提示词。 */
    public static final String WORKER_PROMPT =
            "你是执行专家。按给定的子任务清单逐条完成，产出可核对的结果。";

    /** 验收专家的提示词。 */
    public static final String REVIEWER_PROMPT =
            "你是验收专家。检查结果是否覆盖了全部子任务，指出缺漏；没问题就明确说通过。";

    private AgentsExample() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数是任务（缺省写一篇项目周报）
     * @throws IOException 会话读写失败
     */
    public static void main(String[] args) throws IOException {
        String task = args.length > 0 && !args[0].isBlank() ? args[0] : "写一篇项目周报";
        ChatModel model = net.hegq.mos4j.sdk.Mos4j.defaultModel(
                net.hegq.mos4j.sdk.Mos4j.Protocol.OPENAI, null, null, null, null);

        System.out.println(orchestrate(model, task, null));
    }

    /**
     * 跑一遍三角色编排（用例走这条：不碰网络）。
     *
     * @param model   模型
     * @param task    任务
     * @param logDir  会话日志目录（可空 = 只在内存里）
     * @return 验收意见
     * @throws IOException 会话读写失败
     */
    public static String orchestrate(ChatModel model, String task, Path logDir) throws IOException {
        Agent planner = agent(model, PLANNER_PROMPT);
        Agent worker = agent(model, WORKER_PROMPT);
        Agent reviewer = agent(model, REVIEWER_PROMPT);

        String plan = planner.run(session("planner", logDir), task);
        String result = worker.run(session("worker", logDir), plan);
        String verdict = reviewer.run(session("reviewer", logDir), result);

        System.out.println("[拆解]\n" + plan);
        System.out.println("[执行]\n" + result);
        System.out.println("[验收]\n" + verdict);
        return verdict;
    }

    /** 造一个角色（同一套机制，只是提示词与工具面不同）。 */
    private static Agent agent(ChatModel model, String systemPrompt) {
        return Agent.of(Config.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .build());
    }

    /** 每个角色一个会话：日志互不污染。 */
    private static Session session(String id, Path logDir) throws IOException {
        if (logDir == null) {
            return new Session(id, null);
        }
        Files.createDirectories(logDir);
        return new net.hegq.mos4j.session.JsonlPersistence(logDir).open(logDir, id);
    }

    /** 角色清单（快照里看得见有几个角色在跑）。 */
    public static List<String> roles() {
        return List.of("planner", "worker", "reviewer");
    }
}
