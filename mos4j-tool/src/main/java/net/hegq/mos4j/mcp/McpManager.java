package net.hegq.mos4j.mcp;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多 MCP 服务器的编排器（对齐 Go 参考实现 {@code tool/mcp/manager.go}）。
 *
 * <p><b>四条语义</b>：
 * <ol>
 *   <li><b>两阶段换代</b>：先并发连接并取回**全量**工具，全部就绪后再整体写进注册表。
 *       任何一步失败都不写半套——模型看到的清单与宿主以为的不一致，比少几个工具难查得多。
 *       换代失败时<b>保留上一代</b>。</li>
 *   <li><b>失败隔离</b>：一台服务器连不上，不影响其他服务器；快照里如实记失败。</li>
 *   <li><b>scope 隔离</b>：每台服务器一个 scope（{@code mcp:<server>}），换代只动自己那一份。</li>
 *   <li><b>三档曝光</b>：direct 直接进清单；deferred 进目录（搜到后激活）；
 *       hidden 进注册表但被可见性层挡住——模型看不见、也搜不到，宿主仍可程序化调用。</li>
 * </ol>
 */
public final class McpManager implements AutoCloseable {

    /** hidden 档在注册表可见性层里的槽名。 */
    public static final String HIDDEN_SLOT = "mcp-hidden";

    /** 服务器说明注入的字节上限（32KB）。 */
    public static final int DEFAULT_MAX_INSTRUCTION_BYTES = 32 * 1024;

    private static final String SCOPE_PREFIX = "mcp:";

    private final ToolRegistry registry;
    private final McpClientFactory factory;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private final Map<String, McpServerConfig> serverConfigs = new LinkedHashMap<>();
    private final Map<String, McpSnapshot.ServerStatus> statuses = new LinkedHashMap<>();
    private final Map<String, Set<String>> hiddenByServer = new LinkedHashMap<>();
    private final Map<String, Boolean> resourceServers = new LinkedHashMap<>();
    private final Object lock = new Object();

    /**
     * 装管理器。
     *
     * @param registry 工具注册表（MCP 工具写进这里）
     * @param factory  客户端工厂
     */
    public McpManager(ToolRegistry registry, McpClientFactory factory) {
        if (registry == null) {
            throw new IllegalArgumentException("mcp: 工具注册表不能为空");
        }
        if (factory == null) {
            throw new IllegalArgumentException("mcp: 客户端工厂不能为空");
        }
        this.registry = registry;
        this.factory = factory;
        // hidden 层读**当前**的 hidden 集合：服务器换代时集合会变，过滤器本身不用换
        // （过滤器必须是纯判定、无副作用）。
        registry.setVisibilitySlot(HIDDEN_SLOT, this::notHidden);
    }

