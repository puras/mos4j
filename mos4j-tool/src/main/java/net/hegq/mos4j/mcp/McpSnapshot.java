package net.hegq.mos4j.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 装配快照：装了什么、没装什么、哪台出了问题。
 *
 * <p><b>如实</b>是硬要求：一台服务器连不上，快照里必须写出来，
 * 而不是安静地少几个工具——宿主看着快照却不知道少了什么，
 * 等于把故障藏到了别处。
 */
public record McpSnapshot(List<ServerStatus> servers) {

    /** 构造（复制入参，外部改不动内部状态）。 */
    public McpSnapshot {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /** 空快照。 */
    public static McpSnapshot empty() {
        return new McpSnapshot(List.of());
    }

    /**
     * 一台服务器的状态。
     *
     * @param name     服务器名
     * @param status   状态
     * @param direct   direct 档工具数
     * @param deferred deferred 档工具数
     * @param hidden   hidden 档工具数
     * @param detail   补充说明（失败原因、协议版本等）
     */
    public record ServerStatus(String name, Status status, int direct, int deferred, int hidden,
                               String detail) {
    }

    /** 服务器状态。 */
    public enum Status {
        /** 已连接，工具已进注册表。 */
        CONNECTED,
        /** 连接或列工具失败（若上一代存在，则保留上一代）。 */
        FAILED,
        /** 配置为禁用，故意没连（不是故障）。 */
        DISABLED
    }

    /** 已连接的服务器名。 */
    public List<String> connectedServers() {
        return namesWith(Status.CONNECTED);
    }

    /** 失败的服务器名。 */
    public List<String> failedServers() {
        return namesWith(Status.FAILED);
    }

    /** 故意没连的服务器名（配置为禁用）。 */
    public List<String> skippedServers() {
        return namesWith(Status.DISABLED);
    }

    /** 全部工具数（各档之和）。 */
    public int toolCount() {
        int total = 0;
        for (ServerStatus status : servers) {
            total += status.direct() + status.deferred() + status.hidden();
        }
        return total;
    }

    /** 某台服务器的状态（找不到返回 null）。 */
    public ServerStatus server(String name) {
        for (ServerStatus status : servers) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return null;
    }

    private List<String> namesWith(Status wanted) {
        List<String> out = new ArrayList<>();
        for (ServerStatus status : servers) {
            if (status.status() == wanted) {
                out.add(status.name());
            }
        }
        return List.copyOf(out);
    }
}
