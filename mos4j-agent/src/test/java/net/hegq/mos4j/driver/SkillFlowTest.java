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
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.session.Event;
import net.hegq.mos4j.session.EventKind;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.skill.SkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * J7 技能机制的装配与真实链路测试（{@code Agent.of} + ReAct 循环）。
 *
 * <p><b>守护的不变式</b>：
 * <ul>
 *   <li><b>条件注册</b>：没有模型可见技能就不注册 {@code skill} 工具（能力即证据）；</li>
 *   <li><b>提示词位置</b>：清单落在 {@code context} 之后、{@code instructions} 之前（prompt cache）；</li>
 *   <li><b>正文走工具结果</b>：技能正文作为普通 {@code tool/result} 进上下文，不重渲染系统提示词；</li>
 *   <li><b>审计与状态</b>：激活落 {@code skill/activate} 日志，状态每轮清空、日志每轮一条。</li>
 * </ul>
 */
class SkillFlowTest {

    private static final Usage NO_USAGE = new Usage(0, 0, 0, 0, null);

    /** 按轮次脚本化响应的模型替身：记录每轮收到的工具名与系统消息。 */
    private static final class ScriptedModel implements ChatModel {

        private final List<Response> steps;
        private final List<List<String>> seenToolNames = new ArrayList<>();
        private final List<String> seenSystem = new ArrayList<>();
        private final AtomicInteger round = new AtomicInteger();

        ScriptedModel(Response... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public Response chat(Request request) {
            return chat(request, (CancellationToken) null);
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            List<String> names = request.tools() == null ? List.of()
                    : request.tools().stream().map(net.hegq.mos4j.model.Tool::name).toList();
            seenToolNames.add(names);
            seenSystem.add(request.messages().isEmpty() ? "" : String.valueOf(request.messages().get(0).content()));
            return steps.get(round.getAndIncrement());
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter) {
            return chat(request, (CancellationToken) null);
        }

        @Override
        public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
            return chat(request, cancellation);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of();
        }
    }

    private static Response toolCall(String id, String name, String args) {
        return new Response(Message.assistant("", List.of(new ToolCall(id, name, args))), NO_USAGE, "tool_calls");
    }

    private static Response text(String content) {
        return new Response(Message.assistant(content), NO_USAGE, "stop");
    }

    /** 技能目录根（项目内 test-tmp，不用系统临时目录）。 */
    private static Path newRoot() throws IOException {
        Path root = Path.of("target", "test-tmp", "skillflow-" + System.nanoTime());
        Files.createDirectories(root);
        return root;
    }

    private static void writeSkill(Path root, String dirName, String frontmatter, String body) throws IOException {
        Path dir = root.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\n" + frontmatter + "\n---\n" + body);
    }

    private static SkillCatalog catalog(Path root) {
        return SkillCatalog.load(SkillCatalog.Options.of(List.of(root.toString())));
    }

    private static String systemPrompt() {
        return "## base\n你是 MoS4J。\n\n## context\n项目背景。\n\n## instructions\n按步骤做。";
    }

