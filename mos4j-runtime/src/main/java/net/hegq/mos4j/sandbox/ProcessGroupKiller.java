package net.hegq.mos4j.sandbox;

import java.util.Comparator;
import java.util.List;

/**
 * 进程组击杀器。
 *
 * <p><b>为什么要单独抽一层</b>：超时之后如果只杀父进程，子进程会活下来继续占着 CPU 和管道，
 * 超时名存实亡。但 Java 标准库<b>没有</b>「把子进程放进新进程组然后 kill(-pgid)」的能力
 * （{@code ProcessBuilder} 默认不设 setpgid，也没有 {@code setsid}）。
 *
 * <p><b>所以默认实现只能降级</b>，并且必须把降级说清楚（{@link #description()}）：
 * 快照式、逐个强杀、非原子。Go 侧是「设进程组 + 整组 kill」，语义更强。
 * 想要真正等价的强度，只能上 JNI/原生后端——那是可选模块，不进 SDK 默认依赖。
 */
public interface ProcessGroupKiller {

    /**
     * 杀掉进程及其子孙。
     *
     * @param process 目标进程
     */
    void kill(Process process);

    /**
     * 强度说明（会进 {@code Describe}，供部署方判断边界）。
     *
     * @return 说明文字
     */
    String description();
}
