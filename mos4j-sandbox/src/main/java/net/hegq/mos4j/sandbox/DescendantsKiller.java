package net.hegq.mos4j.sandbox;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 默认的降级击杀器：按 {@code descendants()} 快照逐个强杀。
 *
 * <p><b>诚实声明边界</b>：
 * <ul>
 *   <li><b>非原子</b>：快照与杀之间有窗口；</li>
 *   <li><b>尽力而为</b>：快照之后 fork 出来的子孙会逃逸（杀父进程后子进程被 reparent，仍然活着）；</li>
 *   <li><b>不等价于 Go 的进程组杀</b>（{@code setpgid} + {@code kill(-pgid)}）：整组原子消失做不到。</li>
 * </ul>
 *
 * <p>这三条都写进 {@link #description()}，{@code Describe} 会原样报出去——
 * 部署方必须知道自己拿到的是哪种强度。
 */
public final class DescendantsKiller implements ProcessGroupKiller {

    @Override
    public void kill(Process process) {
        if (process == null) {
            return;
        }
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = handle.descendants().toList();
        // 先杀深的后杀浅的：父进程死了，子进程会被 reparent 给 init，杀不到
        descendants.stream()
                .sorted(Comparator.comparingInt(DescendantsKiller::depthOf).reversed())
                .forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
    }

    /** 估计深度（{@code ProcessHandle} 没有公开 depth()，自己沿 parent 链数）。 */
    private static int depthOf(ProcessHandle handle) {
        int depth = 0;
        Optional<ProcessHandle> parent = handle.parent();
        while (parent.isPresent() && depth < 64) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }

    @Override
    public String description() {
        List<String> parts = List.of(
                "进程组击杀=降级实现（DescendantsKiller）",
                "手段=descendants() 快照 + 逐个 destroyForcibly",
                "强度=尽力而为、非原子",
                "缺口=快照之后 fork 的子孙会逃逸",
                "对比=不等价于 Go 侧 setpgid + kill(-pgid) 的整组杀");
        return String.join("；", parts);
    }
}
