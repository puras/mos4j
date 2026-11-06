package net.hegq.mos4j.tool.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.hegq.mos4j.tool.skill.SkillFixtures.bytes;
import static net.hegq.mos4j.tool.skill.SkillFixtures.load;
import static net.hegq.mos4j.tool.skill.SkillFixtures.newRoot;
import static net.hegq.mos4j.tool.skill.SkillFixtures.write;
import static net.hegq.mos4j.tool.skill.SkillFixtures.writeSkill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能清单（发现 / 清单渲染 / 正文现读 / 重扫 / 用户手势）的语义测试（J7）。
 *
 * <p><b>验收口径是语义</b>（与 Go 参考版对齐）：这些测试钉的是
 * 「同名先到先得」「坏技能跳过并诊断」「正文调用时现读」「清单预算多级降级」
 * 「重扫幂等且差异明细完整」「用户手势只认首个 token」这些<b>行为</b>，
 * 不是某段实现的形状。期望值全部 Java 自算。
 */
class SkillCatalogTest {

    // ---- 发现 ----

    @Test
    @DisplayName("子目录里的技能被发现，按目录名排序（确定性输出）")
    void discoversSkillsInSubdirectories() throws IOException {
        Path root = newRoot();
        writeSkill(root, "beta", "B 技能", "正文B");
        writeSkill(root, "alpha", "A 技能", "正文A");

        SkillCatalog catalog = load(root);

        assertEquals(List.of("alpha", "beta"), catalog.modelVisibleNames());
        assertEquals(2, catalog.len());
        assertTrue(catalog.warnings().isEmpty(), catalog.warnings().toString());
        Skill alpha = catalog.lookup("alpha");
        assertNotNull(alpha);
        assertEquals("A 技能", alpha.description());
        assertEquals(root.resolve("alpha").toString(), alpha.dir());
        assertEquals(root.resolve("alpha").resolve("SKILL.md").toString(), alpha.bodyPath());
    }

    @Test
    @DisplayName("目录自己含 SKILL.md 时它就是技能根（不再下钻）")
    void directoryWithSkillFileIsItselfASkill() throws IOException {
        Path root = newRoot();
        Path skillDir = write(root.resolve("self"), "name: self\ndescription: 自带正文", "正文");
        // 该目录里再放一个「看起来像技能」的子目录：不该被当成第二个技能。
        writeSkill(skillDir, "nested", "嵌套技能", "正文");

        SkillCatalog catalog = load(skillDir);

        assertEquals(1, catalog.len());
        assertEquals(List.of("self"), catalog.modelVisibleNames());
    }

    @Test
    @DisplayName("裸 .md 文件不算技能（没有技能目录 = 资源基准无从谈起）")
    void bareMarkdownFileIsNotASkill() throws IOException {
        Path root = newRoot();
        Files.writeString(root.resolve("loose.md"), "---\nname: loose\ndescription: x\n---\n正文");

        SkillCatalog catalog = load(root);

        assertEquals(0, catalog.len());
    }

