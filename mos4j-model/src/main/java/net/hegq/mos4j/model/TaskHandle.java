package net.hegq.mos4j.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 异步任务的句柄（跨模型种类的公共底座）。
 *
 * <p><b>为什么需要它</b>：图像 / 视频生成、文档解析这类服务不是「一次请求换一次响应」——
 * 客户端先提交拿到一个任务号，之后反复查询状态，最后才拿到产物。这条生命周期必须有个
 * 类型承载，否则每个协议各自造一个（状态名、终态判据、产物取法都不一样），宿主就得逐个适配。
 *
 * <p><b>这个类型只描述事实</b>：任务号、当前状态、已有产物、失败原因。
 * <b>怎么刷新由协议层决定</b>（轮询端点与状态映射是各家私有的）——见 {@link TaskPoller}。
 *
 * <p><b>失败必须带原因</b>：没有原因的任务失败，宿主只能把它原样透给用户
 * （"任务失败了"），用户无从判断是配额、参数还是服务端问题。
 *
 * @param taskId       任务号（厂商侧的唯一标识）
 * @param status       当前状态
 * @param artifacts    已有产物（不可变；成功时通常非空）
 * @param errorMessage 失败原因（仅 {@link TaskStatus#FAILED} 时非空）
 */
public record TaskHandle(String taskId, TaskStatus status, List<Artifact> artifacts, String errorMessage) {

    /**
     * @param taskId       任务号（可空 / 空白 = 非法）
     * @param status       状态（可空 = 非法）
     * @param artifacts    产物列表（可空 = 空列表）
     * @param errorMessage 失败原因（可空）
     */
    public TaskHandle {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("llm: 异步任务句柄没有任务号");
        }
        status = Objects.requireNonNull(status, "llm: 异步任务句柄没有状态");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        boolean hasMessage = errorMessage != null && !errorMessage.isBlank();
        if (status == TaskStatus.FAILED && !hasMessage) {
            throw new IllegalArgumentException("llm: 失败的任务必须带失败原因");
        }
        if (status == TaskStatus.SUCCEEDED && hasMessage) {
            throw new IllegalArgumentException("llm: 成功的任务不该带失败原因");
        }
    }

    /** 已提交。 */
    public static TaskHandle pending(String taskId) {
        return new TaskHandle(taskId, TaskStatus.PENDING, List.of(), null);
    }

    /** 执行中。 */
    public static TaskHandle running(String taskId) {
        return new TaskHandle(taskId, TaskStatus.RUNNING, List.of(), null);
    }

    /** 成功完成。 */
    public static TaskHandle succeeded(String taskId, List<Artifact> artifacts) {
        return new TaskHandle(taskId, TaskStatus.SUCCEEDED, artifacts, null);
    }

    /** 失败。 */
    public static TaskHandle failed(String taskId, String errorMessage) {
        return new TaskHandle(taskId, TaskStatus.FAILED, List.of(), errorMessage);
    }

    /** 已取消。 */
    public static TaskHandle canceled(String taskId) {
        return new TaskHandle(taskId, TaskStatus.CANCELED, List.of(), null);
    }

    /** 是否终态（不会再变）。 */
    public boolean terminal() {
        return status.terminal();
    }

    /** 首个产物（没有则为空）。 */
    public Optional<Artifact> primaryArtifact() {
        return artifacts.isEmpty() ? Optional.empty() : Optional.of(artifacts.get(0));
    }
}
