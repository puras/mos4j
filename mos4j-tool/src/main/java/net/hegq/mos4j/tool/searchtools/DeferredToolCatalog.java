package net.hegq.mos4j.tool.searchtools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * deferred 工具目录（T5.7）：登记工具的「名字 + 说明」，完整定义先不进请求。
 *
 * <p>激活态<b>下一轮生效</b>：模型看到 tool_search 结果后调 activate，
 * 激活发生在本轮；下一轮组装工具清单时才带上新工具。
 * 这与「每轮请求的工具清单在决策前快照」一致——
 * 若激活当轮立即生效，模型这轮看到的清单和它决策时的清单不一致，会调出「不存在」的工具。
 */
public final class DeferredToolCatalog {

    /** 一条 deferred 登记：工具名 + 说明（完整 Tool 由宿主持有）。 */
    public record Entry(String name, String description) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> active = new LinkedHashSet<>();
    private final Object lock = new Object();

    /** 登记一个 deferred 工具（重名登记直接报错，fail-closed）。 */
    public void register(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("catalog: 工具名不能为空");
        }
        synchronized (lock) {
            for (Entry entry : entries) {
                if (entry.name().equals(name)) {
                    throw new IllegalArgumentException("catalog: 工具重复登记: " + name);
                }
            }
            entries.add(new Entry(name, description == null ? "" : description));
        }
    }

    /** 全部登记（快照）。 */
    public List<Entry> entries() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    /** 按名取登记（不存在返回 null）。 */
    public Entry entry(String name) {
        synchronized (lock) {
            for (Entry entry : entries) {
                if (entry.name().equals(name)) {
                    return entry;
                }
            }
            return null;
        }
    }

    /**
     * 检索。
     *
     * @param query 关键词
     * @param topK  最多几条
     * @return 按相关性排序的登记（已激活的不重复返回）
     */
    public List<Bm25Index.Scored> search(String query, int topK) {
        List<Entry> snapshot = entries();
        Bm25Index index = new Bm25Index();
        for (Entry entry : snapshot) {
            index.add(entry.name(), entry.name() + " " + entry.description());
        }
        List<Bm25Index.Scored> hits = index.search(query, Math.max(topK, 1) + active.size());
        List<Bm25Index.Scored> visible = new ArrayList<>();
        synchronized (lock) {
            for (Bm25Index.Scored hit : hits) {
                if (visible.size() >= Math.max(topK, 1)) {
                    break;
                }
                if (!active.contains(hit.id())) {
                    visible.add(hit);
                }
            }
        }
        return visible;
    }

    /**
     * 激活工具（可多个）。
     *
     * @param names 工具名（未登记的名字被忽略并返回，调用方决定要不要告诉模型）
     * @return 真正激活的名字（不含未知名）；已激活的幂等处理
     */
    public List<String> activate(List<String> names) {
        List<String> activated = new ArrayList<>();
        synchronized (lock) {
            if (names != null) {
                for (String name : names) {
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    boolean known = entries.stream().anyMatch(e -> e.name().equals(name));
                    if (known && active.add(name)) {
                        activated.add(name);
                    }
                }
            }
        }
        return activated;
    }

    /** 是否已激活。 */
    public boolean isActive(String name) {
        synchronized (lock) {
            return active.contains(name);
        }
    }

    /** 已激活工具名（快照，按激活顺序）。 */
    public List<String> activeTools() {
        synchronized (lock) {
            return new ArrayList<>(active);
        }
    }

    /** 是否有登记（deferred 集非空才该自动注册 tool_search）。 */
    public boolean isEmpty() {
        synchronized (lock) {
            return entries.isEmpty();
        }
    }
}
