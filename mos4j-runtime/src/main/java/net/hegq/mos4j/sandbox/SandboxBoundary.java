package net.hegq.mos4j.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱边界的<b>诚实快照</b>：装了什么 + 哪里是降级的。
 *
 * <p>同样由 sandbox 模块自己掌握事实（工作目录、超时上界、击杀强度、资源上限、路径强制），
 * 驱动层只负责把它写进 {@code Describe}。
 *
 * @param workingDirectory   宿主固定的工作目录
 * @param maxTimeout         超时上界
 * @param killerDescription  进程组击杀强度说明
 * @param limitsApplied      生效的资源上限
 * @param limitsSkipped      被跳过的资源上限
 * @param pathScopeDescription 路径强制说明（null = 未装配）
 * @param environmentKeys    透传给子进程的环境变量名
 * @param pathScopeAbsentReason 未装配路径强制的原因（null = 没说明原因；非 null 时是宿主显式关闭之类）
 */
public record SandboxBoundary(String workingDirectory, String maxTimeout, String killerDescription,
                               List<String> limitsApplied, List<String> limitsSkipped,
                               String pathScopeDescription, List<String> environmentKeys,
                               String pathScopeAbsentReason) {

    /**
     * 从实际的运行器生成快照。
     *
     * @param runner 运行器
     * @return 快照
     */
    public static SandboxBoundary of(LocalRunner runner) {
        return new SandboxBoundary(
                runner.workingDirectory().toString(),
                runner.maxTimeout().toString(),
                runner.killer().description(),
                runner.limits().applied(),
                runner.limits().skipped(),
                scopeDescription(runner),
                runner.environment().keySet().stream().sorted().toList(),
                runner.pathScopeAbsentReason());
    }

    /**
     * 路径强制的快照说明。
     *
     * <p><b>为什么动态装配也要说「已装配」</b>：动态模式下 {@code pathScope()} 是空的
     * （写根每次现取），照着空值报「未装配」就是假话——命令其实被强制着。
     * Describe 说的必须是事实：装了、怎么装的、边界在哪。
     *
     * @param runner 运行器
     * @return 说明（null = 未装配）
     */
    private static String scopeDescription(LocalRunner runner) {
        if (runner.pathScope() != null) {
            return runner.pathScope().description();
        }
        if (runner.pathScopeSupplier() != null) {
            return "路径强制=已装配（动态写根：每次命令按当前授权的根现取）";
        }
        return null;
    }

    /**
     * 已装配的能力（进 Describe 的 features）。
     *
     * @return 说明列表
     */
    public List<String> features() {
        List<String> features = new ArrayList<>();
        features.add("命令沙箱=已装配；工作目录=" + workingDirectory + "（宿主固定，模型改不了）");
        features.add("超时=min(请求值 ?? 缺省值, " + maxTimeout + ")");
        features.add("环境=白名单裁剪后透传：" + environmentKeys);
        if (!limitsApplied.isEmpty()) {
            features.add("资源上限=" + limitsApplied);
        }
        return features;
    }

    /**
     * 降级与边界（进 Describe 的 caveats）。
     *
     * @return 说明列表
     */
    public List<String> caveats() {
        List<String> caveats = new ArrayList<>();
        caveats.add("进程组击杀=" + killerDescription);
        if (!limitsSkipped.isEmpty()) {
            caveats.add("资源上限（部分被跳过）=" + limitsSkipped);
        }
        if (pathScopeDescription == null) {
            String why = pathScopeAbsentReason == null || pathScopeAbsentReason.isBlank()
                    ? "" : "（原因：" + pathScopeAbsentReason + "）";
            caveats.add("路径强制=未装配" + why + "（沙箱里的进程可以写 jail 之外的目录；"
                    + "文件工具仍受文件 jail 约束，但 shell 不受）；平台后端状态=" + PathScopes.status());
        } else {
            // description 自带「路径强制=」前缀（两个后端都是），别拼成「路径强制=路径强制=…」
            caveats.add(pathScopeDescription.startsWith("路径强制=")
                    ? pathScopeDescription : "路径强制=" + pathScopeDescription);
        }
        return caveats;
    }
}
