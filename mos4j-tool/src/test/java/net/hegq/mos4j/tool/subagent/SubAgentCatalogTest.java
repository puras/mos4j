package net.hegq.mos4j.tool.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理清单的发现、校验与渲染测试。
 *
 * <p><b>守护的不变式</b>：
 * <ul>
 *   <li><b>目录形态</b>：目录自己含 {@code AGENT.md} 就是子代理根；否则每个直接子目录是一个子代理；</li>
 *   <li><b>先到先得</b>：同名子代理保留先发现的，后来的被遮蔽并出诊断；</li>
 *   <li><b>降级而非静默丢</b>：坏定义、读不动的目录都进诊断；</li>
 *   <li><b>模型覆盖 fail-closed</b>：声明了宿主目录里没有的模型 → 装配期报错；</li>
 *   <li><b>清单预算降级</b>：先丢描述，再丢条目，丢了多少如实写进末尾提示。</li>
 * </ul>
 */
class SubAgentCatalogTest {

    /** 子代理目录根（项目内 test-tmp，不用系统临时目录）。 */
    private static Path newRoot() throws IOException {
        Path root = Path.of("target", "test-tmp", "subagent-" + System.nanoTime());
        Files.createDirectories(root);
        return root;
    }

    private static void write(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SubAgentCatalog.FILE_NAME), content);
    }

    private static SubAgentCatalog load(Path... dirs) {
        return SubAgentCatalog.load(SubAgentCatalog.Options.of(
                java.util.Arrays.stream(dirs).map(Path::toString).toList()));
    }

    @Test
    @DisplayName("目录自己含 AGENT.md：它就是子代理根（不再下钻）")
    void rootDirectoryItselfIsASubAgent() throws Exception {
        Path root = newRoot();
        write(root, "---\nname: root-agent\ndescription: 根目录子代理\n---\n你是根子代理。\n");
        write(root.resolve("child"), "---\nname: child\ndescription: 子目录子代理\n---\n子。\n");

        SubAgentCatalog catalog = load(root);

        assertEquals(1, catalog.len(), "根目录形态下不再下钻");
        assertEquals("root-agent", catalog.all().get(0).name());
    }

    @Test
    @DisplayName("每个直接子目录含 AGENT.md：各是一个子代理；裸 md 文件不算")
    void childDirectoriesAreSubAgents() throws Exception {
        Path root = newRoot();
        write(root.resolve("beta"), "---\nname: beta\ndescription: B\n---\nB 正文\n");
        write(root.resolve("alpha"), "---\nname: alpha\ndescription: A\n---\nA 正文\n");
        Files.writeString(root.resolve("loose.md"), "---\nname: loose\ndescription: 裸文件\n---\nx\n");

        SubAgentCatalog catalog = load(root);

        assertEquals(2, catalog.len(), "裸 md 文件没有「子代理目录」，不算子代理");
        assertEquals(List.of("alpha", "beta"), catalog.names(), "目录内按名排序（确定性输出）");
    }

    @Test
    @DisplayName("同名子代理：先到先得，后来者被遮蔽并出诊断")
    void duplicateNamesAreFirstComeFirstServed() throws Exception {
        Path first = newRoot();
        Path second = newRoot();
        write(first.resolve("agent"), "---\nname: same\ndescription: 先来的\n---\nx\n");
        write(second.resolve("agent"), "---\nname: same\ndescription: 后来的\n---\ny\n");

        SubAgentCatalog catalog = load(first, second);

        assertEquals(1, catalog.len());
        assertEquals("先来的", catalog.all().get(0).description());
        assertEquals(1, catalog.warnings().size(), catalog.warnings().toString());
        assertTrue(catalog.warnings().get(0).contains("重名"), catalog.warnings().toString());
        assertTrue(catalog.warnings().get(0).contains("先到先得"), catalog.warnings().toString());
    }

    @Test
    @DisplayName("坏定义：缺 frontmatter / 坏 YAML / 缺描述 / 非法名字 → 跳过并出诊断")
    void brokenDefinitionsAreSkippedWithDiagnostics() throws Exception {
        Path root = newRoot();
        write(root.resolve("no-front"), "没有 frontmatter 的正文\n");
        write(root.resolve("bad-yaml"), "---\nname: [unclosed\n---\n正文\n");
        write(root.resolve("no-desc"), "---\nname: nodesc\n---\n正文\n");
        write(root.resolve("bad-name"), "---\nname: Bad_Name\ndescription: 名字不合法\n---\n正文\n");
        write(root.resolve("ok"), "---\nname: ok\ndescription: 好的\n---\n正文\n");

        SubAgentCatalog catalog = load(root);

        assertEquals(1, catalog.len(), "只有好的那个装上");
        assertEquals("ok", catalog.all().get(0).name());
        assertEquals(4, catalog.warnings().size(), catalog.warnings().toString());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("缺少 frontmatter")),
                catalog.warnings().toString());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("frontmatter 解析失败")),
                catalog.warnings().toString());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("description 缺失")),
                catalog.warnings().toString());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("kebab-case")),
                catalog.warnings().toString());
    }

    @Test
    @DisplayName("name 缺失：回退到目录名")
    void missingNameFallsBackToDirectoryName() throws Exception {
        Path root = newRoot();
        write(root.resolve("fallback"), "---\ndescription: 没写名字\n---\n正文\n");

        SubAgentCatalog catalog = load(root);

        assertEquals(1, catalog.len());
        assertEquals("fallback", catalog.all().get(0).name());
    }

    @Test
    @DisplayName("清单渲染：名字 + 描述 + 工具白名单，包在 available_subagents 标签里")
    void catalogRender() throws Exception {
        Path root = newRoot();
        write(root.resolve("deploy"), "---\nname: deploy\ndescription: 发布流程\ntools:\n  - shell\n---\n正文\n");

        String text = load(root).render();

        assertTrue(text.startsWith("<available_subagents>"), text);
        assertTrue(text.contains("- deploy: 发布流程"), text);
        assertTrue(text.contains("（可用工具：shell）"), text);
        assertTrue(text.endsWith("</available_subagents>"), text);
    }

    @Test
    @DisplayName("清单超预算：先丢描述，再丢条目，末尾如实写明省略了多少")
    void catalogBudgetDegradesAndDiscloses() throws Exception {
        Path root = newRoot();
        StringBuilder fat = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            fat.append("描述很长很长很长很长很长很长很长很长很长很长。");
        }
        for (int i = 0; i < 6; i++) {
            write(root.resolve("agent" + i), "---\nname: agent" + i + "\ndescription: " + fat + "\n---\n正文\n");
        }
        SubAgentCatalog catalog = SubAgentCatalog.load(new SubAgentCatalog.Options(
                List.of(root.toString()), null, 400, null));

        String text = catalog.render();

        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 400,
                "渲染结果必须在预算内: " + text.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(text.contains("清单预算受限") || text.contains("因清单预算未列出"), text);
    }

    @Test
    @DisplayName("模型覆盖：声明了但宿主目录里没有 → 装配期报错（假能力不静默降级）")
    void modelOverrideMustExist() throws Exception {
        Path root = newRoot();
        write(root.resolve("agent"), "---\nname: agent\ndescription: D\nmodel: ghost\n---\n正文\n");

        SubAgentException thrown = assertThrows(SubAgentException.class,
                () -> SubAgentCatalog.load(new SubAgentCatalog.Options(
                        List.of(root.toString()), null, 0, modelId -> false)));
        assertTrue(thrown.getMessage().contains("ghost"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("假能力"), thrown.getMessage());
    }

    @Test
    @DisplayName("模型覆盖：宿主目录里有 → 装配通过")
    void modelOverridePresent() throws Exception {
        Path root = newRoot();
        write(root.resolve("agent"), "---\nname: agent\ndescription: D\nmodel: fast\n---\n正文\n");

        SubAgentCatalog catalog = SubAgentCatalog.load(new SubAgentCatalog.Options(
                List.of(root.toString()), null, 0, "fast"::equals));

        assertEquals(1, catalog.len());
    }

    @Test
    @DisplayName("指令正文现读：改了 AGENT.md 立刻生效，不必重扫")
    void instructionsAreReadOnDemand() throws Exception {
        Path root = newRoot();
        write(root.resolve("agent"), "---\nname: agent\ndescription: D\n---\n第一版正文\n");
        SubAgentCatalog catalog = load(root);
        SubAgentDefinition definition = catalog.lookup("agent");
        assertNotNull(definition);

        assertEquals("第一版正文", catalog.readInstructions(definition));
        write(root.resolve("agent"), "---\nname: agent\ndescription: D\n---\n第二版正文\n");
        assertEquals("第二版正文", catalog.readInstructions(definition), "正文现读，不驻留");
    }

    @Test
    @DisplayName("目录读不动：出诊断，其余照常装")
    void unreadableDirectoryIsDiagnosed() throws Exception {
        Path root = newRoot();
        write(root.resolve("agent"), "---\nname: agent\ndescription: D\n---\n正文\n");

        SubAgentCatalog catalog = SubAgentCatalog.load(SubAgentCatalog.Options.of(
                List.of(root.toString(), root.resolve("missing").toString())));

        assertEquals(1, catalog.len());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("读不动")),
                catalog.warnings().toString());
    }

    @Test
    @DisplayName("目录列表为空：清单为空、渲染空串、没有工具")
    void emptyCatalog() {
        SubAgentCatalog catalog = load();

        assertEquals(0, catalog.len());
        assertEquals("", catalog.render());
        assertTrue(catalog.tools((spec, context) -> SubAgentResult.completed("x", List.of()), null).isEmpty());
        assertNull(catalog.lookup("nope"));
        assertFalse(catalog.names().contains("nope"));
    }
}
