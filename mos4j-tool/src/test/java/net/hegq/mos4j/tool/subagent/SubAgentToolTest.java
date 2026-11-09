package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.core.ToolContext;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理工具的形态与失败语义测试。
 *
 * <p><b>守护的不变式</b>：
 * <ul>
 *   <li><b>一个子代理 = 一个普通工具</b>：名字 {@code subagent__<name>}，参数只有
 *       {@code task}（必填）与 {@code context}（可选）；</li>
 *   <li><b>独占</b>：子代理内部要发模型请求，默认不参与并发；</li>
 *   <li><b>子代理层失败不是父轮次失败</b>：执行体抛错 / 超时 / 回执不合法 → 正常回执；</li>
 *   <li><b>框架层错误才失败</b>：参数不是 JSON、task 为空、没有执行体。</li>
 * </ul>
 */
class SubAgentToolTest {

    private static Path newRoot() throws IOException {
        Path root = Path.of("target", "test-tmp", "subagenttool-" + System.nanoTime());
        Files.createDirectories(root);
        return root;
    }

    private static SubAgentCatalog catalogWith(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SubAgentCatalog.FILE_NAME),
                "---\nname: " + name + "\ndescription: 干活的\n---\n你是 " + name + "。\n");
        return SubAgentCatalog.load(SubAgentCatalog.Options.of(List.of(root.toString())));
    }

    private static SubAgentTool tool(SubAgentCatalog catalog, SubAgentRunner runner, Duration timeout) {
        return new SubAgentTool(catalog.lookup(catalog.names().get(0)), catalog, runner, timeout);
    }

    @Test
    @DisplayName("工具名与说明：subagent__<name>，说明里写明回执要自己复核")
    void toolShape() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> SubAgentResult.completed("done", List.of()), null);

        assertEquals("subagent__deploy", tool.name());
        assertTrue(tool.description().contains("干活的"), tool.description());
        assertTrue(tool.description().contains("summary 是子代理自述"), tool.description());
        assertTrue(tool.description().contains("findings 是待复核的线索"), tool.description());
        assertEquals(SubAgentTool.Params.class, tool.parameterType());
    }

    @Test
    @DisplayName("参数 schema：task 必填、context 可选，没有多余字段")
    void parameterSchema() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> SubAgentResult.completed("done", List.of()), null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);

        Map<String, Object> schema = registry.list().get(0).parameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("task", "context"), List.copyOf(properties.keySet()));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("task"), required, "只有 task 必填");
    }

    @Test
    @DisplayName("默认独占：不声明并发安全")
    void toolIsExclusiveByDefault() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> SubAgentResult.completed("done", List.of()), null);

        assertFalse(tool.concurrencySafe("{\"task\":\"x\"}"),
                "子代理内部要发模型请求，默认独占（并发是显式 opt-in）");
    }

    @Test
    @DisplayName("task 为空：框架层错误（这次调用压根没跑起来）")
    void emptyTaskIsAFrameworkError() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> SubAgentResult.completed("done", List.of()), null);

        ToolException thrown = assertThrows(ToolException.class,
                () -> tool.execute("{\"task\":\"  \"}", ToolContext.none()));
        assertTrue(thrown.getMessage().contains("task 不能为空"), thrown.getMessage());
    }

    @Test
    @DisplayName("参数不是 JSON：框架层错误")
    void malformedArgsAreAFrameworkError() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> SubAgentResult.completed("done", List.of()), null);

        assertThrows(ToolException.class, () -> tool.execute("not json", ToolContext.none()));
    }

    @Test
    @DisplayName("执行体抛错：回一段 status=error 的正常回执（父轮次不失败）")
    void runnerFailureBecomesAnErrorReceipt() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> {
            throw new IllegalStateException("模型炸了");
        }, null);

        Object raw = tool.execute("{\"task\":\"干活\"}", ToolContext.none());

        SubAgentResult result = (SubAgentResult) raw;
        assertEquals(SubAgentStatus.ERROR, result.status());
        assertTrue(result.error().contains("模型炸了"), result.error());
        assertNull(result.validate());
    }

    @Test
    @DisplayName("超时：回一段 status=timeout 的正常回执")
    void timeoutBecomesATimeoutReceipt() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, (spec, context) -> {
            Thread.sleep(30_000);
            return SubAgentResult.completed("来不及了", List.of());
        }, Duration.ofMillis(200));

        long start = System.nanoTime();
        Object raw = tool.execute("{\"task\":\"干活\"}", ToolContext.none());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        SubAgentResult result = (SubAgentResult) raw;
        assertEquals(SubAgentStatus.TIMEOUT, result.status());
        assertTrue(result.error().contains("超时"), result.error());
        assertTrue(elapsedMillis < 10_000, "工具不等执行体：实际耗时 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("回执不合法：投影成 status=error 的回执（父模型看得见坏回执）")
    void invalidReceiptBecomesAnErrorReceipt() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        // status=error 却没带原因 → 回执不合法
        SubAgentTool broken = new SubAgentTool(catalog.lookup("deploy"), catalog,
                (spec, context) -> new SubAgentResult(SubAgentStatus.ERROR, "", List.of(), ""), null);

        Object raw = broken.execute("{\"task\":\"干活\"}", ToolContext.none());
        SubAgentResult result = (SubAgentResult) raw;
        assertEquals(SubAgentStatus.ERROR, result.status());
        assertTrue(result.error().contains("回执不合法"), result.error());
    }

    @Test
    @DisplayName("取消：执行体跟着停，回一段 error 回执（不抛异常）")
    void cancellationStopsTheRunner() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        AtomicReference<SubAgentResult> seen = new AtomicReference<>();
        CancellationToken token = CancellationToken.create();
        SubAgentTool tool = tool(catalog, (spec, context) -> {
            for (int i = 0; i < 200; i++) {
                if (context.cancellation() != null && context.cancellation().isCancelled()) {
                    seen.set(SubAgentResult.error("子代理已取消"));
                    return seen.get();
                }
                Thread.sleep(20);
            }
            return SubAgentResult.completed("没被取消", List.of());
        }, null);

        ToolContext context = ToolContext.of(1, "s1").withCancellation(token);
        token.cancel();

        Object raw = tool.execute("{\"task\":\"干活\"}", context);

        SubAgentResult result = (SubAgentResult) raw;
        assertEquals(SubAgentStatus.ERROR, result.status());
        assertTrue(result.error().contains("取消"), result.error());
    }

    @Test
    @DisplayName("没有执行体：框架层错误（fail-closed，不假装能跑）")
    void missingRunnerIsAFrameworkError() throws Exception {
        Path root = newRoot();
        SubAgentCatalog catalog = catalogWith(root, "deploy");
        SubAgentTool tool = tool(catalog, null, null);

        ToolException thrown = assertThrows(ToolException.class,
                () -> tool.execute("{\"task\":\"干活\"}", ToolContext.none()));
        assertTrue(thrown.getMessage().contains("没有可用的执行体"), thrown.getMessage());
    }

    @Test
    @DisplayName("清单为每个子代理造一个工具，顺序按清单顺序")
    void catalogCreatesOneToolPerSubAgent() throws Exception {
        Path root = newRoot();
        for (String name : List.of("alpha", "beta")) {
            Path dir = root.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(SubAgentCatalog.FILE_NAME),
                    "---\nname: " + name + "\ndescription: " + name + "\n---\n正文\n");
        }
        SubAgentCatalog catalog = SubAgentCatalog.load(SubAgentCatalog.Options.of(List.of(root.toString())));

        List<Tool> tools = catalog.tools((spec, context) -> SubAgentResult.completed("x", List.of()), null);

        assertEquals(2, tools.size());
        assertEquals("subagent__alpha", tools.get(0).name());
        assertEquals("subagent__beta", tools.get(1).name());
        assertNotNull(catalog.buildSpec(catalog.lookup("alpha"), "任务", "上下文"));
    }
}
