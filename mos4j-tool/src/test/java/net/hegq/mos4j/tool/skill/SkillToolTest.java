package net.hegq.mos4j.tool.skill;

import net.hegq.mos4j.core.SkillAudit;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.hegq.mos4j.tool.skill.SkillFixtures.load;
import static net.hegq.mos4j.tool.skill.SkillFixtures.newRoot;
import static net.hegq.mos4j.tool.skill.SkillFixtures.write;
import static net.hegq.mos4j.tool.skill.SkillFixtures.writeSkill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能工具（{@code skill(name)}）的语义测试（J7）。
 *
 * <p><b>关键不变式</b>：
 * <ul>
 *   <li><b>只读不写</b>：工具只按 name 返回正文，没有「技能级工具收窄」这种第二套安全真相；</li>
 *   <li><b>审计先于状态变更</b>：留痕写失败就不返回正文（fail-closed）；</li>
 *   <li><b>mismatch 只诊断不拦</b>：诊断记不上不阻止加载；</li>
 *   <li><b>仅用户可调用的技能模型不可加载</b>（{@code disable-model-invocation}）。</li>
 * </ul>
 */
class SkillToolTest {

    /** 记录调用的假审计器。 */
    private static final class RecordingAudit implements SkillAudit {

        private final List<String> activations = new ArrayList<>();
        private final List<String> mismatches = new ArrayList<>();
        private boolean failActivate;
        private boolean failMismatch;

        @Override
        public void activate(String name, String description, String dir) throws IOException {
            if (failActivate) {
                throw new IOException("日志写不动了");
            }
            activations.add(name);
        }

        @Override
        public void allowedToolsMismatch(String name, List<String> missingAllowed) throws IOException {
            if (failMismatch) {
                throw new IOException("诊断写不动了");
            }
            mismatches.add(name + ":" + String.join(",", missingAllowed));
        }
    }

