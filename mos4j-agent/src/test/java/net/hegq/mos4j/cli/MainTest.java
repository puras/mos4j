package net.hegq.mos4j.cli;

import net.hegq.mos4j.sdk.test.FakeModelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 最小形态（T6.9）：装配 + 跑一轮。
 *
 * <p>走真实 HTTP（FakeModelServer 回放 OpenAI SSE）——CLI 装配的
 * 是真正的 OpenAI 适配器与 Agent 链路，不是 mock。</p>
 */
class MainTest {

    private FakeModelServer server;
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeModelServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static String corpus(String path) throws IOException {
        try (InputStream in = MainTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "语料缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void runsOneTurn_andPersistsSession() throws Exception {
        // ReActPlanner 默认走非流式 chat()：回放开 JSON 响应（不是 SSE）
        server.respondJson("""
                {"id":"cmpl-1","object":"chat.completion","created":0,"model":"gpt-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello world"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                """);
        Path logDir = Path.of("target", "test-tmp", "cli-" + System.nanoTime(), "sessions");
        Path workspace = logDir.getParent().resolve("ws");
        Files.createDirectories(workspace);

        // 把 System.out 捕获起来（CLI 把回答打到 stdout）
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = Main.run(new String[]{
                    "--api-key", "sk-test",
                    "--base-url", server.baseUrl(),
                    "--model", "gpt-test",
                    "--protocol", "openai",
                    "--workspace", workspace.toString(),
                    "--log-dir", logDir.toString(),
                    "--session", "t1",
                    "--no-path-scope",
                    "你好",
            });
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(0, code, "正常退出码 0");
        assertEquals("Hello world", captured.toString(StandardCharsets.UTF_8).strip(),
                "模型回答打到 stdout");
        assertTrue(Files.exists(logDir.resolve("t1.jsonl")), "会话日志落盘");
        String log = Files.readString(logDir.resolve("t1.jsonl"));
        assertTrue(log.startsWith("{\"id\":\"t1\""), "首行是 Header");
        assertTrue(log.contains("assistant"), "assistant 事件进了日志");
    }

    @Test
    void missingInput_showsUsage_exits2() throws IOException {
        assertEquals(2, Main.run(new String[0]), "无输入 → 用法 + 退出码 2");
    }

    @Test
    void unknownFlag_isRejected() throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Main.run(new String[]{"--nope", "hi"}));
        assertTrue(error.getMessage().contains("--nope"));
    }

    @Test
    void helpExitsZero() throws IOException {
        assertEquals(0, Main.run(new String[]{"--help"}));
    }

    @Test
    void versionExitsZeroAndPrintsTheVersion() throws IOException {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertEquals(0, Main.run(new String[]{"--version"}));
        } finally {
            System.setOut(original);
        }
        String printed = buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(printed.startsWith("mos4j "), printed);
        assertTrue(printed.contains(net.hegq.mos4j.core.Version.VERSION), printed);
    }

    @Test
    void skillsDir_isWiredIntoTheSession() throws Exception {
        // --skills 目录里放 SKILL.md：技能清单应进会话日志（user 侧的 available_skills 段）。
        server.respondJson("""
                {"id":"cmpl-1","object":"chat.completion","created":0,"model":"gpt-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"收到"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
                """);
        Path logDir = Path.of("target", "test-tmp", "cli-skills-" + System.nanoTime(), "sessions");
        Path workspace = logDir.getParent().resolve("ws");
        Path skills = logDir.getParent().resolve("skills");
        Files.createDirectories(workspace);
        Path skillDir = skills.resolve("demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: demo-skill\ndescription: 演示技能\n---\n正文\n");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = Main.run(new String[]{
                    "--api-key", "sk-test",
                    "--base-url", server.baseUrl(),
                    "--model", "gpt-test",
                    "--protocol", "openai",
                    "--workspace", workspace.toString(),
                    "--log-dir", logDir.toString(),
                    "--skills", skills.toString(),
                    "--session", "t1",
                    "--no-path-scope",
                    "你好",
            });
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(0, code, "正常退出码 0");
        // 技能清单进的是**系统提示词**（会话日志不记 system prompt），所以看模型收到的请求体。
        String request = server.lastRequestBody();
        assertTrue(request.contains("demo-skill"), "技能清单应进请求的系统提示词: " + request);
        assertTrue(request.contains("available_skills"), "技能清单段应带协议标签: " + request);
    }

    @Test
    void missingApiKey_isCleanError() {
        // 装配失败要给出环境变量名（不是空指针栈）；main() 会捕获后以退出码 2 结束
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Main.run(new String[]{
                        "--protocol", "gemini",
                        "--log-dir", Path.of("target", "test-tmp",
                                "cli-missing-" + System.nanoTime()).toString(),
                        "--no-file-tools",
                        "你好",
                }));
        assertTrue(error.getMessage().contains("GEMINI_API_KEY"), error.getMessage());
    }
}
