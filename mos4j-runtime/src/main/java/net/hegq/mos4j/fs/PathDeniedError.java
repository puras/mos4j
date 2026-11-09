package net.hegq.mos4j.fs;

import java.util.List;

/**
 * 路径被拒绝：结构化错误，带「该操作允许的根」。
 *
 * <p><b>为什么带允许的根</b>：说「哪些根允许」比说「什么模式」有用。
 * 模型看到「越界」不知道该往哪走；看到「允许的根是 /work 与 /data」才知道
 * 该换个路径重试——这是给模型的<b>可执行</b>信息，不是给人看的日志。
 *
 * <p>这属于<b>路径越界</b>（拒绝），不是「文件不存在」（那是另一种失败）。
 * 两者混在一起会让模型以为「再等等就会出现」。
 */
public class PathDeniedError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String requestedPath;
    private final List<String> allowedRoots;
    private final String reason;

    /**
     * @param requestedPath 被拒绝的路径（模型给的原文）
     * @param allowedRoots  该操作允许的根（真实路径字符串）
     * @param reason        拒绝原因（越界 / symlink 越界 / 链接层数超限 / 绝对路径 …）
     */
    public PathDeniedError(String requestedPath, List<String> allowedRoots, String reason) {
        super(buildMessage(requestedPath, allowedRoots, reason));
        this.requestedPath = requestedPath == null ? "" : requestedPath;
        this.allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
        this.reason = reason == null ? "路径越界" : reason;
    }

    private static String buildMessage(String path, List<String> roots, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("路径被拒绝（").append(reason == null ? "路径越界" : reason).append("）：");
        sb.append(path == null ? "" : path);
        sb.append("。该操作允许的根：");
        if (roots == null || roots.isEmpty()) {
            sb.append("（无）");
        } else {
            sb.append(roots);
        }
        return sb.toString();
    }

    /**
     * 被拒绝的路径（模型给的原文）。
     *
     * @return 路径原文
     */
    public String requestedPath() {
        return requestedPath;
    }

    /**
     * 该操作允许的根。
     *
     * @return 根路径列表
     */
    public List<String> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 拒绝原因。
     *
     * @return 原因
     */
    public String reason() {
        return reason;
    }
}
