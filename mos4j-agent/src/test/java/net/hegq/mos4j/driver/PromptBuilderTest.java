package net.hegq.mos4j.driver;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.agent.Config;
import net.hegq.mos4j.context.PromptSections;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.session.Session;
import net.hegq.mos4j.tool.skill.SkillCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可注入的系统提示词分节装配器（对齐 Go 参考实现的 {@code Config.Prompt}）。
 *
 * <p>守护四条：
 * <ul>
 *   <li><b>二选一</b>：整串提示词与分节装配器同时给 → 报错（不知道听谁的）；</li>
 *   <li><b>中心顺序</b>：框架分节（技能清单）落在宿主分节之后、工具指引之前；</li>
 *   <li><b>不动宿主那份</b>：框架在副本上装配，宿主那份保持原样；</li>
 *   <li><b>重名即报错</b>：宿主已经放了 skills 段，框架不重复塞。</li>
 * </ul>
 */
class PromptBuilderTest {

    /** 记录请求的模型替身。 */
    private static final class CapturingModel implements ChatModel {

        private final AtomicReference<Request> last = new AtomicReference<>();

        @Override
        public Response chat(Request request) {
            return chat(request, CancellationToken.none());
        }

        @Override
        public Response chat(Request request, CancellationToken cancellation) {
            last.set(request);
            return new Response(Message.assistant("答"), null, "stop");
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
            return Set.of();
        }

        String systemPrompt() {
            Request request = last.get();
            List<Message> messages = request.messages();
            for (Message message : messages) {
                if (message.role() == net.hegq.mos4j.model.Role.SYSTEM) {
                    return message.content();
                }
            }
            return null;
        }
    }

    @Test
    void theInjectedBuilderBecomesTheSystemPrompt() throws Exception {
        CapturingModel model = new CapturingModel();
        PromptSections.Builder prompt = PromptSections.builder()
                .add("base", "你是助手")
                .add("instructions", "只说中文");
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .prompt(prompt)
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "干活", null);

        String system = model.systemPrompt();
        assertTrue(system.contains("你是助手"), "宿主段必须在：" + system);
        assertTrue(system.contains("只说中文"), "宿主段必须在：" + system);
        assertTrue(system.indexOf("你是助手") < system.indexOf("只说中文"),
                "中心顺序必须生效（base 在 instructions 之前）：" + system);
    }

    @Test
    void theHostsBuilderIsNotMutated() throws Exception {
        CapturingModel model = new CapturingModel();
        PromptSections.Builder prompt = PromptSections.builder().add("base", "你是助手");
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .prompt(prompt)
                .build());

        // 同一个装配器可以复用于第二个智能体：框架在副本上装配。
        Agent second = Agent.of(Config.builder()
                .model(model)
                .prompt(prompt)
                .build());
        Session session = new Session("s2", null);
        second.run(session, "干活", null);

        assertFalse(prompt.has("skills"), "宿主那份装配器不该被框架改写");
    }

    @Test
    void aSystemPromptAndAPromptBuilderAreMutuallyExclusive() {
        CapturingModel model = new CapturingModel();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Config.builder()
                        .model(model)
                        .systemPrompt("整串")
                        .prompt(PromptSections.builder().add("base", "分节"))
                        .build());

        assertTrue(thrown.getMessage().contains("二选一"), thrown.getMessage());
    }

    @Test
    void theStringRouteStillWorks() throws Exception {
        CapturingModel model = new CapturingModel();
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .systemPrompt("整串提示词")
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "干活", null);

        assertEquals("整串提示词", model.systemPrompt());
    }

    @Test
    void theSkillsSectionLandsInTheInjectedBuilder() throws IOException {
        Path root = Files.createDirectories(
                Path.of("target", "test-tmp", "prompt-skills-" + System.nanoTime()).toAbsolutePath());
        Path skillDir = root.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: 演示技能\n---\n正文", StandardCharsets.UTF_8);
        SkillCatalog skills = SkillCatalog.load(SkillCatalog.Options.of(List.of(root.toString())));

        CapturingModel model = new CapturingModel();
        PromptSections.Builder prompt = PromptSections.builder()
                .add("base", "你是助手")
                .add("instructions", "只说中文");
        Agent agent = Agent.of(Config.builder()
                .model(model)
                .prompt(prompt)
                .skills(skills)
                .build());
        Session session = new Session("s1", null);

        agent.run(session, "干活", null);

        String system = model.systemPrompt();
        assertTrue(system.contains("演示技能"), "技能清单必须进提示词：" + system);
        assertTrue(system.indexOf("你是助手") < system.indexOf("演示技能"),
                "中心顺序：base 在 skills 之前：" + system);
        assertTrue(system.indexOf("演示技能") < system.indexOf("只说中文"),
                "中心顺序：skills 在 instructions 之前：" + system);
    }

    @Test
    void aHostsOwnSkillsSectionIsNotDuplicated() throws IOException {
        Path root = Files.createDirectories(
                Path.of("target", "test-tmp", "prompt-skills-dup-" + System.nanoTime()).toAbsolutePath());
        Path skillDir = root.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: 演示技能\n---\n正文", StandardCharsets.UTF_8);
        SkillCatalog skills = SkillCatalog.load(SkillCatalog.Options.of(List.of(root.toString())));

        CapturingModel model = new CapturingModel();
        PromptSections.Builder prompt = PromptSections.builder()
                .add("base", "你是助手")
                .add("skills", "宿主自己写的技能段");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Agent.of(Config.builder()
                        .model(model)
                        .prompt(prompt)
                        .skills(skills)
                        .build()));
        assertTrue(thrown.getMessage().contains("skills"), thrown.getMessage());
    }
}
