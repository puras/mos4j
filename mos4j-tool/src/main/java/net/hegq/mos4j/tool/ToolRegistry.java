package net.hegq.mos4j.tool;

import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.tool.searchtools.ActivateTool;
import net.hegq.mos4j.tool.searchtools.DeferredToolCatalog;
import net.hegq.mos4j.tool.searchtools.ToolSearchTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 线程安全的工具注册表。
 *
 * <p><b>三条约定</b>：</p>
 * <ol>
 *   <li><b>注册即生成 schema 并缓存</b>：避免循环内每轮 {@link #list()} 重复反射
 *       （反射是热路径上的纯浪费）。</li>
 *   <li><b>重名注册直接报错</b>（fail-closed，不静默覆盖）——覆盖意味着模型调到的
 *       工具与宿主以为的不是同一个。</li>
 *   <li><b>未知工具调用直接报错</b>：守卫与策略在驱动层先行拦截，这里的兜底保证
 *       未注册的工具绝不会落到任意实现上。</li>
 * </ol>
 */
public final class ToolRegistry {

    private final Map<String, net.hegq.mos4j.tool.Tool> tools = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
    private final DeferredToolCatalog deferredCatalog = new DeferredToolCatalog();
    private final Object lock = new Object();

    /** 具名可见性槽：同名槽整层替换，多槽之间 AND 叠加。 */
    private final Map<String, Predicate<String>> visibilitySlots = new LinkedHashMap<>();

    /** 工具名 → 归属 scope（普通注册工具归属空串）。 */
    private final Map<String, String> toolScope = new LinkedHashMap<>();

    /** scope → 该 scope 当前的工具名集合。 */
    private final Map<String, Set<String>> scopeTools = new LinkedHashMap<>();

    public ToolRegistry() {
        // 目录检索与注册表用**同一份**可见性判定：注册表说模型看不见的，
        // 搜出来模型也调不动（搜得到、调不动，比搜不到更费劲）。
        deferredCatalog.setVisibility(this::visibleTo);
    }

    /**
     * 注册一个工具。
     *
     * @param tool 工具
     * @throws ToolException 重名、无名，或 schema 生成失败（fail-closed）
     */
    public void register(net.hegq.mos4j.tool.Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isEmpty()) {
            throw new ToolException("tool: 不能注册 null 或无名工具");
        }
        synchronized (lock) {
            if (tools.containsKey(tool.name())) {
                throw new ToolException("tool: 工具 \"" + tool.name() + "\" 重复注册");
            }
            Map<String, Object> schema = resolveSchema(tool);
            tools.put(tool.name(), tool);
            schemas.put(tool.name(), schema);
        }
    }

    /** 注册并返回该工具（便于链式装配）。 */
    public ToolRegistry registerAndGet(Tool tool) {
        register(tool);
        return this;
    }

    /** 按名称查找工具。 */
    public Optional<net.hegq.mos4j.tool.Tool> get(String name) {
        synchronized (lock) {
            return Optional.ofNullable(tools.get(name));
        }
    }

    /** 已注册工具名（按注册顺序）。 */
    public List<String> names() {
        synchronized (lock) {
            return new ArrayList<>(tools.keySet());
        }
    }

    /**
     * 工具的协议层定义（schema 取自注册时的缓存）。
     *
     * <p>非 deferred 工具始终出现；deferred 工具只在激活后才出现（见 {@link #registerDeferred}）。
     * 被可见性槽挡住的工具不出现（模型看不见的工具不该占工具清单的名额）。
     */
    public List<net.hegq.mos4j.model.Tool> list() {
        synchronized (lock) {
            List<net.hegq.mos4j.model.Tool> out = new ArrayList<>(tools.size());
            Set<String> active = new LinkedHashSet<>(deferredCatalog.activeTools());
            for (Map.Entry<String, net.hegq.mos4j.tool.Tool> entry : tools.entrySet()) {
                String name = entry.getKey();
                net.hegq.mos4j.tool.Tool tool = entry.getValue();
                if (!isDeferredHelper(name) && deferredCatalog.entry(name) != null
                        && !active.contains(name)) {
                    continue; // 未激活的 deferred 工具不进清单
                }
                if (!visibleTo(name)) {
                    continue; // 可见性槽挡住（模型看不见）
                }
                out.add(new net.hegq.mos4j.model.Tool(tool.name(), tool.description(),
                        schemas.get(name)));
            }
            return out;
        }
    }

    /**
     * 模型当前<b>可见</b>的工具名（与 {@link #list()} 同一口径：未激活的 deferred 工具不在内）。
     *
     * <p>技能的 {@code allowed-tools} 求交诊断用它（只诊断不收窄）：“技能要的工具
     * 当前看不见”得跟模型真正看到的那份清单比，而不是跟注册表里所有名字比——
     * deferred 工具名字存在但模型看不见，那也算看不见。
     *
     * @return 工具名（按注册顺序）
     */
    public List<String> visibleNames() {
        List<String> out = new ArrayList<>();
        for (net.hegq.mos4j.model.Tool tool : list()) {
            out.add(tool.name());
        }
        return out;
    }

    /**
     * 执行指定名称的工具调用（统一入口）。
     *
     * @param name     工具名
     * @param argsJson 参数 JSON 原文（容错修复后传入）
     * @throws ToolException 未知工具或工具执行失败
     */
    public Object call(String name, String argsJson) {
        return call(name, argsJson, net.hegq.mos4j.core.ToolContext.none());
    }

    /**
     * 执行指定名称的工具调用（带执行上下文）。
     *
     * @param name     工具名
     * @param argsJson 参数 JSON 原文（容错修复后传入）
     * @param context  执行上下文（轮次 / 会话 id / 取消令牌；可空 = 空上下文）
     * @throws ToolException 未知工具或工具执行失败
     */
    public Object call(String name, String argsJson, net.hegq.mos4j.core.ToolContext context) {
        net.hegq.mos4j.tool.Tool tool = get(name)
                .orElseThrow(() -> new ToolException("tool: 未知工具 \"" + name + "\""));
        try {
            return tool.execute(ToolArgs.normalize(argsJson),
                    context == null ? net.hegq.mos4j.core.ToolContext.none() : context);
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("tool: 执行 \"" + name + "\" 失败: " + e.getMessage(), e);
        }
    }

    /** 工具数量。 */
    public int size() {
        synchronized (lock) {
            return tools.size();
        }
    }

    // ---- 可见性槽（具名层，多层 AND 叠加）----

    /**
     * 设置 / 替换一个**具名**可见性槽。
     *
     * <p>同名槽整层替换：调用方传新的过滤器就换掉旧的那层，传 {@code null} 就移除该层。
     * 多槽之间 **AND** 叠加：每一层都放行，工具才对模型可见。
     *
     * <p>为什么要具名而不是一个总开关：不同的关注点要能**独立热替换**——
     * 策略层换名单时，不该把 MCP 的 hidden 层一起换掉。
     *
     * <p>槽的过滤器必须是**纯判定、无副作用**：它会被反复调用（每次组装工具清单）。
     *
     * @param slot    槽名（不能为空）
     * @param visible 可见性判定（可空 = 移除该槽）
     */
    public void setVisibilitySlot(String slot, Predicate<String> visible) {
        if (slot == null || slot.isBlank()) {
            throw new ToolException("tool: 可见性槽名不能为空");
        }
        synchronized (lock) {
            if (visible == null) {
                visibilitySlots.remove(slot);
            } else {
                visibilitySlots.put(slot, visible);
            }
        }
    }

    /** 取某个可见性槽的过滤器（没设过返回 null）。 */
    public Predicate<String> visibilitySlot(String slot) {
        synchronized (lock) {
            return visibilitySlots.get(slot);
        }
    }

    /** 某个工具当前是否对模型可见（所有槽 AND 叠加）。 */
    public boolean visibleTo(String name) {
        synchronized (lock) {
            for (Predicate<String> filter : visibilitySlots.values()) {
                if (!filter.test(name)) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- 按 scope 原子换代（多来源工具面：MCP 等）----

    /**
     * 按 scope 整体替换工具集（两阶段换代的落盘一步）。
     *
     * <p><b>先校验整批，再落盘</b>：名单内重名、deferred 名单含名单外工具、
     * schema 生成失败——任一出错就抛错，<b>注册表保持原样</b>（上一代还在）。
     * 半套工具比旧一套更难查：模型看到的清单与宿主以为的不一致。
     *
     * <p>同一 scope 的上一代里**不在新名单**的工具会消失（连同 deferred 登记与激活态）；
     * 其他 scope 的工具不受影响。
     *
     * <p>跨 scope 同名工具直接报错（比 Go 更严）：同名工具归两个来源，
     * 谁生效说不清。
     *
     * @param scope     归属 scope（空串 = 不做 scope 清理）
     * @param newTools  新工具全集（direct / deferred / hidden 都在里面）
     * @param deferred  其中属于 deferred 档的名字（必须是 newTools 的子集）
     * @throws ToolException 校验失败或落盘失败（注册表保持原样）
     */
    public void replace(String scope, List<net.hegq.mos4j.tool.Tool> newTools,
                        Set<String> deferred) {
        String key = scope == null ? "" : scope;
        List<net.hegq.mos4j.tool.Tool> batch = newTools == null ? List.of() : newTools;
        Set<String> deferredNames = deferred == null ? Set.of() : deferred;
        synchronized (lock) {
            // ---- 阶段一：整批校验（失败则一个字节都不改） ----
            Map<String, Map<String, Object>> newSchemas = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            for (net.hegq.mos4j.tool.Tool tool : batch) {
                if (tool == null || tool.name() == null || tool.name().isEmpty()) {
                    throw new ToolException("tool: 不能替换 null 或无名工具");
                }
                String name = tool.name();
                if (!names.add(name)) {
                    throw new ToolException("tool: Replace 名单内工具 \"" + name + "\" 重名");
                }
                String owner = toolScope.get(name);
                if (owner != null && !owner.equals(key)) {
                    throw new ToolException("tool: 工具 \"" + name + "\" 已归属 scope \"" + owner
                            + "\"，不能被 scope \"" + key + "\" 接管");
                }
                newSchemas.put(name, resolveSchema(tool));
            }
            for (String name : deferredNames) {
                if (!names.contains(name)) {
                    throw new ToolException("tool: Replace 的 deferred 名单含未在名单中的工具 \""
                            + name + "\"");
                }
            }

            // ---- 阶段二：落盘 ----
            Set<String> previous = scopeTools.getOrDefault(key, Set.of());
            for (String name : previous) {
                if (names.contains(name)) {
                    continue;
                }
                tools.remove(name);
                schemas.remove(name);
                toolScope.remove(name);
                deferredCatalog.unregister(name);
                deferredCatalog.deactivate(name);
            }
            for (net.hegq.mos4j.tool.Tool tool : batch) {
                String name = tool.name();
                tools.put(name, tool);
                schemas.put(name, newSchemas.get(name));
                toolScope.put(name, key);
                deferredCatalog.unregister(name); // 清旧目录条目（幂等）
                if (deferredNames.contains(name)) {
                    deferredCatalog.register(name, tool.description());
                }
            }
            scopeTools.put(key, names);
            ensureDeferredHelpers();
        }
    }

    /** 某个 scope 当前的工具名（快照）。 */
    public Set<String> scopeToolNames(String scope) {
        synchronized (lock) {
            return new LinkedHashSet<>(scopeTools.getOrDefault(scope == null ? "" : scope, Set.of()));
        }
    }

    /** 全部 scope 名（快照）。 */
    public Set<String> scopes() {
        synchronized (lock) {
            return new LinkedHashSet<>(scopeTools.keySet());
        }
    }

    // ---- deferred 工具（T5.7）：工具面太大时，先搜后激活 ----

    /**
     * 登记 deferred 工具：只进目录（名字 + 说明），不进工具清单。
     *
     * <p>deferred 集非空时自动注册 {@code tool_search} / {@code activate}
     * （幂等，多次调用只注册一次）——没有这两条工具，模型搜到了也无法激活。
     *
     * @param tool        完整工具定义（宿主持有；激活后由 {@link #list()} 输出）
     * @param description 目录里的简述（给 tool_search 的语料）
     * @throws ToolException 重名、无名或 schema 生成失败（fail-closed）
     */
    public void registerDeferred(net.hegq.mos4j.tool.Tool tool, String description) {
        if (tool == null || tool.name() == null || tool.name().isEmpty()) {
            throw new ToolException("tool: 不能登记 null 或无名 deferred 工具");
        }
        synchronized (lock) {
            // schema 在登记时就生成：激活失败要发生在登记期，而不是激活当轮
            Map<String, Object> schema = resolveSchema(tool);
            if (tools.containsKey(tool.name())) {
                throw new ToolException("tool: 工具 \"" + tool.name() + "\" 重复注册");
            }
            deferredCatalog.register(tool.name(), description == null ? tool.description() : description);
            tools.put(tool.name(), tool);
            schemas.put(tool.name(), schema);
        }
        ensureDeferredHelpers();
    }

    /** deferred 目录（宿主可查询 / 宿主可自行判断）。 */
    public DeferredToolCatalog deferredCatalog() {
        return deferredCatalog;
    }

    /**
     * 「历史工具名并入」兑底：把模型历史上调用过（但未激活）的工具名并进激活集。
     *
     * <p>场景：会话从持久化恢复后，历史里有对某 deferred 工具的调用，
     * 但激活集是空的（激活态在内存里）。不并入的话，投影里那条调用
     * 对着一个「本轮清单里不存在」的工具，模型会困惑甚至重复问。
     * 兑底不是恢复事实——是把历史里的事实反映到本轮清单。
     */
    public void admitHistoricalTools(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        deferredCatalog.activate(names);
    }

    /** deferred 辅助工具（tool_search / activate）始终出现在清单里。 */
    private static boolean isDeferredHelper(String name) {
        return ToolSearchTool.NAME.equals(name) || ActivateTool.NAME.equals(name);
    }

    /** deferred 集非空时自动注册 tool_search / activate（幂等）。 */
    private void ensureDeferredHelpers() {
        synchronized (lock) {
            if (deferredCatalog.isEmpty()) {
                return;
            }
            if (tools.get(ToolSearchTool.NAME) == null) {
                registerLocked(new ToolSearchTool(deferredCatalog));
            }
            if (tools.get(ActivateTool.NAME) == null) {
                registerLocked(new ActivateTool(deferredCatalog));
            }
        }
    }

    /** 注册（调用方必须已持锁）。 */
    private void registerLocked(net.hegq.mos4j.tool.Tool tool) {
        Map<String, Object> schema = resolveSchema(tool);
        tools.put(tool.name(), tool);
        schemas.put(tool.name(), schema);
    }

    private static Map<String, Object> resolveSchema(net.hegq.mos4j.tool.Tool tool) {
        Map<String, Object> direct = tool.schema();
        if (direct != null) {
            return direct;
        }
        Class<?> type;
        try {
            type = tool.parameterType();
        } catch (RuntimeException e) {
            // 反射失败必须抛出（fail-closed），不能退化成空 schema
            throw new ToolException("tool: 生成 \"" + tool.name() + "\" 的 schema 失败: " + e.getMessage(), e);
        }
        if (type == null) {
            // 没有参数也没有 schema → 空对象参数
            return Map.of("type", "object");
        }
        // 反射失败必须抛出（fail-closed），不能退化成空 schema
        return SchemaGenerator.generate(type);
    }
}
