package net.hegq.mos4j.examples.agents;

import net.hegq.mos4j.examples.testkit.StubModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多智能体示例的用例：三个角色各跑各的会话，产出接得上。
 */
class AgentsExampleTest {

    private Path tempDir() throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", "example-agents-" + System.nanoTime()));
    }

    @Test
    @DisplayName("三角色编排：拆解 → 执行 → 验收，各自在自己的会话里")
    void orchestratesThreeRoles() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.text("1. 收集本周进展\n2. 汇总风险\n3. 定下周计划"),
                StubModel.text("已收集：3 项进展；风险 1 项"),
                StubModel.text("通过"));

        String verdict = AgentsExample.orchestrate(model, "写一篇项目周报", dir.resolve("sessions"));

        assertEquals("通过", verdict);
        assertEquals(3, model.requests().size(), "每个角色各调一次模型");
        assertEquals(AgentsExample.PLANNER_PROMPT, model.systemPromptOf(0));
        assertEquals(AgentsExample.WORKER_PROMPT, model.systemPromptOf(1));
        assertEquals(AgentsExample.REVIEWER_PROMPT, model.systemPromptOf(2));
    }

    @Test
    @DisplayName("会话隔离：三个角色三个会话，日志各在各的")
    void rolesUseSeparateSessions() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.text("计划"), StubModel.text("结果"), StubModel.text("通过"));

        AgentsExample.orchestrate(model, "任务", dir.resolve("sessions"));

        assertTrue(Files.exists(dir.resolve("sessions").resolve("planner.jsonl")));
        assertTrue(Files.exists(dir.resolve("sessions").resolve("worker.jsonl")));
        assertTrue(Files.exists(dir.resolve("sessions").resolve("reviewer.jsonl")));
    }
}