    /**
     * 连接全部服务器并把工具写进注册表。
     *
     * @param configs 服务器配置
     * @return 装配快照
     */
    public McpSnapshot start(List<McpServerConfig> configs) {
        List<McpServerConfig> list = configs == null ? List.of() : configs;
        if (list.isEmpty()) {
            return snapshot();
        }
        // ---- 阶段一：并发连接 + 取全量工具（不碰注册表） ----
        Map<String, Prepared> prepared = new LinkedHashMap<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<Prepared>> futures = new ArrayList<>();
            for (McpServerConfig config : list) {
                futures.add(CompletableFuture.supplyAsync(() -> prepare(config), executor));
            }
            for (int i = 0; i < list.size(); i++) {
                McpServerConfig config = list.get(i);
                try {
                    prepared.put(config.name(), futures.get(i).join());
                } catch (RuntimeException e) {
                    prepared.put(config.name(), Prepared.failed(config, rootMessage(e)));
                }
            }
        } finally {
            executor.shutdown();
        }
        // ---- 阶段二：串行落盘（换代） ----
        synchronized (lock) {
            for (McpServerConfig config : list) {
                serverConfigs.put(config.name(), config);
            }
        }
        for (McpServerConfig config : list) {
            commit(config, prepared.get(config.name()));
        }
        registerResourceTools();
        return snapshot();
    }

    /**
     * 重连一台服务器并换代（失败保留上一代）。
     *
     * @param serverName 服务器名
     * @return 装配快照
     */
    public McpSnapshot reload(String serverName) {
        McpServerConfig config;
        synchronized (lock) {
            config = serverConfigs.get(serverName);
        }
        if (config == null) {
            return snapshot();
        }
        commit(config, prepare(config));
        registerResourceTools();
        return snapshot();
    }

    /**
     * 注册三个共享资源工具（只要任一台可见服务器确实有资源）。
     *
     * <p>工具面恒为 3（不随服务器数增长）：服务器间路由靠显式 {@code server} 参数。
     * 幂等：已注册过就不重复注册（注册表不接受重名）。
     */
    private void registerResourceTools() {
        if (!hasResourceServers()) {
            return;
        }
        Set<String> registered = new java.util.LinkedHashSet<>(registry.visibleNames());
        for (Tool tool : McpResourceTools.all(this)) {
            if (!registered.contains(tool.name())) {
                registry.register(tool);
            }
        }
    }

    /**
     * 是否存在资源可达的服务器（非 hidden 且确实声明过资源或模板）。
     *
     * @return 有则 true
     */
    public boolean hasResourceServers() {
        synchronized (lock) {
            for (Map.Entry<String, Boolean> entry : resourceServers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue()) && !serverHidden(entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 资源可达的服务器客户端。
     *
     * <p>判定与工具注册口径一致：存在、非 hidden、确实有资源。找不到返回 {@code null}。
     *
     * @param serverName 服务器名
     * @return 客户端（不可达时 null）
     */
    public McpClient resourceClient(String serverName) {
        synchronized (lock) {
            if (!Boolean.TRUE.equals(resourceServers.get(serverName))) {
                return null;
            }
            if (serverHidden(serverName)) {
                return null;
            }
            return clients.get(serverName);
        }
    }

    /** 一台服务器是否整体对模型隐藏（服务器级 hidden 或逐工具通配 hidden）。 */
    private boolean serverHidden(String serverName) {
        McpServerConfig config;
        synchronized (lock) {
            config = serverConfigs.get(serverName);
        }
        if (config == null) {
            return false;
        }
        if (config.exposure() == McpExposure.HIDDEN) {
            return true;
        }
        McpExposure wildcard = config.toolExposure().get("*");
        return wildcard == McpExposure.HIDDEN;
    }

    /** 当前快照。 */
    public McpSnapshot snapshot() {
        synchronized (lock) {
            return new McpSnapshot(new ArrayList<>(statuses.values()));
        }
    }

    /**
     * 各服务器的说明（空的不要）。
     *
     * @return 说明列表
     */
    public List<McpInstruction> instructions() {
        List<McpInstruction> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
                String text = entry.getValue().instructions();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                out.add(new McpInstruction(entry.getKey(), text));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 渲染服务器说明（注入模型用）。
     *
     * <p>带服务器归属（模型看得懂这段话是谁说的）；超预算时**整段**省略并写明——
     * 半句话比没有更坏（宁可少说一台）。
     *
     * @return 渲染后的文本（无说明时返回空串）
     */
    public String instructionsText() {
        return instructionsText(DEFAULT_MAX_INSTRUCTION_BYTES);
    }

    /**
     * 渲染服务器说明（可指定字节预算）。
     *
     * @param maxBytes 字节上限（{@code <=0} 取默认 32KB）
     * @return 渲染后的文本
     */
    public String instructionsText(int maxBytes) {
        List<McpInstruction> list = instructions();
        if (list.isEmpty()) {
            return "";
        }
        int budget = maxBytes <= 0 ? DEFAULT_MAX_INSTRUCTION_BYTES : maxBytes;
        StringBuilder out = new StringBuilder();
        out.append("[MCP 服务器说明]\n");
        out.append("以下说明来自已连接的 MCP 服务器（连接建立时注入一次）：\n");
        for (McpInstruction instruction : list) {
            String part = "- " + instruction.server() + ": " + instruction.text() + "\n";
            if (out.toString().getBytes(StandardCharsets.UTF_8).length + part.getBytes(StandardCharsets.UTF_8).length
                    > budget) {
                out.append("- （其余服务器的说明超出预算，已省略）\n");
                break;
            }
            out.append(part);
        }
        return out.toString();
    }

    /**
     * 当前处于 hidden 档的工具名（全部服务器汇总）。
     *
     * @return 工具名集合
     */
    public Set<String> hiddenTools() {
        Set<String> out = new LinkedHashSet<>();
        synchronized (lock) {
            for (Set<String> names : hiddenByServer.values()) {
                out.addAll(names);
            }
        }
        return Set.copyOf(out);
    }

    /** 关闭全部连接（幂等）。 */
    @Override
    public void close() {
        List<McpClient> open;
        synchronized (lock) {
            open = new ArrayList<>(clients.values());
            clients.clear();
        }
        for (McpClient client : open) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不掩盖别的问题：连接已经要断了，报错反而让调用方以为还能用。
            }
        }
    }

    // ---- 内部 ----

    /** 可见性判定：hidden 档的工具对模型不可见（宿主仍可程序化调用）。 */
    private boolean notHidden(String name) {
        return !hiddenTools().contains(name);
    }

    /** 阶段一：连接 + 取工具（不写注册表）。 */
    private Prepared prepare(McpServerConfig config) {
        if (config.disabled()) {
            return Prepared.disabled(config);
        }
        McpClient client;
        try {
            client = factory.connect(config);
        } catch (Exception e) {
            return Prepared.failed(config, rootMessage(e));
        }
        if (client == null) {
            return Prepared.failed(config, "客户端工厂返回了 null");
        }
        try {
            List<McpToolInfo> tools = client.listTools();
            if (tools == null) {
                tools = List.of();
            }
            List<Tool> all = new ArrayList<>();
            Set<String> deferredNames = new LinkedHashSet<>();
            Set<String> hiddenNames = new LinkedHashSet<>();
            for (McpToolInfo info : tools) {
                if (info == null || info.name() == null || info.name().isEmpty()) {
                    continue;
                }
                McpToolAdapter adapter = McpToolAdapter.of(client, config.name(), info);
                all.add(adapter);
                McpExposure exposure = config.exposureOf(info.name());
                if (exposure == McpExposure.DEFERRED) {
                    deferredNames.add(adapter.name());
                } else if (exposure == McpExposure.HIDDEN) {
                    hiddenNames.add(adapter.name());
                }
            }
            return Prepared.ok(config, client, all, deferredNames, hiddenNames,
                    client.instructions(), client.protocolVersion(), hasResources(client));
        } catch (Exception e) {
            closeQuietly(client);
            return Prepared.failed(config, rootMessage(e));
        }
    }

    /**
     * 这台服务器是否确实声明了资源或模板。
     *
     * <p>列资源失败**不影响**工具装配：资源是可选能力，拿不到就当没有（工具本身照常可用）。
     */
    private static boolean hasResources(McpClient client) {
        try {
            if (!client.listResources().isEmpty()) {
                return true;
            }
        } catch (Exception ignored) {
            // 列不到资源 = 这台服务器没有（或不让列）资源，不是故障。
        }
        try {
            return !client.listResourceTemplates().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 阶段二：落盘（换代）。失败保留上一代。 */
    private void commit(McpServerConfig config, Prepared prepared) {
        String scope = scopeOf(config.name());
        if (prepared == null) {
            record(config.name(), McpSnapshot.Status.FAILED, 0, 0, 0, "没有拿到连接结果");
            return;
        }
        if (prepared.status() == McpSnapshot.Status.DISABLED) {
            // 禁用 = 配置意图，不是故障：清掉这一份工具，但快照要写明「未连接（配置为禁用）」。
            try {
                registry.replace(scope, List.of(), Set.of());
            } catch (RuntimeException e) {
                record(config.name(), McpSnapshot.Status.FAILED, 0, 0, 0, rootMessage(e));
                return;
            }
            McpClient old = null;
            synchronized (lock) {
                hiddenByServer.remove(config.name());
                resourceServers.remove(config.name());
                old = clients.remove(config.name());
            }
            closeQuietly(old);
            record(config.name(), McpSnapshot.Status.DISABLED, 0, 0, 0, "未连接（配置为禁用）");
            return;
        }
        if (prepared.status() == McpSnapshot.Status.FAILED) {
            // 失败隔离：不动注册表（上一代还在），只记快照。
            record(config.name(), McpSnapshot.Status.FAILED, 0, 0, 0,
                    prepared.detail() + "（保留上一代）");
            return;
        }
        try {
            registry.replace(scope, prepared.tools(), prepared.deferredNames());
        } catch (RuntimeException e) {
            closeQuietly(prepared.client());
            record(config.name(), McpSnapshot.Status.FAILED, 0, 0, 0,
                    "换代失败：" + rootMessage(e) + "（保留上一代）");
            return;
        }
        McpClient old;
        synchronized (lock) {
            old = clients.put(config.name(), prepared.client());
            hiddenByServer.put(config.name(), prepared.hiddenNames());
            resourceServers.put(config.name(), prepared.hasResources());
        }
        closeQuietly(old);
        int direct = prepared.tools().size() - prepared.deferredNames().size() - prepared.hiddenNames().size();
        String detail = prepared.protocolVersion() == null || prepared.protocolVersion().isEmpty()
                ? "" : "协议版本 " + prepared.protocolVersion();
        record(config.name(), McpSnapshot.Status.CONNECTED, Math.max(direct, 0),
                prepared.deferredNames().size(), prepared.hiddenNames().size(), detail);
    }

    /** 记录一台服务器的状态。 */
    private void record(String name, McpSnapshot.Status status, int direct, int deferred, int hidden,
                        String detail) {
        synchronized (lock) {
            statuses.put(name, new McpSnapshot.ServerStatus(name, status, direct, deferred, hidden, detail));
        }
    }

    /** 服务器的 scope 名。 */
    public static String scopeOf(String serverName) {
        return SCOPE_PREFIX + serverName;
    }

    private static void closeQuietly(McpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // 同 close()：关闭失败不该盖住真正的错误。
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** 阶段一的产物。 */
    private record Prepared(McpServerConfig config, McpSnapshot.Status status, McpClient client,
                             List<Tool> tools, Set<String> deferredNames, Set<String> hiddenNames,
                             String instructions, String protocolVersion, boolean hasResources,
                             String detail) {

        static Prepared ok(McpServerConfig config, McpClient client, List<Tool> tools,
                           Set<String> deferredNames, Set<String> hiddenNames, String instructions,
                           String protocolVersion, boolean hasResources) {
            return new Prepared(config, McpSnapshot.Status.CONNECTED, client, tools, deferredNames,
                    hiddenNames, instructions, protocolVersion, hasResources, "");
        }

        static Prepared failed(McpServerConfig config, String detail) {
            return new Prepared(config, McpSnapshot.Status.FAILED, null, List.of(), Set.of(), Set.of(),
                    "", "", false, detail);
        }

        static Prepared disabled(McpServerConfig config) {
            return new Prepared(config, McpSnapshot.Status.DISABLED, null, List.of(), Set.of(), Set.of(),
                    "", "", false, "未连接（配置为禁用）");
        }
    }
}