    @Test
    @DisplayName("没装技能：不注册 skill 工具、提示词不动、门面方法安全降级")
    void withoutSkillsNothingChanges() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .systemPrompt(systemPrompt())
                .build());

        assertFalse(registry.visibleNames().contains("skill"), "空枪不注册");
        assertNull(agent.runContext().skillTool(), "没有技能就没有工具实例");
        assertEquals(systemPrompt(), agent.runContext().systemPrompt(), "提示词一字不改（prompt cache）");
        assertTrue(agent.skills().isEmpty());
        assertFalse(agent.reloadSkills().changed());
        assertFalse(agent.expandSkill("/deploy 参数").expanded(), "没装技能时抓手势不该炸");
    }

    @Test
    @DisplayName("技能全部仅用户可调用：仍不注册 skill 工具、不插清单段")
    void allUserOnlySkillsRegisterNothing() throws Exception {
        Path root = newRoot();
        writeSkill(root, "secret", "name: secret\ndescription: 仅用户调用\ndisable-model-invocation: true", "正文");
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        assertFalse(registry.visibleNames().contains("skill"), "模型看不见任何技能就不给工具");
        assertNull(agent.runContext().skillTool());
        assertFalse(agent.runContext().systemPrompt().contains("## skills"), "没有可见技能就不占提示词位置");
        assertEquals(1, agent.skills().size(), "技能本身仍然在（用户还能用 /secret）");
        assertTrue(agent.expandSkill("/secret").expanded(), "仅用户可调用的技能可以手势展开");
    }

    @Test
    @DisplayName("有模型可见技能：注册 skill 工具，清单插在 context 之后、instructions 之前")
    void visibleSkillRegistersToolAndPromptSection() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "正文");
        ToolRegistry registry = new ToolRegistry();
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        assertTrue(registry.visibleNames().contains("skill"), "能力即证据：有可见技能就给工具");
        assertNotNull(agent.runContext().skillTool());
        String prompt = agent.runContext().systemPrompt();
        assertTrue(prompt.contains("## skills"), prompt);
        assertTrue(prompt.indexOf("## context") < prompt.indexOf("## skills"), prompt);
        assertTrue(prompt.indexOf("## skills") < prompt.indexOf("## instructions"), prompt);
        assertTrue(prompt.contains("deploy"), "清单里有技能名: " + prompt);
    }

    @Test
    @DisplayName("模型调用 skill：正文作为 tool/result 进上下文，并落 skill/activate 事件")
    void modelLoadsSkillThroughTheLoop() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "第一步：构建");
        ToolRegistry registry = new ToolRegistry();
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "skill", "{\"name\":\"deploy\"}"),
                text("按流程做完了"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .tools(registry)
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        agent.run(session, "发布");

        // 正文进了工具结果（不是系统提示词）
        Event result = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT)
                .findFirst()
                .orElseThrow();
        assertTrue(result.toolResult().content().contains("第一步：构建"), result.toolResult().content());
        assertFalse(agent.runContext().systemPrompt().contains("第一步：构建"),
                "正文不进静态系统提示词（prompt cache）");

        // 审计事件
        Event activate = session.events().stream()
                .filter(e -> e.kind() == EventKind.SKILL_ACTIVATE)
                .findFirst()
                .orElseThrow();
        assertEquals("deploy", activate.skill().name());
        assertEquals("发布流程", activate.skill().description());
        assertEquals(root.resolve("deploy").toString(), activate.skill().dir());
        assertEquals(1, activate.turn());
    }

    @Test
    @DisplayName("仅用户可调用的技能：模型调用被拒（fail-closed），正文不外泄")
    void modelCannotLoadUserOnlySkill() throws Exception {
        Path root = newRoot();
        writeSkill(root, "visible", "name: visible\ndescription: 可见技能", "可见正文");
        writeSkill(root, "secret", "name: secret\ndescription: 机密\ndisable-model-invocation: true", "机密正文");
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "skill", "{\"name\":\"secret\"}"),
                text("拿不到"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        agent.run(session, "试试");

        String content = session.events().stream()
                .filter(e -> e.kind() == EventKind.TOOL_RESULT)
                .findFirst()
                .orElseThrow()
                .toolResult()
                .content();
        assertTrue(content.contains("仅限用户显式调用"), content);
        assertFalse(content.contains("机密正文"), "拒绝理由不能带出正文: " + content);
        assertTrue(session.events().stream().noneMatch(e -> e.kind() == EventKind.SKILL_ACTIVATE),
                "被拒的加载不落激活事件");
    }

    @Test
    @DisplayName("每轮换状态：两轮各调一次 skill，激活事件两条（审计是轮级）")
    void eachTurnRecordsItsOwnActivation() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "正文");
        ScriptedModel model = new ScriptedModel(
                toolCall("c1", "skill", "{\"name\":\"deploy\"}"),
                text("第一轮完"),
                toolCall("c2", "skill", "{\"name\":\"deploy\"}"),
                text("第二轮完"));
        Session session = new Session("s1", null);
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        agent.run(session, "第一次");
        agent.run(session, "第二次");

        List<Event> activations = session.events().stream()
                .filter(e -> e.kind() == EventKind.SKILL_ACTIVATE)
                .toList();
        assertEquals(2, activations.size(), "状态每轮清空：第二次加载必须重新留痕");
        assertEquals(1, activations.get(0).turn());
        assertEquals(2, activations.get(1).turn(), "第二次 run 是 turn 2（turn 按 run 递增，不按模型轮次）");
        assertEquals(List.of("deploy", "deploy"),
                activations.stream().map(e -> e.skill().name()).toList());
    }

    @Test
    @DisplayName("重扫技能目录：新增技能出现在清单里，静态提示词不重渲染")
    void reloadPicksUpNewSkills() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "正文");
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());
        String before = agent.runContext().systemPrompt();

        writeSkill(root, "triage", "name: triage\ndescription: 分诊流程", "正文");
        var reload = agent.reloadSkills();

        assertTrue(reload.changed(), "新增技能应当算变化");
        assertEquals(List.of("triage"), reload.added());
        assertTrue(reload.notice().contains("triage"), "完整替换通知里有新技能: " + reload.notice());
        assertTrue(reload.notice().contains("available_skills"), reload.notice());
        assertEquals(before, agent.runContext().systemPrompt(),
                "静态系统提示词不重渲染（变化只走 user 侧通知）");
        assertEquals(2, agent.skills().size());
    }

    @Test
    @DisplayName("用户手势 /name 展开：宿主自己调，框架不自动接管输入")
    void userGestureExpansion() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "正文");
        Agent agent = Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .systemPrompt(systemPrompt())
                .skills(catalog(root))
                .build());

        assertTrue(agent.expandSkill("/deploy 灰度").expanded());
        assertFalse(agent.expandSkill("请看看 /deploy").expanded(), "句中出现不算手势");
    }

    @Test
    @DisplayName("显式传入工具注册表时，skill 工具追加到同一张表（不新建）")
    void skillToolIsRegisteredInHostRegistry() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "name: deploy\ndescription: 发布流程", "正文");
        ToolRegistry registry = new ToolRegistry();
        Agent.of(Config.builder()
                .model(new ScriptedModel(text("ok")))
                .tools(registry)
                .skills(catalog(root))
                .build());

        assertTrue(registry.visibleNames().contains("skill"));
        assertThrows(net.hegq.mos4j.tool.ToolException.class,
                () -> registry.register(impostorSkillTool()),
                "重复注册同名工具必须报错（不静默覆盖）");
    }

    private static net.hegq.mos4j.tool.FunctionTool impostorSkillTool() {
        return net.hegq.mos4j.tool.FunctionTool.of("skill", "冒名顶替", java.util.Map.class, args -> "x");
    }
}