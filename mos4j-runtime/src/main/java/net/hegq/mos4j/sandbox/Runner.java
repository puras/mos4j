package net.hegq.mos4j.sandbox;

/**
 * 命令运行器。
 *
 * <p>实现方负责把「宿主的边界」真正落到进程上：工作目录、环境、超时、资源上限、路径强制。
 */
public interface Runner {

    /**
     * 跑一次命令。
     *
     * @param request 运行请求
     * @return 运行结果（不抛异常表示命令本身跑完了；启动失败也用结果表达）
     */
    RunResult run(RunRequest request);
}
