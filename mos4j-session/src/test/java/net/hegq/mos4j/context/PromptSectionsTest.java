package net.hegq.mos4j.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.7 提示词分节装配：中心顺序、add/set/complete 语义、装配期只渲染一次。
 */
class PromptSectionsTest {

    @Test
    @DisplayName("中心顺序固定：base → environment → context → instructions → tools")
    void canonicalOrderIsFixed() {
        PromptSections sections = PromptSections.builder()
                .add("tools", "T")
                .add("instructions", "I")
                .add("context", "C")
                .add("environment", "E")
                .add("base", "B")
                .build();

        assertEquals(List.of("base", "environment", "context", "instructions", "tools"),
                sections.sections().stream().map(PromptSection::name).toList());
        String rendered = sections.render();
        assertTrue(rendered.indexOf("## base") < rendered.indexOf("## environment"), rendered);
        assertTrue(rendered.indexOf("## environment") < rendered.indexOf("## context"), rendered);
        assertTrue(rendered.indexOf("## context") < rendered.indexOf("## instructions"), rendered);
        assertTrue(rendered.indexOf("## instructions") < rendered.indexOf("## tools"), rendered);
    }

    @Test
    @DisplayName("add 是严格插入：重名报错")
    void addRejectsDuplicates() {
        PromptSections.Builder builder = PromptSections.builder().add("base", "B");

        assertThrows(IllegalStateException.class, () -> builder.add("base", "B2"));
    }

    @Test
    @DisplayName("set 是覆写：不存在的段报错（不静默新建）")
    void setRejectsMissingSections() {
        PromptSections.Builder builder = PromptSections.builder();

        assertThrows(IllegalStateException.class, () -> builder.set("base", "B"));
    }

    @Test
    @DisplayName("set 覆写已有段")
    void setOverwritesExistingSection() {
        PromptSections sections = PromptSections.builder()
                .add("base", "旧")
                .set("base", "新")
                .build();

        assertEquals("新", sections.content("base"));
    }

    @Test
    @DisplayName("complete 是整段替换")
    void completeReplacesTheWholeSection() {
        PromptSections sections = PromptSections.builder()
                .add("base", "旧内容")
                .complete("base", "全新内容")
                .build();

        assertEquals("全新内容", sections.content("base"));
    }

    @Test
    @DisplayName("clone 派生独立副本：改副本不影响原件")
    void cloneIsIndependent() {
        PromptSections.Builder original = PromptSections.builder().add("base", "原");

        PromptSections.Builder copy = original.clone();
        copy.set("base", "改");

        assertNotSame(original, copy);
        assertEquals("原", original.build().content("base"));
        assertEquals("改", copy.build().content("base"));
    }

    @Test
    @DisplayName("装配期只渲染一次：结果可复用，逐字节一致")
    void renderIsStable() {
        PromptSections sections = PromptSections.builder()
                .add("base", "B")
                .add("context", "C")
                .build();

        String first = sections.render();
        String second = sections.render();

        assertEquals(first, second);
        assertTrue(first.startsWith("## base"), first);
    }

    @Test
    @DisplayName("宿主自定义段排在标准段之后，不抢位置")
    void customSectionsComeLast() {
        PromptSections sections = PromptSections.builder()
                .add("custom", "X")
                .add("base", "B")
                .build();

        assertEquals(List.of("base", "custom"),
                sections.sections().stream().map(PromptSection::name).toList());
    }

    @Test
    @DisplayName("空段不产出多余空行")
    void emptySectionDoesNotAddBlankLines() {
        PromptSections sections = PromptSections.builder()
                .add("base", "B")
                .add("context", "")
                .build();

        assertFalse(sections.render().contains("\n\n\n"), sections.render());
    }

