package net.hegq.mos4j.core;

/**
 * 对外版本号。
 *
 * <p><b>为什么要有这个类</b>：宿主程序经常需要「我跑的是哪一版」——
 * 写进日志、报给服务端、或在 issue 里贴出来。没有统一出口时，各处自己拼字符串，
 * 拼着拼着就会和 Maven 坐标对不上（版本号有唯一的真源：**Maven 坐标**）。
 *
 * <p><b>口径</b>：
 * <ul>
 *   <li>取值与父 POM 的 {@code <version>} 一致，由守护测试 {@code VersionTest} 机械核对；
 *       改了版本号忘了改这里 → 测试变红。</li>
 *   <li>这里是<b>纯常量</b>，不读 {@code META-INF/maven/…/pom.properties}：
 *       开发期（classpath 里没有那份文件）与发布期的取值必须一致，
 *       读文件会让「开发期版本号」变成另一个值——那种不一致比没有版本号更难查。</li>
 *   <li>不含构建时间戳：构建时间不是版本的一部分，且会让「同样的代码得到不同的版本号」。</li>
 * </ul>
 */
public final class Version {

    /** 当前版本号（与 Maven 坐标同步）。 */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Version() {
    }

    /**
     * 当前版本号。
     *
     * @return 版本号字符串（形如 {@code 0.1.0} 或 {@code 0.1.0-SNAPSHOT}）
     */
    public static String version() {
        return VERSION;
    }
}
