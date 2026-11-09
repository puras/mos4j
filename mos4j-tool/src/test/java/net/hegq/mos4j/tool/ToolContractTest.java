package net.hegq.mos4j.tool;

import net.hegq.mos4j.fs.ObserveGate;
import net.hegq.mos4j.fs.RootSet;
import net.hegq.mos4j.fs.SandboxedFs;
import net.hegq.mos4j.sandbox.LocalRunner;
import net.hegq.mos4j.tool.builtin.CurrentTimeTool;
import net.hegq.mos4j.tool.builtin.EditFileTool;
import net.hegq.mos4j.tool.builtin.GlobTool;
import net.hegq.mos4j.tool.builtin.GrantAccessTool;
import net.hegq.mos4j.tool.builtin.GrepTool;
import net.hegq.mos4j.tool.builtin.LsTool;
import net.hegq.mos4j.tool.builtin.ReadFileTool;
import net.hegq.mos4j.tool.builtin.ShellTool;
import net.hegq.mos4j.tool.builtin.WriteFileTool;
import net.hegq.mos4j.tool.searchtools.ActivateTool;
import net.hegq.mos4j.tool.searchtools.DeferredToolCatalog;
import net.hegq.mos4j.tool.searchtools.ToolSearchTool;
import net.hegq.mos4j.tool.skill.SkillCatalog;
import net.hegq.mos4j.tool.skill.SkillTool;
import net.hegq.mos4j.tool.web.SearchProvider;
import net.hegq.mos4j.tool.web.WebFetchTool;
import net.hegq.mos4j.tool.web.WebSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型可见工具契约守护（B13-1 / B13-2）。
 *
 * <p><b>为什么要有这道门</b>：工具名与参数名是<b>模型可见</b>的契约，改一个字母，
 * 模型就可能继续用旧名字调用（提示词里写着旧名字、旧名字进了历史会话），
 * 表现为「工具不存在」这类从模型侧冒出来的错。改名这件事本轮做过一次
 * （{@code read_file → read}），没有机器门禁就只能靠人记得住。
 *
 * <p><b>为什么不放在文档里</b>：文档不会自己变红。这里把清单冻结成代码，
 * 工具名或参数名一变，这道门立刻变红——和字段名冻结清单（{@code fieldnames.json}）
 * 是同一个思路：契约要能被机器守住。
 *
 * <p>清单口径：Java 侧模型可见工具共 14 个（其中 {@code skill} 是<b>条件注册</b>：
 * 只有存在模型可见技能时才进注册表——能力即证据，空枪只会骗模型多试一次）。
 * 与 Go 参考版一一对应，只在两处有意不同（{@code grant_access} 参数形状、deferred 两步式），
 * 见 {@code docs/contract/INVARIANT-TESTS.md} §3。
 */
class ToolContractTest {

    /** 冻结的模型可见工具名清单（顺序即注册顺序预期，不做强制）。 */
    private static final List<String> FROZEN_NAMES = List.of(
            "read",
            "write",
            "edit",
            "shell",
            "grep",
            "glob",
            "ls",
            "current_time",
            "grant_access",
            "tool_search",
            "activate",
            "web_search",
            "web_fetch",
            "skill");

    private Path base;
    private SandboxedFs fs;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(
                Path.of("target", "test-tmp", "contract-" + System.nanoTime()).toAbsolutePath());
        fs = new SandboxedFs(RootSet.of(base));
        ObserveGate gate = new ObserveGate();
        LocalRunner runner = LocalRunner.builder(base).build();
        DeferredToolCatalog catalog = new DeferredToolCatalog();
        SearchProvider provider = (query, limit) -> List.of();

