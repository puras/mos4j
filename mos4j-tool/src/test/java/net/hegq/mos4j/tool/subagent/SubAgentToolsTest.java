package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理工具面收窄与回执解析的测试。
 *
 * <p><b>守护的不变式</b>：
 * <ul>
 *   <li><b>交集而非放大</b>：子代理可用工具 = 声明白名单 ∩ 父当前可见工具集；</li>
 *   <li><b>父面是上限</b>：宿主策略挡掉的工具不在父可见集里，声明也拿不到它；</li>
 *   <li><b>深度 0</b>：子代理不能调子代理，也不能用 {@code tool_search} 扩容；</li>
 *   <li><b>非 JSON 答案不丢内容</b>：整段降级成 {@code summary}，框架仍给固定结构。</li>
 * </ul>
 */
class SubAgentToolsTest {

    @Test
    @DisplayName("白名单交集：父看不见的工具不会因为声明而出现")
    void whitelistIsIntersectedWithTheParentSurface() {
        List<String> parent = List.of("read", "write", "shell");

        List<String> resolved = SubAgentTools.resolve(List.of("read", "shell", "net"), parent);

        assertEquals(List.of("read", "shell"), resolved, "net 父面没有，不能出现");
    }

    @Test
    @DisplayName("没声明白名单：继承父面")
    void emptyDeclarationInheritsTheParentSurface() {
        List<String> parent = List.of("read", "write", "shell");

        List<String> resolved = SubAgentTools.resolve(List.of(), parent);

        assertEquals(List.of("read", "shell", "write"), resolved);
    }

    @Test
    @DisplayName("父可见集为空：退化成只按声明白名单（宿主自己兜底）")
    void emptyParentSurfaceFallsBackToTheDeclaration() {
        List<String> resolved = SubAgentTools.resolve(List.of("read", "shell"), List.of());

        assertEquals(List.of("read", "shell"), resolved);
    }

    @Test
    @DisplayName("子注册表：只保留可用工具，深度固定为 0")
    void childRegistryKeepsOnlyAllowedTools() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(stub("read"));
        parent.register(stub("shell"));
        parent.register(stub("subagent__deploy"));
        parent.register(stub("tool_search"));

        SubAgentTools.Child child = SubAgentTools.child(parent, List.of("read"));

        assertEquals(List.of("read"), child.tools().stream().map(Tool::name).toList());
        assertTrue(child.dropped().contains("read") == false, child.dropped().toString());
        assertTrue(SubAgentTools.excludedFromChild("subagent__x"));
        assertTrue(SubAgentTools.excludedFromChild("tool_search"));
        assertFalse(SubAgentTools.excludedFromChild("read"));
    }

    @Test
    @DisplayName("子注册表：声明了父面没有的工具 → 记成被挡掉（诊断，不静默）")
    void childRegistryReportsDroppedDeclarations() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(stub("read"));

        SubAgentTools.Child child = SubAgentTools.child(parent, List.of("read", "net"));

        assertEquals(List.of("read"), child.tools().stream().map(Tool::name).toList());
        assertEquals(List.of("net"), child.dropped());
    }

    @Test
    @DisplayName("子注册表：恒定排除 subagent__* 与 tool_search（哪怕父面有）")
    void childRegistryAlwaysExcludesDelegationAndSearch() {
        ToolRegistry parent = new ToolRegistry();
        parent.register(stub("read"));
        parent.register(stub("subagent__deploy"));
        parent.register(stub("tool_search"));

        SubAgentTools.Child child = SubAgentTools.child(parent, List.of());

        assertEquals(List.of("read"), child.tools().stream().map(Tool::name).toList());
    }

    @Test
    @DisplayName("回执解析：标准 JSON")
    void parsePlainJson() {
        SubAgentResult result = SubAgentReceipt.parse(
                "{\"status\":\"completed\",\"summary\":\"搞定\",\"findings\":[{\"ref\":\"a.java\",\"summary\":\"可疑\"}]}");

        assertEquals(SubAgentStatus.COMPLETED, result.status());
        assertEquals("搞定", result.summary());
        assertEquals(1, result.findings().size());
        assertEquals("a.java", result.findings().get(0).ref());
        assertEquals("可疑", result.findings().get(0).summary());
    }

    @Test
    @DisplayName("回执解析：带代码围栏的 JSON 也能认")
    void parseFencedJson() {
        SubAgentResult result = SubAgentReceipt.parse(
                "```json\n{\"status\":\"error\",\"summary\":\"部分完成\",\"error\":\"缺权限\"}\n```");

        assertEquals(SubAgentStatus.ERROR, result.status());
        assertEquals("缺权限", result.error());
    }

    @Test
    @DisplayName("回执解析：非 JSON 整段降为 summary（内容不丢）")
    void plainTextBecomesSummary() {
        SubAgentResult result = SubAgentReceipt.parse("我看了一下，没发现问题");

        assertEquals(SubAgentStatus.COMPLETED, result.status());
        assertEquals("我看了一下，没发现问题", result.summary());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    @DisplayName("回执解析：空答案也有一段 summary")
    void emptyAnswerBecomesSummary() {
        SubAgentResult result = SubAgentReceipt.parse("   ");

        assertEquals(SubAgentStatus.COMPLETED, result.status());
        assertFalse(result.summary().isEmpty());
    }

    @Test
    @DisplayName("回执解析：非法 status 转成 error（fail-closed）")
    void unknownStatusBecomesError() {
        SubAgentResult result = SubAgentReceipt.parse("{\"status\":\"weird\",\"summary\":\"x\"}");

        assertEquals(SubAgentStatus.ERROR, result.status());
        assertFalse(result.error().isEmpty());
    }

    @Test
    @DisplayName("回执校验：completed 不要求 error，error/timeout 必须带原因")
    void validationRules() {
        assertNull(new SubAgentResult(SubAgentStatus.COMPLETED, "s", List.of(), "").validate());
        assertTrue(new SubAgentResult(SubAgentStatus.ERROR, "s", List.of(), "").validate() != null);
        assertTrue(new SubAgentResult(SubAgentStatus.TIMEOUT, "s", List.of(), "").validate() != null);
        assertTrue(new SubAgentResult(SubAgentStatus.COMPLETED, "s",
                List.of(new SubAgentFinding("a.java", "")), "").validate() != null,
                "findings 缺 summary → 不合法");
    }

    private static Tool stub(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Class<?> parameterType() {
                return Map.class;
            }

            @Override
            public String execute(String argsJson) {
                return "ok";
            }
        };
    }
}
