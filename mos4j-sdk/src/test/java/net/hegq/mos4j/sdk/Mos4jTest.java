package net.hegq.mos4j.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 门面（T6.10）：一次依赖拿到可跑 Agent；门面不引入 SDK 之外的依赖。
 *
 * <p>文件测试用 {@code target/test-tmp}（沙箱禁止写系统临时目录，{@code @TempDir} 不可用）。</p>
 */
class Mos4jTest {

    private Path dir;

    private Path tempDir() throws IOException {
        dir = Files.createDirectories(Path.of("target", "test-tmp", "sdk-" + System.nanoTime()));
        return dir;
    }

    @AfterEach
    void cleanUp() {
        // target/test-tmp 留给构建清理，测试内不递归删（避免误伤）
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            env.put(pairs[i], pairs[i + 1]);
        }
        return env;
    }

    @Test
    void buildsWithOpenAiDefaults() throws Exception {
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk-test")
                .model("gpt-test")
                .logDir(tempDir().resolve("sessions"))
                .env(env("OPENAI_API_KEY", "from-builder-wins"))
                .build();

        assertNotNull(mos4j.agent(), "一次装配即可拿到可跑 Agent");
        assertTrue(mos4j.agent().describe().modelProvider().toLowerCase().contains("openai"),
                "缺省 OpenAI: " + mos4j.agent().describe().modelProvider());
        // 文件工具缺省装配（工具名按 J3 冻结口径：read/write/edit/shell）
        assertTrue(mos4j.agent().describe().tools().stream()
                        .anyMatch("read"::equals),
                "缺省应装配文件工具 read: " + mos4j.agent().describe().tools());
    }

    @Test
    void missingKeyFailsFastWithEnvName() throws IOException {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Mos4j.builder()
                        .protocol(Mos4j.Protocol.ANTHROPIC)
                        .logDir(tempDir().resolve("s"))
                        .env(Map.of())
                        .build());
        assertTrue(error.getMessage().contains("ANTHROPIC_API_KEY"),
                "缺密钥时报环境变量名: " + error.getMessage());
    }

    @Test
    void protocolSwitch_readsWithMatchingEnvVar() throws Exception {
        Mos4j gemini = Mos4j.builder()
                .protocol(Mos4j.Protocol.GEMINI)
                .logDir(tempDir().resolve("s2"))
                .env(env("GEMINI_API_KEY", "g-key", "GEMINI_MODEL", "gemini-test"))
                .build();
        assertTrue(gemini.agent().describe().modelProvider().toLowerCase().contains("gemini"),
                "Gemini 协议: " + gemini.agent().describe().modelProvider());
    }

    @Test
    void sessionRoundTrip() throws Exception {
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("sessions"))
                .env(env())
                .build();
        var session = mos4j.session("abc");
        session.append(net.hegq.mos4j.session.Event.builder(net.hegq.mos4j.session.EventKind.TURN_START)
                .turn(0).build());
        var reopened = mos4j.session("abc");
        // restore 会给孤儿 turn 合成收尾（turn/start 无 turn/end → interrupted），
        // 所以事件数 = 原数 + 合成的 turn/end（这是 Session 语义，不是门面的）
        assertEquals(2, reopened.events().size(), "同 id 再开 = 恢复历史（含孤儿合成收尾）");
    }

    @Test
    void noFileToolsOption() throws Exception {
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .fileTools(false)
                .logDir(tempDir().resolve("s3"))
                .env(env())
                .build();
        assertTrue(mos4j.agent().describe().tools().isEmpty(),
                "fileTools(false)：工具面为空");
    }

    @Test
    void describeReportsTheJailThatIsActuallyInstalled() throws Exception {
        // 工具明明被 jail 着，Describe 却说「未装配文件 jail」——那是假话。
        // 门面必须把边界快照如实写进 Config，否则宿主无法回答「我现在装了什么」。
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s4"))
                .env(env())
                .build();
        var caveats = mos4j.agent().describe().caveats();
        assertTrue(caveats.stream().noneMatch(c -> c.contains("未装配文件 jail")),
                "文件工具已装配，Describe 不该说没装 jail: " + caveats);
        assertTrue(caveats.stream().noneMatch(c -> c.contains("未装配命令沙箱")),
                "shell 工具已装配，Describe 不该说没装沙箱: " + caveats);
    }

    @Test
    void optionalCapabilitiesAreWiredThrough() throws Exception {
        // 门面新开的可选装配：审批通道装上后，Describe 不再声明「未装配审批通道」。
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s5"))
                .env(env())
                .approvals(request -> net.hegq.mos4j.core.ApprovalResult.approved())
                .build();
        var caveats = mos4j.agent().describe().caveats();
        assertTrue(caveats.stream().noneMatch(c -> c.contains("未装配审批通道")),
                "审批通道已装配，Describe 不该说没装: " + caveats);
    }

    @Test
    void skillsAreWiredThroughTheFacade() throws Exception {
        Path root = tempDir();
        Path skillDir = Files.createDirectories(root.resolve("skills").resolve("deploy"));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n第一步\n");

        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .skills(root.resolve("skills"))
                .build();

        assertEquals(1, mos4j.agent().skills().size(), "技能目录里的技能被扫到");
        assertTrue(mos4j.agent().describe().tools().contains("skill"),
                "有模型可见技能 → 注册 skill 工具: " + mos4j.agent().describe().tools());
        assertTrue(mos4j.agent().runContext().systemPrompt().contains("deploy"),
                "清单进了系统提示词");
        assertTrue(mos4j.agent().expandSkill("/deploy").expanded(), "手势展开走门面装配的同一份清单");
    }

    @Test
    void skillLimitsArePassedToTheCatalog() throws Exception {
        Path root = tempDir();
        Path skillDir = Files.createDirectories(root.resolve("skills").resolve("deploy"));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n" + "正文".repeat(200) + "\n");

        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .skills(root.resolve("skills"))
                .skillLimits(100, 0)
                .build();

        // 正文限额透传到清单：超预算在「调用时现读」那一刻才暴露（不预先读正文）
        var tool = mos4j.agent().runContext().skillTool();
        assertNotNull(tool, "有可见技能就有工具");
        var error = assertThrows(net.hegq.mos4j.tool.ToolException.class,
                () -> tool.execute("{\"name\":\"deploy\"}"));
        assertTrue(error.getMessage().contains("超预算"), error.getMessage());
    }

    @Test
    void withoutSkillsTheToolIsAbsent() throws Exception {
        Mos4j mos4j = Mos4j.builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s6"))
                .env(env())
                .build();

        assertTrue(mos4j.agent().skills().isEmpty());
        assertTrue(mos4j.agent().describe().tools().stream().noneMatch("skill"::equals),
                "没装技能就不该给 skill 工具: " + mos4j.agent().describe().tools());
    }
}