        registry = new ToolRegistry();
        registry.register(new ReadFileTool(fs, gate));
        registry.register(new WriteFileTool(fs, gate));
        registry.register(new EditFileTool(fs, gate));
        registry.register(new ShellTool(runner));
        registry.register(new GrepTool(fs));
        registry.register(new GlobTool(fs));
        registry.register(new LsTool(fs));
        registry.register(new CurrentTimeTool());
        registry.register(new GrantAccessTool(RootSet.of(base), fs, (path, reason) -> true));
        registry.register(new ToolSearchTool(catalog));
        registry.register(new ActivateTool(catalog));
        registry.register(new WebSearchTool(provider));
        registry.register(new WebFetchTool());
        registry.register(new SkillTool(skillCatalog(base)));
    }

    /**
     * 造一份「有模型可见技能」的技能清单（条件注册的前提）。
     *
     * <p>没有可见技能时 SDK 不注册 {@code skill} 工具——那是另一个测试（driver 侧）。
     */
    private static SkillCatalog skillCatalog(Path base) throws IOException {
        Path dir = base.resolve("skills").resolve("demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: demo\ndescription: 契约用的示例技能\n---\n正文");
        return SkillCatalog.load(SkillCatalog.Options.of(List.of(base.resolve("skills").toString())));
    }

    @Test
    @DisplayName("模型可见工具名清单冻结（14 个，skill 为条件注册）")
    void toolNamesAreFrozen() {
        List<String> actual = registry.list().stream().map(net.hegq.mos4j.model.Tool::name).toList();

        assertEquals(FROZEN_NAMES, actual);
    }

    @Test
    @DisplayName("MCP 工具不进冻结清单（工具名是动态的，冻结了就不成立）")
    void mcpToolsAreNotInTheFrozenList() {
        assertTrue(FROZEN_NAMES.stream().noneMatch(name -> name.startsWith("mcp__")),
                "MCP 工具名随服务器与上游工具变化，不能进冻结清单");
    }

    @Test
    @DisplayName("MCP 资源工具不进冻结清单（按需模块的条件注册工具）")
    void mcpResourceToolsAreNotInTheFrozenList() {
        assertTrue(FROZEN_NAMES.stream().noneMatch(name -> name.startsWith("mcp_resource_")),
                "MCP 资源工具由按需模块条件注册（有资源的服务器才装），不能进冻结清单");
    }

    @Test
    @DisplayName("模型可见参数名清单冻结")
    void parameterNamesAreFrozen() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("read", List.of("path", "offset", "limit"));
        expected.put("write", List.of("path", "content"));
        expected.put("edit", List.of("path", "edits"));
        expected.put("shell", List.of("command", "description", "timeout"));
        expected.put("grep", List.of("pattern", "path", "glob", "limit"));
        expected.put("glob", List.of("pattern", "path", "limit"));
        expected.put("ls", List.of("path", "limit"));
        expected.put("current_time", List.of());
        expected.put("grant_access", List.of("mount", "dir", "reason"));
        expected.put("tool_search", List.of("query", "limit"));
        expected.put("activate", List.of("tools"));
        expected.put("web_search", List.of("query", "limit"));
        expected.put("web_fetch", List.of("url"));
        expected.put("skill", List.of("name"));

        for (net.hegq.mos4j.model.Tool tool : registry.list()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
            List<String> actual = new ArrayList<>(properties == null ? List.of() : properties.keySet());
            assertEquals(expected.get(tool.name()), actual, "工具 " + tool.name() + " 的参数名变了");
        }
    }

    @Test
    @DisplayName("每个工具的必填参数只有显式声明的那些")
    void requiredParametersAreExplicit() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("read", List.of("path"));
        expected.put("write", List.of("path", "content"));
        expected.put("edit", List.of("path", "edits"));
        expected.put("shell", List.of("command"));
        expected.put("grep", List.of("pattern"));
        expected.put("glob", List.of("pattern"));
        expected.put("ls", List.of());
        expected.put("current_time", List.of());
        expected.put("grant_access", List.of("mount", "dir"));
        expected.put("tool_search", List.of("query"));
        expected.put("activate", List.of("tools"));
        expected.put("web_search", List.of("query"));
        expected.put("web_fetch", List.of("url"));
        expected.put("skill", List.of("name"));

        for (net.hegq.mos4j.model.Tool tool : registry.list()) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.parameters().get("required");
            assertEquals(expected.get(tool.name()), required == null ? List.of() : required,
                    "工具 " + tool.name() + " 的必填参数变了");
        }
    }

    @Test
    @DisplayName("schema 不含 title / $defs（部分服务端会拒）")
    void schemasHaveNoTitleOrDefs() {
        for (net.hegq.mos4j.model.Tool tool : registry.list()) {
            assertTrue(!tool.parameters().containsKey("title"), tool.name() + " 的 schema 不该有 title");
            assertTrue(!tool.parameters().containsKey("$defs"), tool.name() + " 的 schema 不该有 $defs");
        }
    }
}
