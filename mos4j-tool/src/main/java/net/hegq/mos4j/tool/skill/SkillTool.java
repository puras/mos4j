package net.hegq.mos4j.tool.skill;

import net.hegq.mos4j.core.SkillAudit;
import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能工具：按 name 返回技能正文。
 *
 * <p>参数只有 {@code name}（模型按名调用）。正文经<b>普通工具结果</b>进模型上下文 →
 * 进历史；走普通注册表 = 同受守卫、策略、审批与 tool/call / tool/result 约束
 * （<b>没有旁路</b>——技能正文改变模型行为，不该绕开安全判定）。
 *
 * <p><b>审计先于状态变更</b>：留痕写失败就不返回正文（fail-closed）——正文已经进了
 * 模型上下文却查不到「加载了哪个技能」，事后没人能解释这轮的行为。
 */
public final class SkillTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "skill";

    private final SkillCatalog catalog;
    private final AtomicReference<SkillAudit> audit = new AtomicReference<>();
    private final AtomicReference<SkillState> state = new AtomicReference<>(new SkillState());

    /**
     * 参数。
     *
     * @param name 技能名（见系统提示词的 available_skills 清单）
     */
    public record Params(
            @ToolParam(desc = "技能名（见系统提示词的 available_skills 清单）", required = true) String name) {
    }

    /**
     * 构造器。
     *
     * @param catalog 技能清单
     */
    public SkillTool(SkillCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("tool: skill 工具需要技能清单");
        }
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按 name 加载技能正文（技能清单见系统提示词的 <" + SkillCatalog.CATALOG_TAG + ">）。"
                + "正文是该技能的操作说明，加载后按里面的步骤执行；"
                + "用不到的技能别加载——正文占上下文。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = SkillArgs.parse(argsJson, Params.class);
        String name = params.name() == null ? "" : params.name().strip();
        if (name.isEmpty()) {
            throw new ToolException("skill 失败：缺少参数 name");
        }
        Skill skill = catalog.lookup(name);
        if (skill == null) {
            // 目录可能已经重扫过（技能被删/改名）：提示模型目录已更新，
            // 而不是让它以为技能从来不存在。
            throw new ToolException("技能 \"" + name + "\" 不在当前技能目录里"
                    + "（目录可能已更新；可用技能见系统提示词的 " + SkillCatalog.CATALOG_TAG + " 清单）");
        }
        if (skill.disableModelInvocation()) {
            // fail-closed：标记为「仅用户显式调用」的技能，模型不许加载。
            throw new ToolException("技能 \"" + name + "\" 仅限用户显式调用"
                    + "（disable-model-invocation），模型不可加载");
        }
        String content;
        try {
            // 正文现读：改了 SKILL.md 立刻生效，不重扫、不重启。
            // 读失败 → 不加载（半截指令比没指令更糟）。
            content = catalog.renderContent(skill);
        } catch (SkillException e) {
            throw new ToolException("skill 失败：" + e.getMessage(), e);
        }
        // allowed-tools × 可见工具集 求交诊断（只诊断不收窄）：技能要的工具当前看不见
        // 就记一笔——把「装饰字段」变成真诊断。框架不改工具面，诊断也不进给模型的
        // 返回值（模型看不见自己的工具面，说了也无从行动）。
        SkillCatalog.AllowedToolsCheck check = catalog.checkAllowedTools(skill);
        if (!check.missing().isEmpty()) {
            SkillAudit current = audit.get();
            if (current != null) {
                try {
                    current.allowedToolsMismatch(skill.name(), check.missing());
                } catch (IOException ignored) {
                    // 诊断记不上不拦加载：缺了这条只损失可观测性，不损失安全性。
                }
            }
        }
        // 留痕：先事件、后状态。审计写失败就不该留下「已激活」的痕迹
        //（状态变了、正文没返回 = 一笔糊涂账）。
        SkillAudit current = audit.get();
        if (current != null) {
            try {
                current.activate(skill.name(), skill.description(), skill.dir());
            } catch (IOException e) {
                // fail-fast：技能正文会改变模型行为，没有留痕的加载等于没发生过的加载。
                throw new ToolException("记技能激活事件失败: " + e.getMessage(), e);
            }
        }
        state.get().activate(skill.name());
        // 返回值用 TreeMap：键序与 Go 的 map 序列化一致（Go 按键排序输出）。
        Map<String, Object> result = new TreeMap<>();
        result.put("name", skill.name());
        result.put("dir", skill.dir() == null ? "" : skill.dir());
        result.put("content", content);
        return result;
    }

    /**
     * 装上本轮的审计器（驱动每轮装一次）。
     *
     * <p><b>为什么是可换的槽</b>：审计出口是<b>会话级</b>的（事件要写进当前会话的日志），
     * 而工具实例是<b>长生命</b>的（挂在注册表上）。把审计器做成可换的槽，
     * 就不用把会话塞进工具（工具不该知道会话是什么）。
     *
     * @param skillAudit 审计器（可空 = 不留痕）
     */
    public void setAudit(SkillAudit skillAudit) {
        audit.set(skillAudit);
    }

    /**
     * 装上本轮的激活状态（驱动每轮装一份新的）。
     *
     * @param skillState 本轮状态（可空 = 用工具自己的）
     */
    public void setState(SkillState skillState) {
        state.set(skillState == null ? new SkillState() : skillState);
    }

    /**
     * 本轮已激活的技能名（按激活顺序）。
     *
     * @return 技能名列表
     */
    public List<String> activated() {
        return state.get().activated();
    }
}
