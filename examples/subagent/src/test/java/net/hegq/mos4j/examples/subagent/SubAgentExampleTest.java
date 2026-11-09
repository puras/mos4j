package net.hegq.mos4j.examples.subagent;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.sdk.Mos4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理示例的用例：清单进提示词、子代理是普通工具、失败不掀桌。
 */
class SubAgentExampleTest {

    private Path tempDir() throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", "example-subagent-" + System.nanoTime()));
    }

    private Path subAgentDir() {
        return Path.of("agents");
    }

    @Test
    @DisplayName("装配：子代理清单进系统提示词")
    void catalogGoesIntoTheSystemPrompt() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("好"));

        Mos4j mos4j = SubAgentExample.assemble(dir.resolve("sessions"), model, subAgentDir());
        mos4j.run("s1", "审一下改动");

        String prompt = model.systemPromptOf(0);
        assertTrue(prompt.contains("reviewer"), "子代理清单进提示词: " + prompt);
        assertTrue(prompt.contains("researcher"), "子代理清单进提示词: " + prompt);
        assertFalse(prompt.contains("只读的审阅者"), "正文不进提示词（调用时现读）");
    }

    @Test
    @DisplayName("子代理是普通工具：模型看得见 subagent__<name>")
    void subAgentIsAPlainTool() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("好"));

        Mos4j mos4j = SubAgentExample.assemble(dir.resolve("sessions"), model, subAgentDir());
        mos4j.run("s1", "审一下改动");

        List<String> tools = model.toolNamesOf(0);
        assertTrue(tools.contains("subagent__reviewer"), "模型看得见子代理工具: " + tools);
        assertTrue(tools.contains("subagent__researcher"), "模型看得见子代理工具: " + tools);
    }

    @Test
    @DisplayName("派活：父模型点名 → 子代理跑 → 回执回到父轮次")
    void dispatchReturnsTheReceipt() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.toolCall("c1", "subagent__reviewer", "{\"task\":\"审一下改动\"}"),
                StubModel.text("{\"status\":\"completed\",\"summary\":\"没发现问题\",\"findings\":[],\"error\":null}"),
                StubModel.text("审阅结论：没发现问题"));

        Mos4j mos4j = SubAgentExample.assemble(dir.resolve("sessions"), model, subAgentDir());
        String reply = mos4j.run("s1", "审一下改动");

        assertEquals("审阅结论：没发现问题", reply);
    }

    @Test
    @DisplayName("子代理坏了也不掀桌：回执转成工具结果，父轮次继续")
    void brokenSubAgentDoesNotBreakTheTurn() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(
                StubModel.toolCall("c1", "subagent__reviewer", "{\"task\":\"审一下改动\"}"),
                // 子代理的最终回答不是合法回执 → 归一为 error 回执
                StubModel.text("我不知道你在说什么"),
                StubModel.text("子代理没给出可用结论"));

        Mos4j mos4j = SubAgentExample.assemble(dir.resolve("sessions"), model, subAgentDir());
        String reply = mos4j.run("s1", "审一下改动");

        assertEquals("子代理没给出可用结论", reply, "子代理失败只是工具结果，父轮次照常收尾");
    }

    @Test
    @DisplayName("快照：子代理名看得见")
    void namesAreVisible() throws Exception {
        Path dir = tempDir();
        Mos4j mos4j = SubAgentExample.assemble(dir.resolve("sessions"),
                StubModel.of(StubModel.text("好")), subAgentDir());

        assertEquals(List.of("researcher", "reviewer"), SubAgentExample.names(mos4j));
    }
}
