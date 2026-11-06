package net.hegq.mos4j.spring;

import net.hegq.mos4j.spring.test.FakeLlmServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starter 端到端：自动装配后真跑一轮（走真实 HTTP，回放 OpenAI 响应）。
 *
 * <p><b>为什么真跑</b>：自动装配最容易出的错是「Bean 都在，但链路是断的」
 * （装配了却没把模型接进 Agent）。只看 Bean 存在不算数——要真跑出一轮答案。
 */
class Mos4jStarterTurnTest {

    private FakeLlmServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLlmServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void assemblesAndRunsOneTurn() throws Exception {
        server.respondJson("""
                {"id":"cmpl-1","object":"chat.completion","created":0,"model":"gpt-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from starter"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                """);
        Path dir = Files.createDirectories(
                Path.of("target", "test-tmp", "starter-turn-" + System.nanoTime()));
        Path workspace = Files.createDirectories(dir.resolve("ws"));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Mos4jAutoConfiguration.class))
                .withPropertyValues(
                        "mos4j.api-key=sk-test",
                        "mos4j.model=gpt-test",
                        "mos4j.base-url=" + server.baseUrl(),
                        "mos4j.workspace=" + workspace,
                        "mos4j.log-dir=" + dir.resolve("sessions"))
                .run(context -> {
                    assertNotNull(context.getBean(Mos4jRunner.class), "执行器应装配");
                    Mos4jRunner runner = context.getBean(Mos4jRunner.class);
                    try {
                        String answer = runner.run("s1", "你好");
                        assertEquals("Hello from starter", answer, "自动装配后能真跑出一轮");
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                });

        assertTrue(Files.exists(dir.resolve("sessions").resolve("s1.jsonl")),
                "会话落盘（日志即事实）");
        String log = Files.readString(dir.resolve("sessions").resolve("s1.jsonl"));
        assertTrue(log.startsWith("{\"id\":\"s1\""), "首行是 Header: " + log.lines().findFirst());
        assertTrue(log.contains("assistant"), "assistant 事件进了日志");
        assertTrue(server.lastRequestBody().contains("Hello from starter")
                        || server.lastRequestBody().contains("你好"),
                "请求体确实发到了服务端");
    }
}