    @Test
    @DisplayName("skills 落在 context 之后、instructions 之前（prompt cache 的关键）")
    void skillsGoesBetweenContextAndInstructions() {
        assertEquals(List.of("base", "environment", "context", "skills", "mcp_servers", "subagents",
                        "instructions"),
                PromptSections.CANONICAL_ORDER.subList(0, 7),
                "中心顺序冻结：base/environment/context/skills/mcp_servers/subagents/instructions");
    }

    @Test
    @DisplayName("mcp_servers 落在 skills 之后、instructions 之前")
    void mcpServersGoesBetweenSkillsAndInstructions() {
        String system = "## base\nB\n\n## environment\nE\n\n## context\nC\n\n## skills\nS"
                + "\n\n## instructions\nI\n\n## tools\nT";

        String result = PromptSections.insert(system, "mcp_servers", "## mcp_servers\nM");

        assertTrue(result.indexOf("## skills") < result.indexOf("## mcp_servers"), result);
        assertTrue(result.indexOf("## mcp_servers") < result.indexOf("## instructions"), result);
    }

    @Test
    @DisplayName("insert 把技能段插到中心位置（不改动其他段）")
    void insertPutsSkillsInCanonicalPosition() {
        String system = "## base\nB\n\n## environment\nE\n\n## context\nC\n\n## instructions\nI\n\n## tools\nT";

        String result = PromptSections.insert(system, "skills", "## skills\nS");

        assertTrue(result.indexOf("## context") < result.indexOf("## skills"), result);
        assertTrue(result.indexOf("## skills") < result.indexOf("## instructions"), result);
        assertTrue(result.contains("## base\nB"), "原有段原样保留：" + result);
        assertTrue(result.contains("## tools\nT"), result);
    }

    @Test
    @DisplayName("insert 在没有后继段时追加到末尾")
    void insertAppendsWhenNoLaterSectionExists() {
        String system = "## base\nB";

        String result = PromptSections.insert(system, "skills", "## skills\nS");

        assertTrue(result.indexOf("## base") < result.indexOf("## skills"), result);
        assertTrue(result.startsWith("## base\nB"), result);
    }

    @Test
    @DisplayName("insert 对宿主整串提示词生效（不经 Builder）")
    void insertWorksWithoutBuilder() {
        String host = "You are MoS4J.\n\n## tools\nread/write";

        String result = PromptSections.insert(host, "skills", "## skills\nS");

        assertTrue(result.startsWith("You are MoS4J."), "宿主前缀一字不改：" + result);
        assertTrue(result.indexOf("## skills") < result.indexOf("## tools"), "提示词缓存靠位置：" + result);
    }

    @Test
    @DisplayName("insert：空内容不改原文；空段名报错")
    void insertRejectsBlankNameAndIgnoresEmptyContent() {
        assertEquals("## base\nB", PromptSections.insert("## base\nB", "skills", ""));
        assertEquals("## base\nB", PromptSections.insert("## base\nB", "skills", null));
        assertEquals("", PromptSections.insert(null, "skills", ""));
        assertThrows(IllegalArgumentException.class, () -> PromptSections.insert("## base\nB", " ", "x"));
        assertThrows(IllegalArgumentException.class, () -> PromptSections.insert("## base\nB", null, "x"));
    }

    @Test
    @DisplayName("insert 是纯函数：同输入同输出，且不改隔壁段")
    void insertIsPure() {
        String system = "## base\nB\n\n## context\nC\n\n## tools\nT";

        String first = PromptSections.insert(system, "skills", "## skills\nS");
        String second = PromptSections.insert(system, "skills", "## skills\nS");

        assertEquals(first, second);
        assertTrue(first.contains("## context\nC"));
    }

    @Test
    @DisplayName("非标准段插在所有标准段之后")
    void insertCustomSectionGoesLast() {
        String system = "## base\nB\n\n## tools\nT";

        String result = PromptSections.insert(system, "extra", "## extra\nX");

        assertTrue(result.indexOf("## tools") < result.indexOf("## extra"), result);
    }
}