    @Test
    @DisplayName("同名技能先到先得：后面的目录跳过并诊断")
    void sameNameFirstDirectoryWins() throws IOException {
        Path first = newRoot().resolve("first");
        Path second = newRoot().resolve("second");
        writeSkill(first, "dup", "第一份", "正文1");
        writeSkill(second, "dup", "第二份", "正文2");

        SkillCatalog catalog = load(first.toString(), second.toString());

        assertEquals(1, catalog.len());
        assertEquals("第一份", catalog.lookup("dup").description(), "先到先得：保留先发现的那份");
        assertEquals(first.resolve("dup").toString(), catalog.lookup("dup").dir());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("重名")),
                "同名碰撞必须有诊断: " + catalog.warnings());
    }

    @Test
    @DisplayName("坏技能跳过并诊断，其余技能照常装上")
    void badSkillsAreSkippedWithDiagnostics() throws IOException {
        Path root = newRoot();
        writeSkill(root, "good", "好技能", "正文");
        // 没有 frontmatter
        Files.createDirectories(root.resolve("no-front"));
        Files.writeString(root.resolve("no-front").resolve("SKILL.md"), "直接是正文\n");
        // name 不合法
        write(root.resolve("bad-name"), "name: Bad Name\ndescription: x", "正文");
        // 缺 description
        write(root.resolve("no-desc"), "name: no-desc", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(List.of("good"), catalog.modelVisibleNames(), "坏技能不装");
        assertEquals(3, catalog.warnings().size(), "每个坏技能都要有一条诊断: " + catalog.warnings());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("缺少 frontmatter")));
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("不是合法 kebab-case")));
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("description 缺失")));
    }

    @Test
    @DisplayName("目录不存在只诊断不失败（读不动 = 没这个技能）")
    void missingDirectoryWarnsButDoesNotFail() throws IOException {
        Path root = newRoot();
        writeSkill(root, "real", "真技能", "正文");

        SkillCatalog catalog = load(root.resolve("nope").toString(), root.toString());

        assertEquals(List.of("real"), catalog.modelVisibleNames());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("读不动")), catalog.warnings().toString());
    }

    @Test
    @DisplayName("name 缺失时回退到目录名（PI 同款）")
    void missingNameFallsBackToDirectoryName() throws IOException {
        Path root = newRoot();
        write(root.resolve("fallback-name"), "description: 只有描述", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(List.of("fallback-name"), catalog.modelVisibleNames());
    }

    @Test
    @DisplayName("BOM 开头的技能文件仍能被发现")
    void bomAtStartIsTolerated() throws IOException {
        Path root = newRoot();
        Path dir = root.resolve("bom");
        Files.createDirectories(dir);
        String text = "---\nname: bom\ndescription: 带 BOM\n---\n正文";
        Files.write(dir.resolve("SKILL.md"),
                ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));

        SkillCatalog catalog = load(root);

        assertEquals(List.of("bom"), catalog.modelVisibleNames());
    }

    @Test
    @DisplayName("frontmatter 重复键按错误处理（对齐 Go yaml.v3）")
    void duplicateFrontmatterKeyIsRejected() throws IOException {
        Path root = newRoot();
        write(root.resolve("dup-key"), "name: first\nname: second\ndescription: x", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(0, catalog.len());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("不是合法 YAML")), catalog.warnings().toString());
    }

    @Test
    @DisplayName("裸 ~ 的 description 视同缺失（对齐 Go yaml.v3 的 nil）")
    void bareTildeDescriptionCountsAsMissing() throws IOException {
        Path root = newRoot();
        write(root.resolve("tilde"), "name: tilde\ndescription: ~", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(0, catalog.len(), "description 是 ~ 等于没写描述，必须 fail-closed");
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("description 缺失")), catalog.warnings().toString());
    }

    @Test
    @DisplayName("disable-model-invocation 的技能模型看不见（但清单里有）")
    void modelVisibleExcludesUserOnlySkills() throws IOException {
        Path root = newRoot();
        writeSkill(root, "visible", "模型可见", "正文");
        write(root.resolve("user-only"),
                "name: user-only\ndescription: 仅用户调用\ndisable-model-invocation: true", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(2, catalog.len(), "技能本身装上了（用户可以调）");
        assertEquals(List.of("visible"), catalog.modelVisibleNames());
        assertTrue(catalog.lookup("user-only").disableModelInvocation());
        assertFalse(catalog.render().contains("user-only"), "清单只列模型可见的: " + catalog.render());
    }

    @Test
    @DisplayName("allowed-tools 声明在装配期给出「不生效」诊断")
    void allowedToolsDeclarationIsDiagnosed() throws IOException {
        Path root = newRoot();
        write(root.resolve("scoped"),
                "name: scoped\ndescription: 带工具声明\nallowed-tools:\n  - read\n  - deploy", "正文");

        SkillCatalog catalog = load(root);

        assertEquals(List.of("read", "deploy"), catalog.lookup("scoped").allowedTools());
        assertTrue(catalog.warnings().stream().anyMatch(w -> w.contains("allowed-tools")),
                "声明了 allowed-tools 就要说清它不生效: " + catalog.warnings());
    }

    // ---- 清单渲染 ----

    @Test
    @DisplayName("清单包含名字、描述与加载指引")
    void renderListsNameDescriptionAndHowToLoad() throws IOException {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");

        String text = load(root).render();

        assertTrue(text.startsWith("<available_skills>"), text);
        assertTrue(text.stripTrailing().endsWith("</available_skills>"), text);
        assertTrue(text.contains("- deploy: 发布流程"), text);
        assertTrue(text.contains(SkillTool.NAME), "清单要说明用什么工具加载: " + text);
    }

    @Test
    @DisplayName("清单超预算：降级到只列技能名（带如实提示）")
    void renderFallsBackToNamesOnly() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "描述".repeat(20), "正文");
        writeSkill(root, "beta", "描述".repeat(20), "正文");

        String full = load(root).render();
        int budget = bytes(full) - 4;
        SkillCatalog squeezed = SkillCatalog.load(SkillCatalog.Options
                .of(List.of(root.toString())).limits(0, budget));
        String text = squeezed.render();

        assertTrue(bytes(text) <= budget, "渲染结果必须在预算内: " + bytes(text));
        assertTrue(text.contains("清单预算受限"), "降级要如实说明: " + text);
        assertTrue(text.contains("- alpha") && text.contains("- beta"), "名字都还在: " + text);
        assertFalse(text.contains("描述描述"), "这一级丢的是描述: " + text);
    }

    @Test
    @DisplayName("清单预算极小：退到最小形态（如实说明未列出）")
    void renderFallsBackToMinimalForm() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "描述", "正文");
        SkillCatalog probe = load(root);
        String full = probe.render();
        // 预算从「完整清单少一个字节」往下压，直到最小形态装得下、其他形态装不下。
        int budget = bytes(full) - 1;
        SkillCatalog squeezed = SkillCatalog.load(SkillCatalog.Options
                .of(List.of(root.toString())).limits(0, budget));

        String text = squeezed.render();

        assertTrue(bytes(text) <= budget, text);
        assertTrue(text.isEmpty() || text.contains("未列出") || text.contains("- alpha"),
                "降级结果只能是「完整 / 只名字 / 部分 / 最小形态 / 空」之一: [" + text + "]");
    }

    @Test
    @DisplayName("预算小到连标签都放不下：不注入清单（工具仍在）")
    void renderReturnsEmptyWhenBudgetTooSmall() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "描述", "正文");

        SkillCatalog squeezed = SkillCatalog.load(SkillCatalog.Options
                .of(List.of(root.toString())).limits(0, 10));

        assertEquals("", squeezed.render());
        assertEquals(1, squeezed.len(), "清单不注入不等于技能没了（工具仍在，模型按需调用）");
    }

    @Test
    @DisplayName("没有模型可见技能时清单是空串（不占系统提示词）")
    void renderIsEmptyWithoutVisibleSkills() throws IOException {
        Path root = newRoot();
        write(root.resolve("user-only"),
                "name: user-only\ndescription: 仅用户调用\ndisable-model-invocation: true", "正文");

        assertEquals("", load(root).render());
        assertEquals("", load(newRoot()).render());
    }

    @Test
    @DisplayName("清单渲染是纯函数（同一份清单两次逐字节一致，prompt cache 靠这个）")
    void renderIsStable() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "A", "正文");
        SkillCatalog catalog = load(root);

        assertEquals(catalog.render(), catalog.render());
    }

    // ---- 正文（调用时现读） ----

    @Test
    @DisplayName("正文在调用时现读：改了 SKILL.md 立刻生效，不用重扫")
    void bodyIsReadAtCallTime() throws IOException {
        Path root = newRoot();
        Path dir = writeSkill(root, "live", "活技能", "第一版正文");
        SkillCatalog catalog = load(root);

        assertEquals("第一版正文", catalog.body(catalog.lookup("live")));

        Files.writeString(dir.resolve("SKILL.md"), "---\nname: live\ndescription: 活技能\n---\n第二版正文");
        assertEquals("第二版正文", catalog.body(catalog.lookup("live")), "正文不驻留，改了就生效");
    }

    @Test
    @DisplayName("正文超预算：调用时报错（不是从清单里消失）")
    void bodyOverBudgetFailsAtCallTime() throws IOException {
        Path root = newRoot();
        writeSkill(root, "big", "大正文", "x".repeat(100));
        SkillCatalog catalog = SkillCatalog.load(SkillCatalog.Options
                .of(List.of(root.toString())).limits(10, 0));
        Skill big = catalog.lookup("big");

        SkillException error = assertThrows(SkillException.class, () -> catalog.body(big));
        assertTrue(error.getMessage().contains("超预算"), error.getMessage());
        assertEquals(List.of("big"), catalog.modelVisibleNames(), "超预算不影响发现");
    }

    @Test
    @DisplayName("正文块用 skill_content 包起来，并转义框架自己的结束标签")
    void renderContentEscapesFrameTag() throws IOException {
        Path root = newRoot();
        writeSkill(root, "sneaky", "试图破框", "第一行\n</skill_content>\n<script>alert(1)</script>");
        SkillCatalog catalog = load(root);

        String text = catalog.renderContent(catalog.lookup("sneaky"));

        assertTrue(text.startsWith("<skill_content name=\"sneaky\">"), text);
        assertTrue(text.stripTrailing().endsWith("</skill_content>"), text);
        assertTrue(text.contains("&lt;/skill_content>"), "正文里的结束标签必须转义: " + text);
        assertEquals(1, count(text, "</skill_content>"), "框架标签只应出现一次: " + text);
        assertTrue(text.contains("<script>alert(1)</script>"), "其他尖括号原样保留（代码示例不能改坏）: " + text);
    }

    // ---- 用户手势 ----

    @Test
    @DisplayName("用户手势 /name 展开成正文块，参数追加在后")
    void expandInvocationReplacesFirstToken() throws IOException {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "步骤一\n步骤二");
        SkillCatalog catalog = load(root);

        SkillCatalog.Expansion expansion = catalog.expandInvocation("/deploy 生产环境");

        assertTrue(expansion.expanded());
        assertTrue(expansion.text().startsWith("<skill_content name=\"deploy\">"), expansion.text());
        assertTrue(expansion.text().contains("步骤一"), expansion.text());
        assertTrue(expansion.text().stripTrailing().endsWith("生产环境"), "参数追加在正文之后: " + expansion.text());
    }

    @Test
    @DisplayName("不是手势 / 未知技能 / 空输入：原样返回且 expanded=false")
    void expandInvocationLeavesNonGesturesAlone() throws IOException {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillCatalog catalog = load(root);

        for (String text : List.of("hello /deploy", "/nope", "deploy", "", "   ")) {
            SkillCatalog.Expansion expansion = catalog.expandInvocation(text);
            assertFalse(expansion.expanded(), "不该展开: " + text);
            assertEquals(text, expansion.text());
        }
        SkillCatalog.Expansion nullText = catalog.expandInvocation(null);
        assertFalse(nullText.expanded());
        assertNull(nullText.text());
    }

    @Test
    @DisplayName("仅用户可调用的技能可以由用户手势展开（这正是该标记的用途）")
    void expandInvocationAllowsUserOnlySkill() throws IOException {
        Path root = newRoot();
        write(root.resolve("user-only"),
                "name: user-only\ndescription: 仅用户调用\ndisable-model-invocation: true", "用户秘籍正文");
        SkillCatalog catalog = load(root);

        SkillCatalog.Expansion expansion = catalog.expandInvocation("/user-only");

        assertTrue(expansion.expanded());
        assertTrue(expansion.text().contains("用户秘籍正文"), expansion.text());
    }

    @Test
    @DisplayName("手势命中但正文读不出来：报错而不是返回半截正文")
    void expandInvocationFailsWhenBodyIsBroken() throws IOException {
        Path root = newRoot();
        Path dir = writeSkill(root, "deploy", "发布流程", "正文");
        SkillCatalog catalog = load(root);
        // 装配后把 frontmatter 删掉 → 正文读不出来
        Files.writeString(dir.resolve("SKILL.md"), "没了 frontmatter");

        SkillException error = assertThrows(SkillException.class, () -> catalog.expandInvocation("/deploy"));
        assertTrue(error.getMessage().contains("已损坏"), error.getMessage());
    }

    // ---- 重扫 ----

    @Test
    @DisplayName("重扫幂等：目录没变就不换快照、不发通知")
    void reloadIsIdempotent() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "A", "正文");
        SkillCatalog catalog = load(root);

        SkillReloadResult result = catalog.reload();

        assertFalse(result.changed());
        assertEquals("", result.notice());
        assertTrue(result.added().isEmpty() && result.removed().isEmpty() && result.updated().isEmpty());
    }

    @Test
    @DisplayName("只改正文不算变化（digest 只含元数据）")
    void bodyOnlyChangeDoesNotCountAsChange() throws IOException {
        Path root = newRoot();
        Path dir = writeSkill(root, "alpha", "A", "第一版");
        SkillCatalog catalog = load(root);

        Files.writeString(dir.resolve("SKILL.md"), "---\nname: alpha\ndescription: A\n---\n第二版");

        assertFalse(catalog.reload().changed(), "正文不进摘要：改正文不算清单变化");
        assertEquals("第二版", catalog.body(catalog.lookup("alpha")), "但正文立刻生效");
    }

    @Test
    @DisplayName("重扫报告新增 / 移除 / 更新，并给出完整清单替换通知")
    void reloadReportsDiffAndFullCatalog() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "A", "正文");
        Path gone = writeSkill(root, "gone", "将被删除", "正文");
        SkillCatalog catalog = load(root);
        assertEquals(2, catalog.len());

        Files.delete(gone.resolve("SKILL.md"));
        Files.delete(gone);
        writeSkill(root, "beta", "B", "正文");
        SkillReloadResult result = catalog.reload();

        assertTrue(result.changed());
        assertEquals(List.of("beta"), result.added());
        assertEquals(List.of("gone"), result.removed());
        assertTrue(result.updated().isEmpty());
        assertTrue(result.notice().contains("技能目录已更新"), result.notice());
        assertTrue(result.notice().contains("beta"), result.notice());
        assertTrue(result.notice().contains("gone"), result.notice());
        // 通知里是「当前完整清单」（替换口径），不是增量
        assertTrue(result.notice().contains("<available_skills>"), result.notice());
        assertTrue(result.notice().contains("- alpha"), "旧技能仍在当前清单里: " + result.notice());
        assertEquals(List.of("alpha", "beta"), catalog.modelVisibleNames(), "快照已替换");
    }

    @Test
    @DisplayName("元数据变化算更新（描述改了就通知）")
    void metadataChangeCountsAsUpdate() throws IOException {
        Path root = newRoot();
        Path dir = writeSkill(root, "alpha", "旧描述", "正文");
        SkillCatalog catalog = load(root);

        write(dir, "name: alpha\ndescription: 新描述", "正文");
        SkillReloadResult result = catalog.reload();

        assertTrue(result.changed());
        assertEquals(List.of("alpha"), result.updated());
        assertEquals("新描述", catalog.lookup("alpha").description());
    }

    @Test
    @DisplayName("新增一个坏技能不打扰模型（诊断更新、技能集合不变）")
    void addingBadSkillDoesNotNotifyModel() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "A", "正文");
        SkillCatalog catalog = load(root);
        assertTrue(catalog.warnings().isEmpty());

        write(root.resolve("broken"), "name: Broken Name\ndescription: x", "正文");
        SkillReloadResult result = catalog.reload();

        assertFalse(result.changed(), "坏技能压根不在清单里，不该让模型重看清单");
        assertEquals(1, catalog.warnings().size(), "但诊断要新鲜: " + catalog.warnings());
    }

    // ---- allowed-tools 求交诊断 ----

    @Test
    @DisplayName("allowed-tools 求交：只报告当前看不见的工具，不改工具面")
    void checkAllowedToolsReportsMissingOnly() throws IOException {
        Path root = newRoot();
        write(root.resolve("scoped"),
                "name: scoped\ndescription: 带工具声明\nallowed-tools:\n  - read\n  - deploy", "正文");
        SkillCatalog catalog = load(root);
        catalog.setToolProbe(() -> List.of("read", "write", "skill"));

        SkillCatalog.AllowedToolsCheck check = catalog.checkAllowedTools(catalog.lookup("scoped"));

        assertEquals(List.of("deploy"), check.missing());
        assertTrue(check.notice().contains("deploy"), check.notice());
        assertFalse(check.notice().contains("read"), "看得见的工具不该出现在诊断里: " + check.notice());
    }

    @Test
    @DisplayName("没声明 allowed-tools 或没接探针：诊断为空")
    void checkAllowedToolsIsQuietWithoutDeclarationOrProbe() throws IOException {
        Path root = newRoot();
        writeSkill(root, "plain", "什么也没声明", "正文");
        write(root.resolve("scoped"),
                "name: scoped\ndescription: 带工具声明\nallowed-tools:\n  - deploy", "正文");

        SkillCatalog noProbe = load(root);
        assertTrue(noProbe.checkAllowedTools(noProbe.lookup("scoped")).missing().isEmpty());

        SkillCatalog withProbe = load(root);
        withProbe.setToolProbe(() -> List.of("read"));
        assertTrue(withProbe.checkAllowedTools(withProbe.lookup("plain")).missing().isEmpty());
        assertEquals(List.of("deploy"), withProbe.checkAllowedTools(withProbe.lookup("scoped")).missing());
    }

    @Test
    @DisplayName("toString 说明规模（排障时不至于只看到类名）")
    void toStringDescribesSize() throws IOException {
        Path root = newRoot();
        writeSkill(root, "alpha", "A", "正文");

        assertEquals("SkillCatalog{技能 1 个，模型可见 1 个}", load(root).toString());
    }

    /** 数一个子串出现次数（不用正则：{@code java.util.regex} 在 SDK 模块里是禁的）。 */
    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}