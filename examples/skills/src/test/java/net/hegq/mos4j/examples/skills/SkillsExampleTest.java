package net.hegq.mos4j.examples.skills;

import net.hegq.mos4j.examples.testkit.StubModel;
import net.hegq.mos4j.sdk.Mos4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能示例的用例：清单进提示词、正文现读、宿主手势就地展开。
 */
class SkillsExampleTest {

    private Path tempDir() throws Exception {
        return Files.createDirectories(Path.of("target", "test-tmp", "example-skills-" + System.nanoTime()));
    }

    private Path skillDir() {
        return Path.of("skills");
    }

    @Test
    @DisplayName("装配：技能清单进系统提示词（进门就看得见）")
    void catalogGoesIntoTheSystemPrompt() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("好"));

        Mos4j mos4j = SkillsExample.assemble(dir.resolve("sessions"), model, skillDir());
        mos4j.run("s1", "把 app 部署到测试环境");

        String prompt = model.systemPromptOf(0);
        assertTrue(prompt.contains("deploy"), "技能清单进提示词: " + prompt);
        assertTrue(prompt.contains("rollback"), "技能清单进提示词: " + prompt);
        assertFalse(prompt.contains("第 2 步失败就停"), "正文不进提示词（按需现读）");
    }

    @Test
    @DisplayName("技能清单是模型可见的工具：模型能点名读正文")
    void skillToolIsVisibleToTheModel() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("读完了"));

        Mos4j mos4j = SkillsExample.assemble(dir.resolve("sessions"), model, skillDir());
        mos4j.run("s1", "按 deploy 技能做");

        assertTrue(model.toolNamesOf(0).contains("skill"),
                "模型看得见 skill 工具: " + model.toolNamesOf(0));
    }

    @Test
    @DisplayName("宿主手势：/deploy 就地展开正文，不经过模型")
    void hostGestureExpandsTheBody() throws Exception {
        Path dir = tempDir();
        StubModel model = StubModel.of(StubModel.text("好"));

        Mos4j mos4j = SkillsExample.assemble(dir.resolve("sessions"), model, skillDir());
        var expansion = mos4j.agent().expandSkill("/deploy app 测试环境");

        assertTrue(expansion.expanded(), "手势应当命中技能");
        assertTrue(expansion.text().contains("冒烟"), "展开的是正文: " + expansion.text());
    }

    @Test
    @DisplayName("技能名清单可读")
    void namesAreListed() throws Exception {
        Path dir = tempDir();
        Mos4j mos4j = SkillsExample.assemble(dir.resolve("sessions"),
                StubModel.of(StubModel.text("好")), skillDir());

        assertEquals(java.util.List.of("deploy", "rollback"), SkillsExample.names(mos4j));
    }
}
