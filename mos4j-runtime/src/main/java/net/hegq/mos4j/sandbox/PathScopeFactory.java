package net.hegq.mos4j.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 路径强制工厂：按写根现造一个 {@link PathScope}。
 *
 * <p><b>为什么需要它</b>：动态路径强制（写根在运行期会变，见
 * {@link LocalRunner.Builder#pathScopeSupplier}）要求**每次调用**按当时的写根生成一份
 * 强制层。平台后端不同，生成方式也不同（macOS = Seatbelt profile，Linux = Landlock 规则集），
 * 所以「造 scope」这件事必须由平台代码决定，不能让调用方自己 if。
 *
 * <p><b>只造写根范围</b>：本层只约束写（读由文件 jail 负责），所以工厂只需要写根。
 */
@FunctionalInterface
public interface PathScopeFactory {

    /**
     * 按写根造一份路径强制。
     *
     * @param writeRoots 允许写入的根（非空、绝对路径）
     * @return 路径强制
     * @throws IllegalArgumentException 写根形态不合法（空 / 相对路径）
     */
    PathScope create(List<Path> writeRoots);
}
