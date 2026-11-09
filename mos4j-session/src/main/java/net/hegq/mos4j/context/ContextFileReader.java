package net.hegq.mos4j.context;

import java.io.IOException;

/**
 * 上下文文件的读取入口（宿主注入）。
 *
 * <p><b>为什么框架不直接读文件</b>：发现规则（候选名优先级、向上递归、
 * 同目录 override、真实路径去重、字节预算）是纯逻辑，可以脱离文件系统测；
 * 而「怎么读」「读哪几个根」是宿主的事——同一个上下文模块，
 * CLI 宿主读本地目录，服务端宿主读对象存储里的虚拟目录。
 *
 * <p>实现必须做到：文件不存在返回 {@code null}（不是抛异常），
 * 否则「这个目录没有 AGENTS.md」会被当成故障。
 */
public interface ContextFileReader {

    /**
     * 读文件。
     *
     * @param path 文件路径
     * @return 文件内容；不存在返回 {@code null}
     * @throws IOException 读取失败
     */
    byte[] readIfExists(String path) throws IOException;

    /**
     * 这个路径是不是目录。
     *
     * <p>缺省 true（不判）：框架不假设宿主的文件系统；宿主知道自己的命名空间时覆写它。
     * 返回 false 时，框架报「上下文目录不存在或不是目录」（fail-closed）。
     */
    default boolean isDirectory(String path) {
        return true;
    }

    /**
     * 文件的真实路径（用于去重）。
     *
     * <p>默认返回入参本身（不去重）。宿主知道真实路径时覆写它——
     * 同一个文件经由软链接被访问两次，不该进上下文两次。
     *
     * @param path 文件路径
     * @return 真实路径
     */
    default String realPath(String path) {
        return path;
    }

    /**
     * 父目录（向上递归用）。
     *
     * <p>默认返回 {@code null} = 已在顶层，不再向上找。
     * 宿主覆写它来实现「从当前目录一路找到根」——
     * 目录怎么算是框架不关心的事（本地路径、虚拟目录、容器挂载各不相同），
     * 但**向上找的规则**（每层按候选名优先级取一个、直到顶）是框架的事。
     *
     * @param dir 当前目录
     * @return 父目录；到顶返回 {@code null}
     */
    default String parentOf(String dir) {
        return null;
    }

    /**
     * 拼接目录与文件名（向上递归时拼候选名用）。
     *
     * <p>默认用 {@code /}（POSIX 风格）。<b>虚拟文件系统宿主应覆写它</b>：
     * 对象存储里的目录用 {@code /}，但有些宿主用别的分隔符，
     * 框架替宿主猜分隔符 = 猜错就读不到文件（而且是「静默读不到」）。
     *
     * @param dir  目录
     * @param name 文件名（候选名）
     * @return 拼接后的路径
     */
    default String join(String dir, String name) {
        if (dir == null || dir.isEmpty()) {
            return name;
        }
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }
}
