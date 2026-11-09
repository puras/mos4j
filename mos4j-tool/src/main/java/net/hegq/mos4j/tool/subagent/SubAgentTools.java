package net.hegq.mos4j.tool.subagent;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;
import net.hegq.mos4j.tool.searchtools.ToolSearchTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子代理的<b>工具面收窄</b>（白名单真正落地的地方）。
 *
 * <p>子代理拿到的是<b>新注册表</b>：里面只有「声明白名单 ∩ 父可见工具集」里的那些工具
 * ——子代理内部调用工具时，父注册表里它拿不到的工具<b>一律不存在</b>
 * （不是「调用了被拒」，是压根没这个工具）。
 *
 * <p><b>deny 优先</b>：父可见集已经过了宿主策略与可见性层，被挡掉的工具不在父可见集里，
 * 声明也拿不到它（<b>声明不能放大宿主策略</b>）。
 *
 * <p><b>恒定扣掉两类工具</b>（子代理的隔离规则）：
 * <ul>
 *   <li>{@code subagent__*}：深度 0——子代理不能再委派；</li>
 *   <li>{@code tool_search}：给了它，子代理就能自己激活 deferred 工具，工具面就不再是交集。</li>
 * </ul>
 */
public final class SubAgentTools {

    private SubAgentTools() {
    }

    /**
     * 子注册表的结果。
     *
     * @param tools   子代理可见的工具（按名字排序，确定性）
     * @param dropped 被挡掉的<b>声明项</b>（诊断用：告诉宿主「你声明的 shell 被宿主策略挡了」）
     */
    public record Child(List<Tool> tools, List<String> dropped) {
    }

    /**
     * 求子代理的<b>实际</b>工具面：声明白名单 ∩ 父可见工具集（deny 优先）。
     *
     * <ul>
     *   <li>声明白名单为空 → <b>不收窄</b>（继承父的可见集）；</li>
     *   <li>父可见工具集为空（宿主没给） → 退化为只按声明白名单（宿主自己兜底）；</li>
     *   <li>结果排序输出（确定性）。</li>
     * </ul>
     *
     * @param declared     声明白名单（可空 = 不收窄）
     * @param parentVisible 父当前可见的工具名（可空 = 空集）
     * @return 实际工具名（已排序）
     */
    public static List<String> resolve(List<String> declared, List<String> parentVisible) {
        List<String> parent = parentVisible == null ? List.of() : parentVisible;
        Set<String> parentSet = new LinkedHashSet<>();
        for (String name : parent) {
            if (name != null && !name.isBlank()) {
                parentSet.add(name.strip());
            }
        }
        if (declared == null || declared.isEmpty()) {
            List<String> out = new ArrayList<>(parentSet);
            Collections.sort(out);
            return List.copyOf(out);
        }
        List<String> out = new ArrayList<>();
        for (String name : declared) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.strip();
            if (parentSet.isEmpty() || parentSet.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        Collections.sort(out);
        return List.copyOf(out);
    }

    /**
     * 造子注册表（白名单落地）。
     *
     * @param parent   父注册表（可空 = 空子注册表）
     * @param declared 声明白名单（可空 = 不收窄）
     * @return 子注册表内容与被挡掉的声明项
     */
    public static Child child(ToolRegistry parent, List<String> declared) {
        List<Tool> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        if (parent == null) {
            for (String name : declared == null ? List.<String>of() : declared) {
                if (name != null && !name.isBlank()) {
                    dropped.add(name.strip());
                }
            }
            return new Child(List.of(), List.copyOf(dropped));
        }
        List<String> allowed = resolve(declared, parent.visibleNames());
        List<String> keptNames = new ArrayList<>();
        for (String name : allowed) {
            if (excludedFromChild(name)) {
                continue;
            }
            keptNames.add(name);
        }
        Set<String> keptSet = new LinkedHashSet<>(keptNames);
        for (String name : keptNames) {
            parent.get(name).ifPresent(kept::add);
        }
        for (String name : declared == null ? List.<String>of() : declared) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.strip();
            if (!keptSet.contains(trimmed)) {
                dropped.add(trimmed);
            }
        }
        return new Child(List.copyOf(kept), List.copyOf(dropped));
    }

    /**
     * 判断工具是否<b>恒定</b>排除在子代理工具面之外。
     *
     * <p>深度 0（子代理不能再委派）与「工具面不经 tool_search」落在造子注册表的地方
     * ——谁造子注册表都绕不过去。
     */
    public static boolean excludedFromChild(String name) {
        return name != null && (name.startsWith(SubAgentCatalog.TOOL_PREFIX)
                || ToolSearchTool.NAME.equals(name));
    }
}
