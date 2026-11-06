package net.hegq.mos4j.policy;

import com.google.re2j.Pattern;
import net.hegq.mos4j.hook.Guard;
import net.hegq.mos4j.hook.GuardDecision;
import net.hegq.mos4j.hook.GuardEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 名字级策略：按工具名放行 / 拒绝。
 *
 * <p><b>三个语义（容易搞混，逐条说清）</b>：
 * <ul>
 *   <li><b>Deny 优先</b>：命中拒绝名单的一律拒绝，哪怕它也在放行名单里；</li>
 *   <li><b>Allow 未配置 = 不限制</b>：没设放行名单 = 不按名字拦任何工具（只受 Deny 影响）；</li>
 *   <li><b>Allow 配了空集 = 全禁</b>：这是「我明确什么都不许」，和「我还没配」是两回事。
 *       把这两种空语义混成一个，宿主想「先全禁着」时就会意外放开一切。</li>
 * </ul>
 *
 * <p><b>通配符</b>：{@code *} 可出现在模式里，且<b>整串锚定</b>
 * ——{@code fs.*} 匹配 {@code fs.read}，<b>不</b>匹配 {@code x.fs.read}；
 * {@code *} 匹配一切；不含 {@code *} 的模式要求<b>全等</b>。
 * 锚定不是细节：{@code *.sh} 若不锚定会匹配 {@code evil.sh}，看起来更严，
 * 实际是按后缀放行——正好放错了方向。
 *
 * <p><b>可见性与判定用同一份名单</b>：{@link #visible} 给 UI 看的可调用工具列表，
 * 与 {@link #allows} 的判定结果必须一致。两份名单各写一遍，
 * 就会出现「UI 里看不到但模型能调」或反之——前者是审计缺口，后者是误导。
 */
public final class ToolNamePolicy implements Guard {

    private final List<Pattern> allow;
    private final List<Pattern> deny;

    private ToolNamePolicy(List<Pattern> allow, List<Pattern> deny) {
        this.allow = allow;
        this.deny = deny;
    }

    /**
     * 不限制（只受后续叠加的 Deny 影响）。
     *
     * @return 策略
     */
    public static ToolNamePolicy unrestricted() {
        return new ToolNamePolicy(null, List.of());
    }

    /**
     * 用名单构造。
     *
     * @param allow 放行名单；{@code null} = 未配置（不限制）；空集合 = 全禁
     * @param deny  拒绝名单（可空）
     * @return 策略
     */
    public static ToolNamePolicy of(Collection<String> allow, Collection<String> deny) {
        return new ToolNamePolicy(allow == null ? null : compile(allow), deny == null ? List.of() : compile(deny));
    }

    /**
     * 叠加拒绝名单。
     *
     * @param names 工具名模式
     * @return 新策略（原策略不变）
     */
    public ToolNamePolicy withDeny(Collection<String> names) {
        List<Pattern> merged = new ArrayList<>(deny);
        merged.addAll(compile(names));
        return new ToolNamePolicy(allow, merged);
    }

    /**
     * 叠加放行名单（把「未配置」变成「已配置」）。
     *
     * @param names 工具名模式
     * @return 新策略（原策略不变）
     */
    public ToolNamePolicy withAllow(Collection<String> names) {
        return new ToolNamePolicy(compile(names), deny);
    }

    /**
     * 是否允许调用这个工具。
     *
     * @param toolName 工具名
     * @return true = 允许
     */
    public boolean allows(String toolName) {
        String name = toolName == null ? "" : toolName;
        if (matches(deny, name)) {
            return false;
        }
        if (allow == null) {
            return true;
        }
        return matches(allow, name);
    }

    /**
     * 可调用工具列表（与 {@link #allows} 同一份名单）。
     *
     * @param all 全部工具名
     * @return 允许调用的那些（保持入参顺序）
     */
    public List<String> visible(Collection<String> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<String> visible = new ArrayList<>();
        for (String name : all) {
            if (allows(name)) {
                visible.add(name);
            }
        }
        return List.copyOf(visible);
    }

    @Override
    public GuardDecision check(GuardEvent event) {
        if (event == null) {
            return GuardDecision.deny("守卫事件为空");
        }
        String name = event.toolName();
        if (matches(deny, name)) {
            return GuardDecision.deny("工具 " + name + " 在拒绝名单中");
        }
        if (allow != null && !matches(allow, name)) {
            return GuardDecision.deny("工具 " + name + " 不在放行名单中");
        }
        return GuardDecision.allow();
    }

    private static boolean matches(List<Pattern> patterns, String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 编译模式：{@code *} → 任意串，整串锚定。 */
    private static List<Pattern> compile(Collection<String> names) {
        List<Pattern> patterns = new ArrayList<>(names.size());
        for (String name : names) {
            patterns.add(compile(name));
        }
        return List.copyOf(patterns);
    }

    private static Pattern compile(String name) {
        String raw = name == null ? "" : name;
        StringBuilder regex = new StringBuilder();
        int start = 0;
        int index;
        while ((index = raw.indexOf('*', start)) >= 0) {
            regex.append(Pattern.quote(raw.substring(start, index))).append(".*");
            start = index + 1;
        }
        regex.append(Pattern.quote(raw.substring(start)));
        return Pattern.compile(regex.toString());
    }
}
