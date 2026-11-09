package net.hegq.mos4j.spring;

import net.hegq.mos4j.agent.Agent;
import net.hegq.mos4j.core.ApprovalResult;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.hook.ApprovalChannel;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.sandbox.PathScopeProbe;
import net.hegq.mos4j.session.JsonlPersistence;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starter 自动装配（Spring Boot 侧）：条件装配、fail-fast、可选能力接线。
 *
 * <p><b>口径</b>：starter 不重新实现任何语义，只把属性翻译成 SDK 装配——
 * 所以这里验的是「装配对不对、缺配置时是不是当场炸」。
 */
class Mos4jAutoConfigurationTest {

    /** 文件测试用 {@code target/test-tmp}（沙箱禁止写系统临时目录，{@code @TempDir} 不可用）。 */
    private static Path tempDir(String name) throws IOException {
        return Files.createDirectories(Path.of("target", "test-tmp", name + "-" + System.nanoTime()));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Mos4jAutoConfiguration.class))
                // 测试不真的跑沙箱：注入一个「可用」的探针。
                // 探测本身（真跑一次 sandbox-exec）由 mos4j-runtime 的用例守。
                .withBean(PathScopeProbe.class,
                        () -> () -> PathScopeProbe.Result.available("测试注入"));
    }

    @Test
    void assemblesByDefault() throws Exception {
        Path dir = tempDir("assemble");
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.model=gpt-test",
                        "mos4j.workspace=" + dir,
                        "mos4j.log-dir=" + dir.resolve("sessions"))
                .run(context -> {
                    assertNotNull(context.getBean(Mos4j.class), "缺省就装配门面");
                    assertNotNull(context.getBean(Agent.class), "Agent 可注入");
                    assertNotNull(context.getBean(JsonlPersistence.class), "持久化可注入");
                    assertNotNull(context.getBean(Mos4jRunner.class), "执行器可注入");
                    String provider = context.getBean(Agent.class).describe().modelProvider();
                    assertTrue(provider.toLowerCase().contains("openai"), "缺省 OpenAI: " + provider);
                });
    }

    @Test
    void disabledMeansNoBeans() {
        runner()
                .withPropertyValues("mos4j.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("mos4j"), "显式关掉时不该有门面");
                    assertEquals(0, context.getBeanNamesForType(Mos4j.class).length,
                            "关掉时一个 Bean 都不给（状态可见，不是半残的）");
                });
    }

    @Test
    void missingApiKeyFailsFast() {
        // 去掉系统环境属性源，让「没配密钥」这件事是确定的（不依赖跑测机器的环境）
        runner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources().remove("systemEnvironment"))
                .withPropertyValues("mos4j.protocol=openai")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null, "缺密钥必须启动失败");
                    String message = String.valueOf(context.getStartupFailure().getMessage());
                    assertTrue(message.contains("OPENAI_API_KEY"),
                            "报错要说清看哪个变量: " + message);
                });
    }

    @Test
    void apiKeyComesFromEnvironmentVariable() {
        // 环境变量兜底：属性没给时读 OPENAI_API_KEY（Spring Environment 能读到系统环境）
        runner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources().remove("systemEnvironment"))
                .withPropertyValues("OPENAI_API_KEY=sk-from-env", "mos4j.model=gpt-test")
                .run(context -> {
                    assertNotNull(context.getBean(Mos4j.class), "环境变量兜底应能装配");
                });
    }

    @Test
    void requiredApprovalsWithoutChannelFailsFast() {
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.approvals.required=true")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null,
                            "要求审批但没有通道 → 启动失败（不静默降级）");
                    String message = String.valueOf(context.getStartupFailure().getMessage());
                    assertTrue(message.contains("ApprovalChannel"),
                            "报错要指名缺哪个 Bean: " + message);
                });
    }

    @Test
    void requiredApprovalsWithChannelAreWired() {
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.approvals.required=true")
                .withUserConfiguration(ApprovalChannelConfiguration.class)
                .run(context -> {
                    assertNotNull(context.getBean(Mos4j.class), "有通道就能装配");
                    var caveats = context.getBean(Agent.class).describe().caveats();
                    assertTrue(caveats.stream().noneMatch(c -> c.contains("未装配审批通道")),
                            "审批通道已装配，Describe 不该说没装: " + caveats);
                });
    }

    @Test
    void outputLimiterIsInstalledByDefault() {
        runner()
                .withPropertyValues("mos4j.api-key=sk-test")
                .run(context -> {
                    var features = context.getBean(Agent.class).describe().features();
                    assertTrue(features.stream().anyMatch(f -> f.contains("工具输出限流")),
                            "缺省装上限流（上下文是稀缺资源）: " + features);
                });
    }

    @Test
    void compactionIsNotInstalledWithoutAWindow() {
        // 窗口只有服务商知道：不配置就不压缩（猜窗口 = 用错误的事实做决定）
        runner()
                .withPropertyValues("mos4j.api-key=sk-test")
                .run(context -> {
                    var caveats = context.getBean(Agent.class).describe().caveats();
                    assertTrue(caveats.stream().anyMatch(c -> c.contains("未装配上下文压缩")),
                            "未配置窗口就不该装压缩: " + caveats);
                });
    }

    @Test
    void compactionIsInstalledWithAWindow() {
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.compaction.context-window=8000",
                        "mos4j.compaction.reserve-tokens=1024")
                .run(context -> {
                    var features = context.getBean(Agent.class).describe().features();
                    assertTrue(features.stream().anyMatch(f -> f.contains("上下文压缩")),
                            "配了窗口就该装压缩: " + features);
                });
    }

    @Test
    void hostChatModelMeansNoApiKeyIsNeeded() {
        // 宿主自带模型（本地模型 / 自带凭据的网关）：那个模型自己知道怎么认证，
        // 再要一把用不上的密钥只是噪音（配置在骗人）。
        runner()
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources().remove("systemEnvironment"))
                .withPropertyValues("mos4j.protocol=openai")
                .withUserConfiguration(ChatModelConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "宿主自带模型时不该要密钥: " + context.getStartupFailure());
                    assertNotNull(context.getBean(Mos4j.class), "有模型就能装配");
                });
    }

    @Test
    void spillDirRelativeToWorkspaceIsAccepted() throws Exception {
        // 落盘逃生通道：目录按 jail 锚点（workspace）解析，且必须在可读根内
        Path workspace = tempDir("spill-ok").toAbsolutePath();
        Files.createDirectories(workspace.resolve("spill"));
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.workspace=" + workspace,
                        "mos4j.output.spill-dir=spill")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "jail 内的落盘目录应当可用: " + context.getStartupFailure());
                });
    }

    @Test
    void spillDirEscapingTheWorkspaceFailsFast() throws Exception {
        // 越界在构造期就失败：不等到运行期默默写到 jail 外面
        Path workspace = tempDir("spill-anchor").toAbsolutePath();
        Path escape = tempDir("spill-escape").toAbsolutePath();
        String relative = workspace.getParent().relativize(escape).toString();
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.workspace=" + workspace,
                        "mos4j.output.spill-dir=" + relative)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "落盘目录越界必须启动失败（fail-closed）");
                });
    }

    @Test
    void spillDirWithoutReadableFileToolsFailsFast() {
        // 宿主自带工具面时 starter 不知道模型能读哪些目录——不装作知道，直接报错
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.output.spill-dir=spill")
                .withUserConfiguration(ToolRegistryConfiguration.class)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "没有可读回它的文件工具时不该默默忽略该配置");
                    String message = String.valueOf(context.getStartupFailure().getMessage());
                    assertTrue(message.contains("spill-dir"),
                            "报错要指名哪个配置项: " + message);
                });
    }

    @Test
    void skillDirsRelativeToWorkspaceArePickedUp() throws Exception {
        // 技能目录相对路径按 mos4j.workspace 解析（不以进程 CWD 为锚：换目录启动不该换技能集合）
        Path workspace = tempDir("skill-anchor").toAbsolutePath();
        Path skill = Files.createDirectories(workspace.resolve("skills").resolve("deploy"));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n第一步\n");

        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.workspace=" + workspace,
                        "mos4j.skills.dirs=skills")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "相对技能目录应当按 workspace 解析: " + context.getStartupFailure());
                    Mos4j mos4j = context.getBean(Mos4j.class);
                    assertEquals(1, mos4j.agent().skills().size(),
                            "技能: " + mos4j.agent().skills());
                    assertTrue(mos4j.agent().describe().tools().contains("skill"),
                            "有模型可见技能就有 skill 工具: " + mos4j.agent().describe().tools());
                });
    }

    @Test
    void skillDirsAreOptional() {
        runner()
                .withPropertyValues("mos4j.api-key=sk-test")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    Mos4j mos4j = context.getBean(Mos4j.class);
                    assertTrue(mos4j.agent().skills().isEmpty(), "没配目录就没技能");
                    assertTrue(mos4j.agent().describe().tools().stream().noneMatch("skill"::equals),
                            "没技能就不给 skill 工具: " + mos4j.agent().describe().tools());
                });
    }

    @Test
    void skillLimitsReachTheCatalog() throws Exception {
        Path workspace = tempDir("skill-limit").toAbsolutePath();
        Path skill = Files.createDirectories(workspace.resolve("skills").resolve("deploy"));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: deploy\ndescription: 发布流程\n---\n" + "正文".repeat(200) + "\n");

        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.workspace=" + workspace,
                        "mos4j.skills.dirs=skills",
                        "mos4j.skills.max-body-bytes=100")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    var tool = context.getBean(Mos4j.class).agent().runContext().skillTool();
                    assertNotNull(tool, "有可见技能就有工具");
                    Exception error = assertThrows(Exception.class,
                            () -> tool.execute("{\"name\":\"deploy\"}"));
                    assertTrue(error.getMessage().contains("超预算"), error.getMessage());
                });
    }

    @Test
    void contextFilesDirIsWiredIntoTheSystemPrompt() throws Exception {
        Path workspace = tempDir("context-files").toAbsolutePath();
        Files.writeString(workspace.resolve("AGENTS.md"), "项目规矩：提交前必须跑测试\n");

        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.workspace=" + workspace,
                        "mos4j.context-files.dir=.",
                        "mos4j.context-files.global-dirs=")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    String prompt = context.getBean(Mos4j.class).agent().runContext().systemPrompt();
                    assertTrue(prompt.contains("提交前必须跑测试"), prompt);
                    assertTrue(prompt.contains("<project_instructions"), prompt);
                });
    }

    @Test
    void contextFilesAreOptional() {
        runner()
                .withPropertyValues("mos4j.api-key=sk-test")
                .run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    String prompt = context.getBean(Mos4j.class).agent().runContext().systemPrompt();
                    assertTrue(prompt == null || !prompt.contains("<project_instructions"),
                            "没配目录就不装上下文文件: " + prompt);
                });
    }

    /** 提供一个审批通道的宿主配置（验证「宿主 Bean 被接进去」）。 */    @Configuration(proxyBeanMethods = false)
    static class ApprovalChannelConfiguration {
        @Bean
        ApprovalChannel approvalChannel() {
            return request -> ApprovalResult.approved();
        }
    }

    /** 提供一个工具面的宿主配置（验证「宿主自带工具面时 starter 不知道边界」）。 */
    @Configuration(proxyBeanMethods = false)
    static class ToolRegistryConfiguration {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry();
        }
    }

    /** 提供一个模型的宿主配置（验证「宿主自带模型时不要密钥」）。 */
    @Configuration(proxyBeanMethods = false)
    static class ChatModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public Response chat(Request request) {
                    throw new UnsupportedOperationException("本测试只验装配，不发请求");
                }

                @Override
                public Response chat(Request request, CancellationToken cancellation) {
                    throw new UnsupportedOperationException("本测试只验装配，不发请求");
                }

                @Override
                public Response chatStream(Request request, StreamEmitter emitter) {
                    throw new UnsupportedOperationException("本测试只验装配，不发请求");
                }

                @Override
                public Response chatStream(Request request, StreamEmitter emitter,
                                           CancellationToken cancellation) {
                    throw new UnsupportedOperationException("本测试只验装配，不发请求");
                }

                @Override
                public Set<Capability> capabilities() {
                    return Set.of();
                }
            };
        }
    }
}
