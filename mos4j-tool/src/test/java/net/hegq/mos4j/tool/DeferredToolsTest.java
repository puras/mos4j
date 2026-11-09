package net.hegq.mos4j.tool;

import net.hegq.mos4j.tool.searchtools.ActivateTool;
import net.hegq.mos4j.tool.searchtools.Bm25Index;
import net.hegq.mos4j.tool.searchtools.DeferredToolCatalog;
import net.hegq.mos4j.tool.searchtools.ToolSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.6 + T5.7：BM25 检索与 deferred 工具目录 / 注册表接线。
 */
class DeferredToolsTest {

    @Test
    @DisplayName("BM25：相关文档得分高于无关文档，按分数降序")
    void bm25RanksRelevantDocsFirst() {
        Bm25Index index = new Bm25Index();
        index.add("a", "read file content from disk 文件读取");
        index.add("b", "run shell command process 执行命令");
        index.add("c", "web search internet 搜索网页");

        List<Bm25Index.Scored> hits = index.search("文件", 3);

        assertFalse(hits.isEmpty());
        assertEquals("a", hits.get(0).id(), "含「文件」的文档排第一");
    }

    @Test
    @DisplayName("BM25：CJK bigram——两字词命中；单字查询按单字元命中")
    void cjkBigramMatches() {
        Bm25Index index = new Bm25Index();
        index.add("doc", "读取文件内容的工具");

        assertFalse(index.search("文件", 3).isEmpty(), "「文件」作为 bigram 应命中");
        assertFalse(index.search("读取", 3).isEmpty(), "「读取」作为 bigram 应命中");
        assertTrue(index.search("代码", 3).isEmpty(), "不含的词不硬凑");
    }

    @Test
    @DisplayName("分词：camelCase 拆词 + 词干 + 停用词")
    void tokenizerSplitsCamelCaseAndStems() {
        List<String> tokens = Bm25Index.tokenize("readFileContents the files");

        assertTrue(tokens.contains("file"));
        assertTrue(tokens.contains("content"), "camelCase 拆出 content；file 复数剥 s 后合并");
        assertFalse(tokens.contains("the"), "停用词被剔除");
    }

    @Test
    @DisplayName("deferred 登记后不进工具清单；激活后下一轮（list 时）出现")
    void deferredToolsAppearOnlyAfterActivation() {
        ToolRegistry registry = new ToolRegistry();
        Tool hidden = FunctionTool.of("hidden_tool", "一个延后加载的工具",
                Map.class, args -> "ok");
        registry.registerDeferred(hidden, "一个延后加载的工具（处理隐藏任务）");

        // 登记后：清单里没有 hidden_tool，但有自动注册的 tool_search / activate
        List<String> visible = registry.list().stream()
                .map(net.hegq.mos4j.model.Tool::name).toList();
        assertFalse(visible.contains("hidden_tool"), "未激活的 deferred 工具不进清单");
        assertTrue(visible.contains("tool_search"), "deferred 非空时自动注册 tool_search");
        assertTrue(visible.contains("activate"), "deferred 非空时自动注册 activate");
        assertTrue(registry.get("hidden_tool").isPresent(), "工具本身已登记（可执行）");

        // 搜索能找到它
        List<Bm25Index.Scored> hits = registry.deferredCatalog().search("隐藏任务", 5);
        assertFalse(hits.isEmpty(), "按描述能搜到");

        // 激活后：出现在清单（下一轮生效 = 下一次 list() 调用）
        registry.deferredCatalog().activate(List.of("hidden_tool"));
        List<String> after = registry.list().stream()
                .map(net.hegq.mos4j.model.Tool::name).toList();
        assertTrue(after.contains("hidden_tool"), "激活后进入清单");    }

    @Test
    @DisplayName("activate：未登记的工具名被忽略并在回执中说明")
    void activateIgnoresUnknownNames() throws Exception {
        DeferredToolCatalog catalog = new DeferredToolCatalog();
        catalog.register("known", "已知工具");

        ToolSearchTool search = new ToolSearchTool(catalog);
        ActivateTool activate = new ActivateTool(catalog);

        String result = (String) activate.execute("{\"tools\":[\"known\",\"ghost\"]}");
        assertTrue(result.contains("known"));
        assertTrue(result.contains("ghost"), "未知名要明说");
        assertTrue(catalog.isActive("known"));
        assertFalse(catalog.isActive("ghost"));

        // 搜索已激活的工具不再返回（避免模型重复激活）
        String searchOut = (String) search.execute("{\"query\":\"已知\"}");
        assertTrue(searchOut.contains("没有匹配的工具"));
    }

    @Test
    @DisplayName("历史工具名并入：会话恢复后激活集为空也能兜底")
    void historicalToolsAreAdmitted() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerDeferred(FunctionTool.of("old_tool", "历史工具",
                Map.class, args -> "ok"), "历史工具");

        assertFalse(registry.list().stream().anyMatch(t -> t.name().equals("old_tool")));

        registry.admitHistoricalTools(List.of("old_tool"));

        assertTrue(registry.list().stream().anyMatch(t -> t.name().equals("old_tool")),
                "历史工具名并入后进入清单");
    }

    @Test
    @DisplayName("tool_search：参数名是 limit（不是 topK），并按 limit 截断")
    void toolSearchUsesLimitParameter() throws Exception {
        DeferredToolCatalog catalog = new DeferredToolCatalog();
        catalog.register("alpha_tool", "读取文件内容");
        catalog.register("beta_tool", "写入文件内容");
        ToolSearchTool search = new ToolSearchTool(catalog);

        String out = (String) search.execute("{\"query\":\"文件\",\"limit\":1}");

        assertTrue(out.contains("alpha_tool") || out.contains("beta_tool"), out);
        assertFalse(out.contains("alpha_tool") && out.contains("beta_tool"),
                "limit=1 只回一条: " + out);

        Map<String, Object> schema = SchemaGenerator.generate(ToolSearchTool.Params.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("limit"), schema.toString());
        assertFalse(properties.containsKey("topK"), "模型可见参数名不应再是 topK: " + schema);
    }

    @Test
    @DisplayName("deferred 重名登记 fail-closed")
    void duplicateDeferredRegistrationFails() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerDeferred(FunctionTool.of("dup", "第一个", Map.class, a -> 1), "第一个");

        assertThrows(ToolException.class,
                () -> registry.registerDeferred(FunctionTool.of("dup", "第二个", Map.class, a -> 2), "第二个"));
    }

    @Test
    @DisplayName("deferred 集为空时不注册 tool_search")
    void noHelpersWithoutDeferredTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(FunctionTool.of("plain", "普通工具", Map.class, a -> 1));

        List<String> names = registry.list().stream()
                .map(net.hegq.mos4j.model.Tool::name).toList();
        assertFalse(names.contains("tool_search"));
        assertFalse(names.contains("activate"));
    }
}
