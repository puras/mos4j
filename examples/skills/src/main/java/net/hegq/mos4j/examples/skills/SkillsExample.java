package net.hegq.mos4j.examples.skills;

import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.tool.skill.Skill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * MoS4J 技能示例：把一处技能目录接进来，模型按清单决定用哪个技能，正文按需现读。
 *
 * <p><b>装配只有三行关键代码</b>（技能目录 + 目录列表 + 系统提示词），其余都是宿主的活：
 * <ul>
 *   <li><b>清单</b>（name / description）在装配期渲染进静态系统提示词——进门就看得见，
 *       且不随调用变化（保 prompt cache）；</li>
 *   <li><b>正文不进提示词</b>：模型调 {@code skill} 工具时才从磁盘现读
 *       （改完正文下次调用即生效）；</li>
 *   <li><b>宿主手势</b>：{@code /deploy 参数} 这种写法由宿主就地展开正文，
 *       不经过模型判断。</li>
 * </ul>
 *
 * <p><b>技能目录列表就是授权边界</b>：框架只读这里声明的目录，别的一概不碰。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * export OPENAI_MODEL=gpt-4o-mini
 * java -cp ... net.hegq.mos4j.examples.skills.SkillsExample "帮我把 app 部署到测试环境"
 * java -cp ... net.hegq.mos4j.examples.skills.SkillsExample "/deploy app 测试环境"
 * </pre>
 */
public final class SkillsExample {

    /** 技能目录（相对本示例的工作目录）。 */
    public static final Path SKILL_DIR = Path.of("skills");

    /** 系统提示词：先看清单，有对口的技能就读正文。 */
    public static final String SYSTEM_PROMPT =
            "你是一个部署助手。动手之前先看技能清单里有没有对口的技能；"
                    + "有就先读它的正文，按里面写的步骤做。";

    private SkillsExample() {
    }

    /**
     * 入口。
     *
     * @param args 第一个参数是问题（缺省问部署）
     * @throws IOException 会话读写失败
     */
    public static void main(String[] args) throws IOException {
        Mos4j mos4j = assemble(Path.of("data"), null);
        printSkills(mos4j);

        String question = args.length > 0 && !args[0].isBlank() ? args[0] : "帮我把 app 部署到测试环境";
        String input = question;
        // 宿主侧手势：/deploy ... 直接就地展开正文（模型不必先调 skill 工具）。
        if (question.strip().startsWith("/")) {
            var expansion = mos4j.agent().expandSkill(question);
            if (expansion.expanded()) {
                System.out.println("[技能手势] 已展开正文");
                input = expansion.text();
            }
        }
        System.out.println(mos4j.run("skills", input));
    }

    /**
     * 装配（模型由门面按协议 + 环境变量装）。
     *
     * @param logDir 会话日志目录
     * @param model  模型（可空 = 用环境变量装）
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model) throws IOException {
        Mos4j.Builder builder = Mos4j.builder()
                .logDir(logDir)
                .skills(SKILL_DIR)
                .systemPrompt(SYSTEM_PROMPT);
        if (model != null) {
            builder.model(model);
        }
        if ("1".equals(System.getenv("MOS_NO_PATH_SCOPE"))) {
            builder.noPathScope();
        }
        return builder.build();
    }

    /**
     * 装配（模型由宿主给，用例走这条）。
     *
     * @param logDir 会话日志目录
     * @param model  模型
     * @param skillDir 技能目录
     * @return 装好的门面
     * @throws IOException 会话日志目录不可用
     */
    public static Mos4j assemble(Path logDir, ChatModel model, Path skillDir) throws IOException {
        return Mos4j.builder()
                .model(model)
                .logDir(logDir)
                .noPathScope()
                .skills(skillDir)
                .systemPrompt(SYSTEM_PROMPT)
                .build();
    }

    /** 打印技能清单（Describe 之外的另一次人话视图）。 */
    private static void printSkills(Mos4j mos4j) {
        System.out.println("技能清单：");
        for (Skill skill : mos4j.agent().skills()) {
            System.out.println("  - " + skill.name() + "：" + skill.description());
        }
    }

    /** 技能名清单。 */
    public static List<String> names(Mos4j mos4j) {
        return mos4j.agent().skills().stream().map(Skill::name).toList();
    }
}
