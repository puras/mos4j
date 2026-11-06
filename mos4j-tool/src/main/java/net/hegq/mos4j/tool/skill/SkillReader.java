package net.hegq.mos4j.tool.skill;

import java.io.IOException;
import java.util.List;

/**
 * 技能目录的 I/O 能力（框架不直接碰文件系统，宿主注入）。
 *
 * <p><b>为什么注入而不是直接用 NIO</b>：技能目录列表本身就是宿主声明的授权范围，
 * 框架不替宿主决定「能读哪儿」。要 jail 语义（只许读声明的目录内）就注入自己的实现；
 * 不注入时用 {@link OsSkillReader} 读本地文件（宿主已经显式给了目录）。
 *
 * <p>两个动作够用：读文件、列目录。
 */
public interface SkillReader {

    /**
     * 读文件内容。
     *
     * @param path 文件路径
     * @return 文件字节
     * @throws IOException 读不动（文件不存在也走这里）
     */
    byte[] readFile(String path) throws IOException;

    /**
     * 列目录项（不递归）。
     *
     * @param path 目录路径
     * @return 目录项（顺序不重要，实现自己决定）
     * @throws IOException 目录读不动
     */
    List<Entry> readDir(String path) throws IOException;

    /**
     * 目录项。
     *
     * @param name      项名（不含路径）
     * @param directory 是否是子目录
     */
    record Entry(String name, boolean directory) {
    }
}
