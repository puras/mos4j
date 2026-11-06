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
 */
public record SandboxBoundary(String workingDirectory, String maxTimeout, String killerDescription,
                               List<String> limitsApplied, List<String> limitsSkipped,
                               String pathScopeDescription, List<String> environmentKeys) {

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
                runner.pathScope() == null ? null : runner.pathScope().description(),
                runner.environment().keySet().stream().sorted().toList());
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
            caveats.add("路径强制=未装配（沙箱里的进程可以写 jail 之外的目录；"
                    + "文件工具仍受文件 jail 约束，但 shell 不受）");
        } else {
            caveats.add("路径强制=" + pathScopeDescription);
        }
        return caveats;
    }
}
