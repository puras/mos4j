package net.hegq.mos4j.spring;

import net.hegq.mos4j.sandbox.PathScopeProbe;
import net.hegq.mos4j.sdk.Mos4j;
import net.hegq.mos4j.spring.test.FakeModelServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starter 侧的路径强制与协议旋钮（Q1/Q4/Q5 的 Spring 口径）。
 *
 * <p>验收的是三件事：
 * <ul>
 *   <li>路径强制有<b>显式</b>的关闭方式（属性），不是静默降级；</li>
 *   <li>关掉之后 Describe 如实声明「shell 不受路径强制保护」（状态可见）；</li>
 *   <li>协议旋钮（输出上限）真的进了请求，且用在错的协议上会当场报错。</li>
 * </ul>
 */
class Mos4jStarterKnobsTest {

    private static Path tempDir(String name) throws IOException {
        return Files.createDirectories(Path.of("target", "test-tmp", name + "-" + System.nanoTime()));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Mos4jAutoConfiguration.class))
                .withBean(PathScopeProbe.class,
                        () -> () -> PathScopeProbe.Result.available("测试注入"));
    }

    @Test
    void pathScopeDisabledIsHonoredAndDeclared() throws Exception {
        Path dir = tempDir("scope-off");
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.model=gpt-test",
                        "mos4j.workspace=" + dir,
                        "mos4j.log-dir=" + dir.resolve("sessions"),
                        "mos4j.path-scope=disabled")
                .run(context -> {
                    assertNotNull(context.getBean(Mos4j.class), "显式关闭也要能装配");
                    String caveats = String.join(" | ", context.getBean(Mos4j.class)
                            .agent().describe().caveats());
                    assertTrue(caveats.contains("路径强制"), caveats);
                    assertTrue(caveats.contains("宿主显式关闭"), caveats);
                });
    }

    @Test
    void requireFinishOnANonGeminiProtocolFailsFast() throws Exception {
        Path dir = tempDir("require-finish");
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.model=gpt-test",
                        "mos4j.protocol=openai",
                        "mos4j.workspace=" + dir,
                        "mos4j.log-dir=" + dir.resolve("sessions"),
                        "mos4j.require-finish=false")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null, "用在错的协议上要启动失败");
                    String message = String.valueOf(context.getStartupFailure().getMessage());
                    assertTrue(message.contains("require-finish") || message.contains("requireFinish"), message);
                });
    }

    @Test
    void requireFinishWithAHostSuppliedModelFailsFast() throws Exception {
        Path dir = tempDir("require-finish-bean");
        runner()
                .withUserConfiguration(HostModelConfiguration.class)
                .withPropertyValues(
                        "mos4j.workspace=" + dir,
                        "mos4j.log-dir=" + dir.resolve("sessions"),
                        "mos4j.require-finish=false")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null, "宿主自带模型时要说清");
                    String message = String.valueOf(context.getStartupFailure().getMessage());
                    assertTrue(message.contains("require-finish") || message.contains("requireFinish"), message);
                });
    }

    @Test
    void maxTokensReachesTheWire() throws Exception {
        try (FakeModelServer server = new FakeModelServer()) {
            server.respondJson("""
                    {"id":"cmpl-1","object":"chat.completion","created":0,"model":"gpt-test",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                       "finish_reason":"stop"}],
                     "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                    """);
            Path dir = tempDir("max-tokens");
            runner()
                    .withPropertyValues(
                            "mos4j.api-key=sk-test",
                            "mos4j.model=gpt-test",
                            "mos4j.base-url=" + server.baseUrl(),
                            "mos4j.workspace=" + dir,
                            "mos4j.log-dir=" + dir.resolve("sessions"),
                            "mos4j.max-tokens=321")
                    .run(context -> {
                        context.getBean(Mos4jRunner.class).run("s1", "你好");
                    });

            assertTrue(server.lastRequestBody().contains("\"max_tokens\":321"),
                    "配了输出上限就要真的进请求体: " + server.lastRequestBody());
        }
    }

    /** 宿主自带模型的场景。 */
    @Configuration
    static class HostModelConfiguration {

        @Bean
        net.hegq.mos4j.model.ChatModel hostModel() {
            return new net.hegq.mos4j.model.ChatModel() {
                @Override
                public net.hegq.mos4j.model.Response chat(net.hegq.mos4j.model.Request request) {
                    return new net.hegq.mos4j.model.Response(
                            net.hegq.mos4j.model.Message.assistant("ok"), null, "stop");
                }

                @Override
                public net.hegq.mos4j.model.Response chat(net.hegq.mos4j.model.Request request,
                                                        net.hegq.mos4j.core.CancellationToken cancellation) {
                    return chat(request);
                }

                @Override
                public net.hegq.mos4j.model.Response chatStream(net.hegq.mos4j.model.Request request,
                                                               net.hegq.mos4j.model.StreamEmitter emitter) {
                    return chat(request);
                }

                @Override
                public net.hegq.mos4j.model.Response chatStream(net.hegq.mos4j.model.Request request,
                                                               net.hegq.mos4j.model.StreamEmitter emitter,
                                                               net.hegq.mos4j.core.CancellationToken cancellation) {
                    return chat(request);
                }

                @Override
                public java.util.Set<net.hegq.mos4j.model.Capability> capabilities() {
                    return java.util.Set.of();
                }
            };
        }
    }

    @Test
    void theDefaultPathScopeModeIsAuto() throws Exception {
        Path dir = tempDir("scope-default");
        runner()
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.model=gpt-test",
                        "mos4j.workspace=" + dir,
                        "mos4j.log-dir=" + dir.resolve("sessions"))
                .run(context -> {
                    assertNotNull(context.getBean(Mos4j.class));
                    // 缺省是 AUTO：探针说可用就装（这里注入的是可用探针）
                    String caveats = String.join(" | ", context.getBean(Mos4j.class)
                            .agent().describe().caveats());
                    assertTrue(!caveats.contains("宿主显式关闭"), caveats);
                });
    }
}
