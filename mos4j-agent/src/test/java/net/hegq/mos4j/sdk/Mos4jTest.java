package net.hegq.mos4j.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 缺省装路径强制的门面（注入一个「可用」的探针）。
     *
     * <p><b>为什么必须注入</b>：门面的缺省行为会探一次这台机器能不能真的把 macOS 沙箱用起来；
     * 开发机常常是「有 sandbox-exec 但宿主拒绝 apply」，真探会失败 → 装配期直接报错。
     * 测试不执行命令，所以注入「可用」即可（探测本身由 sandbox 模块的用例真跑）。
     */
    private static Mos4j.Builder builder() {
        return Mos4j.builder().pathScopeProbe(
                () -> net.hegq.mos4j.sandbox.PathScopeProbe.Result.available("测试注入"));
    }

    @Test
    void buildsWithOpenAiDefaults() throws Exception {
        Mos4j mos4j = builder()
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
                () -> builder()
                        .protocol(Mos4j.Protocol.ANTHROPIC)
                        .logDir(tempDir().resolve("s"))
                        .env(Map.of())
                        .build());
        assertTrue(error.getMessage().contains("ANTHROPIC_API_KEY"),
                "缺密钥时报环境变量名: " + error.getMessage());
    }

    @Test
    void protocolSwitch_readsWithMatchingEnvVar() throws Exception {
        Mos4j gemini = builder()
                .protocol(Mos4j.Protocol.GEMINI)
                .logDir(tempDir().resolve("s2"))
                .env(env("GEMINI_API_KEY", "g-key", "GEMINI_MODEL", "gemini-test"))
                .build();
        assertTrue(gemini.agent().describe().modelProvider().toLowerCase().contains("gemini"),
                "Gemini 协议: " + gemini.agent().describe().modelProvider());
    }

    @Test
    void sessionRoundTrip() throws Exception {
        Mos4j mos4j = builder()
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
        Mos4j mos4j = builder()
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
        Mos4j mos4j = builder()
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
        Mos4j mos4j = builder()
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
    void contextFilesAreWiredIntoTheSystemPrompt() throws Exception {
        Path root = tempDir();
        Files.writeString(root.resolve("AGENTS.md"), "项目规矩：提交前必须跑测试\n");

        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .contextFileReader(new net.hegq.mos4j.context.ContextFileReader() {
                    @Override
                    public byte[] readIfExists(String path) throws IOException {
                        Path candidate = Path.of(path);
                        if (!Files.isRegularFile(candidate)) {
                            return null;
                        }
                        return Files.readAllBytes(candidate);
                    }

                    @Override
                    public String parentOf(String dir) {
                        return null; // 测试不向上递归（避免扫到仓库上层）
                    }
                })
                .contextFileGlobalDirs(List.of())
                .contextFiles(root)
                .build();

        String prompt = mos4j.agent().runContext().systemPrompt();
        assertTrue(prompt.contains("提交前必须跑测试"), prompt);
        assertTrue(prompt.contains("<project_instructions"), prompt);
        assertTrue(prompt.contains("AGENTS.md"), prompt);
        assertTrue(mos4j.agent().describe().features().stream()
                .anyMatch(f -> f.contains("上下文文件")), mos4j.agent().describe().features().toString());
    }

    @Test
    void contextFilesLandInTheContextSectionOfTheInjectedBuilder() throws Exception {
        Path root = tempDir();
        Files.writeString(root.resolve("AGENTS.md"), "项目规矩：提交前必须跑测试\n");

        net.hegq.mos4j.context.PromptSections.Builder prompt =
                net.hegq.mos4j.context.PromptSections.builder().add("base", "你是助手");
        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .contextFileReader(new net.hegq.mos4j.context.ContextFileReader() {
                    @Override
                    public byte[] readIfExists(String path) throws IOException {
                        Path candidate = Path.of(path);
                        if (!Files.isRegularFile(candidate)) {
                            return null;
                        }
                        return Files.readAllBytes(candidate);
                    }

                    @Override
                    public String parentOf(String dir) {
                        return null;
                    }
                })
                .contextFileGlobalDirs(List.of())
                .contextFiles(root)
                .prompt(prompt)
                .build();

        String systemPrompt = mos4j.agent().runContext().systemPrompt();
        assertTrue(systemPrompt.contains("你是助手"), systemPrompt);
        assertTrue(systemPrompt.contains("提交前必须跑测试"), systemPrompt);
        assertTrue(systemPrompt.indexOf("你是助手") < systemPrompt.indexOf("提交前必须跑测试"),
                "中心顺序：base 在 context 之前：" + systemPrompt);
        assertFalse(prompt.has("context"), "宿主那份装配器不该被框架改写");
    }

    @Test
    void disableCompactionConflictsWithAnInstalledCompactionService() throws Exception {
        Path root = tempDir();
        net.hegq.mos4j.context.CompactionService compaction =
                new net.hegq.mos4j.context.CompactionService(
                        net.hegq.mos4j.context.CompactionPolicy.of(100, 0, 0),
                        (transcript, previousSummary) ->
                                new net.hegq.mos4j.context.SummaryGenerator.Summary("摘要", null));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .compaction(compaction)
                .disableCompaction(true)
                .build());
        assertTrue(error.getMessage().contains("冲突"), error.getMessage());
    }

    @Test
    void summaryTimeoutIsAppliedToTheInstalledCompactionService() throws Exception {
        Path root = tempDir();
        net.hegq.mos4j.context.CompactionService compaction =
                new net.hegq.mos4j.context.CompactionService(
                        net.hegq.mos4j.context.CompactionPolicy.of(100, 0, 0),
                        (transcript, previousSummary) ->
                                new net.hegq.mos4j.context.SummaryGenerator.Summary("摘要", null));

        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .compaction(compaction)
                .summaryTimeout(java.time.Duration.ofSeconds(9))
                .build();

        assertEquals(java.time.Duration.ofSeconds(9),
                mos4j.agent().runContext().compaction().summaryTimeout());
    }

    @Test
    void aNegativeSummaryTimeoutIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder().summaryTimeout(java.time.Duration.ofSeconds(-1)));
        assertTrue(error.getMessage().contains("不能为负"), error.getMessage());
    }

    @Test
    void rootsCanBeManagedAtRuntime() throws Exception {
        Path root = tempDir();
        Path extra = Files.createDirectories(root.resolve("extra"));
        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .build();

        assertEquals(1, mos4j.agent().roots().size(), "装配时只有锚点");

        mos4j.agent().addRoot("extra", extra);
        assertEquals(2, mos4j.agent().roots().size());
        assertTrue(mos4j.agent().roots().stream().anyMatch(r -> r.id().equals("extra")));

        mos4j.agent().removeRoot("extra");
        assertEquals(1, mos4j.agent().roots().size());
    }

    @Test
    void skillsAreWiredThroughTheFacade() throws Exception {
        Path root = tempDir();
        Path skillDir = Files.createDirectories(root.resolve("skills").resolve("deploy"));
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n第一步\n");

        Mos4j mos4j = builder()
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

        Mos4j mos4j = builder()
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
    void withoutSkillsTheToolIsAbsent() throws Exception {        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s6"))
                .env(env())
                .build();

        assertTrue(mos4j.agent().skills().isEmpty());
        assertTrue(mos4j.agent().describe().tools().stream().noneMatch("skill"::equals),
                "没装技能就不该给 skill 工具: " + mos4j.agent().describe().tools());
    }

    @Test
    void subAgentsAreWiredThroughTheFacade() throws Exception {
        Path root = tempDir();
        Path agentDir = Files.createDirectories(root.resolve("agents").resolve("deploy"));
        Files.writeString(agentDir.resolve("AGENT.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n你是发布代理。\n");

        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(root.resolve("sessions"))
                .env(env())
                .subAgents(root.resolve("agents"))
                .build();

        assertTrue(mos4j.agent().describe().tools().contains("subagent__deploy"),
                "子代理目录被扫到 → 注册子代理工具: " + mos4j.agent().describe().tools());
        assertTrue(mos4j.agent().runContext().systemPrompt().contains("available_subagents"),
                "子代理清单进了系统提示词");
        assertTrue(mos4j.agent().describe().features().stream()
                        .anyMatch(f -> f.contains("子代理")),
                "Describe 里要说清装了几个子代理: " + mos4j.agent().describe().features());
    }

    @Test
    void aSubAgentModelOverrideThatDoesNotExistFailsAtAssembly() throws Exception {
        Path root = tempDir();
        Path agentDir = Files.createDirectories(root.resolve("agents").resolve("deploy"));
        Files.writeString(agentDir.resolve("AGENT.md"),
                "---\nname: deploy\ndescription: 发布流程\nmodel: ghost\n---\n你是发布代理。\n");

        net.hegq.mos4j.tool.subagent.SubAgentException error = assertThrows(
                net.hegq.mos4j.tool.subagent.SubAgentException.class,
                () -> builder()
                        .apiKey("sk")
                        .logDir(root.resolve("sessions"))
                        .env(env())
                        .subAgents(root.resolve("agents"))
                        .build());
        assertTrue(error.getMessage().contains("ghost"), error.getMessage());
    }

    @Test
    void aNegativeSubAgentTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().subAgentTimeout(java.time.Duration.ofSeconds(-1)));
    }

    @Test
    void anUnavailablePathScopeFailsFastAtAssembly() {
        // 缺省行为：探一次能不能真用起来；不通就在装配期报错（fail-closed），
        // 而不是让模型在第 N 轮说「命令失败了」。
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Mos4j.builder()
                        .apiKey("sk")
                        .logDir(tempDir().resolve("s7"))
                        .env(env())
                        .pathScope(new net.hegq.mos4j.sandbox.SeatbeltScope(List.of(tempDir())))
                        .pathScopeProbe(() -> net.hegq.mos4j.sandbox.PathScopeProbe.Result
                                .unavailable("宿主不允许应用 macOS 沙箱"))
                        .build());

        assertTrue(error.getMessage().contains("路径强制不可用"), error.getMessage());
        assertTrue(error.getMessage().contains("noPathScope()"),
                "错误信息要给出路（换机器 / 显式关闭）: " + error.getMessage());
    }

    @Test
    void noPathScopeIsDeclaredInTheSnapshot() throws Exception {
        // 宿主显式不要这层保护：Describe 必须说清楚「shell 不受路径强制保护」
        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s8"))
                .env(env())
                .noPathScope()
                .build();

        var caveats = mos4j.agent().describe().caveats();
        assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=未装配")
                        && c.contains("宿主显式关闭")),
                "显式关闭要在快照里说清楚: " + caveats);
    }

    @Test
    void anAvailableProbeInstallsThePathScope() throws Exception {
        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s9"))
                .env(env())
                .pathScope(new net.hegq.mos4j.sandbox.SeatbeltScope(List.of(tempDir())))
                .pathScopeProbe(() -> net.hegq.mos4j.sandbox.PathScopeProbe.Result.available("测试注入"))
                .build();

        var caveats = mos4j.agent().describe().caveats();
        assertTrue(caveats.stream().noneMatch(c -> c.contains("路径强制=未装配")),
                "装了路径强制就不该说没装: " + caveats);
        assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=macOS Seatbelt")),
                "快照要说清装的是什么: " + caveats);
    }

    @Test
    void theDefaultPathScopeIsDeclaredAsInstalledWhereSupported() throws Exception {
        Mos4j mos4j = builder()
                .apiKey("sk")
                .logDir(tempDir().resolve("s10"))
                .env(env())
                .build();

        var caveats = mos4j.agent().describe().caveats();
        if (net.hegq.mos4j.sandbox.PathScopes.supported()) {
            // 有**已验证**的后端时，缺省就该装上（而且快照要说是动态写根，不能说成拍死的）
            assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=已装配")),
                    "有已验证后端时缺省就该装上: " + caveats);
            assertTrue(caveats.stream().anyMatch(c -> c.contains("动态写根")),
                    "缺省是动态写根（跟着文件 jail 的活根集合）: " + caveats);
        } else {
            assertTrue(caveats.stream().anyMatch(c -> c.contains("路径强制=未装配")),
                    "没有已验证后端就如实说没装: " + caveats);
        }
    }
}
