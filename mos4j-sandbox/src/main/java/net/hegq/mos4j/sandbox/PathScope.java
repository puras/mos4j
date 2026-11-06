package net.hegq.mos4j.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 路径强制：把「能写哪些目录」落到进程上，而不是靠约定。
 *
 * <p><b>只能收窄，不能放开</b>：{@link #narrowTo(List)} 传入的根必须是当前范围的子集，
 * 否则直接报错。理由很简单——路径强制的价值全在「收窄」上，
 * 一个能随手放开的强制层等于没有。
 *
 * <p><b>写根为 {@code /} 是显式全开逃生门</b>：宿主确实需要「随便写」时显式写出来，
 * 并且这个状态会在 {@link #description()} 里显形，不藏着。
 *
 * <p><b>不支持的平台必须明确报错</b>：没有后端就报错，不做「假装有」的降级
 * （那样部署方会以为路径被强制了）。
 */
public interface PathScope {

    /**
     * 收窄允许写入的根。
     *
     * @param writeRoots 新的写入根（必须是当前范围的子集）
     * @return 收窄后的作用域
     * @throws IllegalArgumentException 试图放开范围
     */
    PathScope narrowTo(List<Path> writeRoots);

    /**
     * 把命令包进强制层。
     *
     * @param command 原始命令行
     * @return 包装后的命令行
     */
    List<String> wrap(List<String> command);

    /**
     * 当前允许写入的根。
     *
     * @return 根列表
     */
    List<Path> writeRoots();

    /**
     * 边界说明（进 {@code Describe}）。
     *
     * @return 说明文字
     */
    String description();
}