    @Test
    @DisplayName("按 name 返回技能正文与目录")
    void loadsBodyByName() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "步骤一\n步骤二");
        SkillTool tool = new SkillTool(load(root));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute("{\"name\":\"deploy\"}");

        assertEquals("deploy", result.get("name"));
        assertEquals(root.resolve("deploy").toString(), result.get("dir"));
        String content = (String) result.get("content");
        assertTrue(content.startsWith("<skill_content name=\"deploy\">"), content);
        assertTrue(content.contains("步骤一"), content);
        assertEquals(List.of("content", "dir", "name"), new ArrayList<>(result.keySet()),
                "键序与 Go 的 map 序列化（按 key 排序）一致");
    }

    @Test
    @DisplayName("技能名首尾空白被容忍（模型抄错空格不该失败两次）")
    void trimsName() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute("{\"name\":\"  deploy  \"}");

        assertEquals("deploy", result.get("name"));
    }

    @Test
    @DisplayName("未知技能：报错并提示目录可能已更新")
    void unknownSkillSaysCatalogMayHaveChanged() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        ToolException error = assertThrows(ToolException.class,
                () -> tool.execute("{\"name\":\"nope\"}"));

        assertTrue(error.getMessage().contains("不在当前技能目录里"), error.getMessage());
        assertTrue(error.getMessage().contains("目录可能已更新"), "别让模型以为技能从来不存在: " + error.getMessage());
    }

    @Test
    @DisplayName("缺少 name：报错（不是空字符串兜底）")
    void missingNameFails() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        for (String args : List.of("{}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "null")) {
            assertThrows(ToolException.class, () -> tool.execute(args), "参数: " + args);
        }
    }

    @Test
    @DisplayName("仅用户可调用的技能：模型加载 fail-closed")
    void userOnlySkillCannotBeLoadedByModel() throws Exception {
        Path root = newRoot();
        write(root.resolve("secret"),
                "name: secret\ndescription: 仅用户调用\ndisable-model-invocation: true", "机密正文");
        SkillTool tool = new SkillTool(load(root));

        ToolException error = assertThrows(ToolException.class,
                () -> tool.execute("{\"name\":\"secret\"}"));

        assertTrue(error.getMessage().contains("仅限用户显式调用"), error.getMessage());
        assertFalse(error.getMessage().contains("机密正文"), "拒绝时不能把正文带出来");
    }

    @Test
    @DisplayName("审计先于状态变更：留痕写失败就不返回正文")
    void auditFailureBlocksLoading() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));
        RecordingAudit audit = new RecordingAudit();
        audit.failActivate = true;
        tool.setAudit(audit);

        ToolException error = assertThrows(ToolException.class,
                () -> tool.execute("{\"name\":\"deploy\"}"));

        assertTrue(error.getMessage().contains("记技能激活事件失败"), error.getMessage());
        assertTrue(tool.activated().isEmpty(), "没留痕就没发生过加载，状态也不该变");
    }

    @Test
    @DisplayName("激活留痕：技能名、描述、目录都进事件")
    void activationIsRecorded() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));
        RecordingAudit audit = new RecordingAudit();
        tool.setAudit(audit);

        tool.execute("{\"name\":\"deploy\"}");

        assertEquals(List.of("deploy"), audit.activations);
        assertEquals(List.of("deploy"), tool.activated());
        assertTrue(audit.mismatches.isEmpty(), "没声明 allowed-tools 就不该有 mismatch 诊断");
    }

    @Test
    @DisplayName("allowed-tools 求交缺工具：记诊断，但照常加载（只诊断不拦）")
    void mismatchIsDiagnosedButDoesNotBlockLoading() throws Exception {
        Path root = newRoot();
        write(root.resolve("scoped"),
                "name: scoped\ndescription: 带工具声明\nallowed-tools:\n  - read\n  - deploy", "正文");
        SkillCatalog catalog = load(root);
        catalog.setToolProbe(() -> List.of("read"));
        SkillTool tool = new SkillTool(catalog);
        RecordingAudit audit = new RecordingAudit();
        tool.setAudit(audit);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute("{\"name\":\"scoped\"}");

        assertEquals("scoped", result.get("name"), "诊断不拦加载");
        assertEquals(List.of("scoped:deploy"), audit.mismatches);
        assertEquals(List.of("scoped"), audit.activations);
        assertFalse(((String) result.get("content")).contains("deploy"),
                "诊断不进给模型的结果（模型看不见自己的工具面）");
    }

    @Test
    @DisplayName("mismatch 记不上不拦加载（丢的是可观测性，不是安全性）")
    void mismatchAuditFailureIsSwallowed() throws Exception {
        Path root = newRoot();
        write(root.resolve("scoped"),
                "name: scoped\ndescription: 带工具声明\nallowed-tools:\n  - deploy", "正文");
        SkillCatalog catalog = load(root);
        catalog.setToolProbe(() -> List.of("read"));
        SkillTool tool = new SkillTool(catalog);
        RecordingAudit audit = new RecordingAudit();
        audit.failMismatch = true;
        tool.setAudit(audit);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute("{\"name\":\"scoped\"}");

        assertEquals("scoped", result.get("name"));
        assertEquals(List.of("scoped"), audit.activations, "激活留痕仍然要成功");
    }

    @Test
    @DisplayName("没有审计器也能加载（SDK 不强制宿主接审计）")
    void worksWithoutAudit() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute("{\"name\":\"deploy\"}");

        assertEquals("deploy", result.get("name"));
        assertEquals(List.of("deploy"), tool.activated());
    }

    @Test
    @DisplayName("每轮换状态：激活记录不跨轮泄漏（驱动每轮装新的一份）")
    void stateIsPerRound() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        tool.execute("{\"name\":\"deploy\"}");
        assertEquals(List.of("deploy"), tool.activated());

        tool.setState(new SkillState());

        assertTrue(tool.activated().isEmpty(), "换轮后「本轮已激活」是空的");
    }

    @Test
    @DisplayName("重复激活同名技能：只记一次（幂等）")
    void repeatedActivationIsIdempotent() throws Exception {
        Path root = newRoot();
        writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));

        tool.execute("{\"name\":\"deploy\"}");
        tool.execute("{\"name\":\"deploy\"}");

        assertEquals(List.of("deploy"), tool.activated());
    }

    @Test
    @DisplayName("正文读不出来：报错且不返回半截内容")
    void brokenBodyFails() throws Exception {
        Path root = newRoot();
        Path dir = writeSkill(root, "deploy", "发布流程", "正文");
        SkillTool tool = new SkillTool(load(root));
        Files.writeString(dir.resolve("SKILL.md"), "没有 frontmatter 了");

        ToolException error = assertThrows(ToolException.class,
                () -> tool.execute("{\"name\":\"deploy\"}"));

        assertTrue(error.getMessage().contains("已损坏"), error.getMessage());
    }

    @Test
    @DisplayName("工具自描述：名字、参数名、必填项、说明里的清单标签")
    void toolMetadata() throws Exception {
        SkillTool skillTool = new SkillTool(load(newRoot()));
        ToolRegistry registry = new ToolRegistry();
        registry.register(skillTool);
        net.hegq.mos4j.llm.model.Tool declared = registry.list().get(0);

        assertEquals("skill", declared.name());
        assertTrue(declared.description().contains(SkillCatalog.CATALOG_TAG), declared.description());

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) declared.parameters().get("properties");
        assertEquals(List.of("name"), new ArrayList<>(properties.keySet()));
        assertEquals(List.of("name"), declared.parameters().get("required"));
    }

    @Test
    @DisplayName("新建的轮级状态没有已激活技能")
    void freshStateHasNoActivations() {
        assertTrue(new SkillState().activated().isEmpty());
    }
}