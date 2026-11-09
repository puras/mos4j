package net.hegq.mos4j.tool;

import net.hegq.mos4j.tool.searchtools.Bm25Index;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可见性槽与 scope 原子换代的守护测试。
 *
 * <p>这两件能力是多来源工具面（MCP 等）的前提：visible 档要能挡住模型、
 * hidden 档要能被宿主程序化调用、换代要能在失败时保住上一代。
 */
class ToolRegistryScopeTest {

    private static net.hegq.mos4j.tool.Tool tool(String name) {
        return FunctionTool.of(name, "工具 " + name, NoArgs.class, args -> name + " 结果");
    }

    /** 空参数工具。 */
    public static final class NoArgs {
    }

    // ---- 可见性槽 ----

    @Test
    void aVisibilitySlotHidesAToolFromTheModelButNotFromTheHost() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read"));
        registry.setVisibilitySlot("mcp-hidden", name -> !name.equals("read"));

        assertFalse(registry.list().stream().anyMatch(t -> t.name().equals("read")),
                "被槽挡住的工具不进模型清单");
        assertFalse(registry.visibleNames().contains("read"));
        assertEquals("read 结果", registry.call("read", "{}"),
                "hidden 档工具仍可被宿主程序化调用");
    }

    @Test
    void visibilitySlotsAreAndedTogether() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read"));
        registry.setVisibilitySlot("policy", name -> true);
        registry.setVisibilitySlot("mcp-hidden", name -> false);

        assertFalse(registry.visibleTo("read"), "多层 AND：一层挡住就不可见");

        registry.setVisibilitySlot("mcp-hidden", null);
        assertTrue(registry.visibleTo("read"), "移除该层后恢复可见");
    }

    @Test
    void settingTheSameSlotTwiceReplacesTheWholeLayer() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read"));
        registry.setVisibilitySlot("policy", name -> false);
        assertFalse(registry.visibleTo("read"));

        registry.setVisibilitySlot("policy", name -> true);
        assertTrue(registry.visibleTo("read"), "同名槽整层替换，不叠加");
    }

    @Test
    void aSlotWithNoFilterIsRemovedByNull() {
        ToolRegistry registry = new ToolRegistry();
        registry.setVisibilitySlot("policy", name -> false);
        assertFalse(registry.visibleTo("anything"));

        registry.setVisibilitySlot("policy", null);
        assertTrue(registry.visibleTo("anything"));
        assertEquals(null, registry.visibilitySlot("policy"));
    }

    @Test
    void anEmptySlotNameIsRejected() {
        ToolRegistry registry = new ToolRegistry();
        assertThrows(ToolException.class, () -> registry.setVisibilitySlot(" ", name -> true));
    }

    @Test
    void invisibleDeferredToolsAreNotSearchable() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerDeferred(tool("read"), "读文件");
        registry.setVisibilitySlot("mcp-hidden", name -> !name.equals("read"));

        List<Bm25Index.Scored> hits = registry.deferredCatalog().search("read", 5);
        assertTrue(hits.isEmpty(), "看不见的工具不该被搜出来（搜到却调不动，更费劲）");
    }

    // ---- scope 原子换代 ----

    @Test
    void replaceSwapsTheWholeScope() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("old")), Set.of());

        assertTrue(registry.visibleNames().contains("old"));
        assertEquals(Set.of("mcp:a"), registry.scopes());

        registry.replace("mcp:a", List.of(tool("fresh")), Set.of());
        assertFalse(registry.visibleNames().contains("old"), "上一代不在新名单里的工具消失");
        assertTrue(registry.visibleNames().contains("fresh"));
    }

    @Test
    void aFailedReplacementLeavesThePreviousGenerationIntact() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("old")), Set.of());

        ToolException error = assertThrows(ToolException.class,
                () -> registry.replace("mcp:a", List.of(tool("new"), tool("new")), Set.of()));
        assertTrue(error.getMessage().contains("重名"));

        assertTrue(registry.visibleNames().contains("old"), "校验失败时上一代还在");
        assertFalse(registry.visibleNames().contains("new"), "校验失败时新工具没落盘");
    }

    @Test
    void aDeferredNameOutsideTheBatchIsRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("old")), Set.of());

        assertThrows(ToolException.class,
                () -> registry.replace("mcp:a", List.of(tool("new")), Set.of("ghost")));

        assertTrue(registry.visibleNames().contains("old"));
        assertFalse(registry.visibleNames().contains("new"));
    }

    @Test
    void scopesAreIsolatedFromEachOther() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("a1")), Set.of());
        registry.replace("mcp:b", List.of(tool("b1")), Set.of());

        registry.replace("mcp:a", List.of(tool("a2")), Set.of());

        assertTrue(registry.visibleNames().contains("a2"));
        assertFalse(registry.visibleNames().contains("a1"));
        assertTrue(registry.visibleNames().contains("b1"), "另一个 scope 不受影响");
        assertEquals(Set.of("a2"), registry.scopeToolNames("mcp:a"));
        assertEquals(Set.of("b1"), registry.scopeToolNames("mcp:b"));
    }

    @Test
    void aToolCannotBeTakenOverByAnotherScope() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("shared")), Set.of());

        assertThrows(ToolException.class,
                () -> registry.replace("mcp:b", List.of(tool("shared")), Set.of()));
        assertEquals(Set.of("shared"), registry.scopeToolNames("mcp:a"));
    }

    @Test
    void deferredToolsEnterTheCatalogAndCanBeActivated() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("big")), Set.of("big"));

        assertFalse(registry.visibleNames().contains("big"), "deferred 工具激活前不进清单");
        assertTrue(registry.deferredCatalog().entry("big") != null);

        registry.deferredCatalog().activate(List.of("big"));
        assertTrue(registry.visibleNames().contains("big"), "激活后进清单");
    }

    @Test
    void aRemovedDeferredToolLeavesTheCatalog() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("big")), Set.of("big"));
        registry.deferredCatalog().activate(List.of("big"));

        registry.replace("mcp:a", List.of(tool("other")), Set.of());

        assertEquals(null, registry.deferredCatalog().entry("big"), "消失的工具不能留在目录里");
        assertFalse(registry.deferredCatalog().isActive("big"), "激活态不能留着占检索位");
        assertTrue(registry.deferredCatalog().search("big", 5).isEmpty());
    }

    @Test
    void aToolThatStaysButDropsOutOfDeferredIsNoLongerSearchable() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("x")), Set.of("x"));

        registry.replace("mcp:a", List.of(tool("x")), Set.of());

        assertEquals(null, registry.deferredCatalog().entry("x"));
        assertTrue(registry.visibleNames().contains("x"), "降级为 direct 后直接进清单");
    }

    @Test
    void anEmptyReplacementClearsTheScope() {
        ToolRegistry registry = new ToolRegistry();
        registry.replace("mcp:a", List.of(tool("old")), Set.of());

        registry.replace("mcp:a", List.of(), Set.of());

        assertFalse(registry.visibleNames().contains("old"));
        assertEquals(Set.of(), registry.scopeToolNames("mcp:a"));
    }
}
