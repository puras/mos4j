package net.hegq.mos4j.tool.searchtools;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.util.List;

/**
 * tool_search 工具（T5.6）：按关键词搜索可激活的 deferred 工具。
 *
 * <p>工具面太大（几百个）时，把全部定义塞进每轮请求既贵又稀释注意力。
 * deferred 机制（T5.7）让宿主只注册工具的「名字 + 说明」，
 * 模型用本工具按任务搜出相关的几个，激活后下一轮才拿到完整定义。
 */
public final class ToolSearchTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "tool_search";

    /** 缺省返回条数。 */
    public static final int DEFAULT_TOP_K = 5;

    private final DeferredToolCatalog catalog;

    /** @param catalog deferred 工具目录 */
    public ToolSearchTool(DeferredToolCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("tool: tool_search 需要 deferred 目录");
        }
        this.catalog = catalog;
    }

    /**
     * 参数。
     *
     * @param query 关键词（工具名或用途描述）
     * @param limit 最多返回几条
     */
    public record Params(
            @ToolParam(desc = "搜索关键词（工具名或用途，中英文均可）", required = true) String query,
            @ToolParam(desc = "最多返回几条（默认 5）") Integer limit) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按关键词搜索尚未激活的工具。工具集很大时，先搜出需要的工具再激活，"
                + "激活在下一轮生效。返回工具名与说明及匹配分。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = SearchArgs.parse(argsJson, Params.class);
        if (params.query() == null || params.query().isBlank()) {
            throw new ToolException("tool_search 失败：缺少参数 query");
        }
        int topK = params.limit() == null ? DEFAULT_TOP_K : params.limit();
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        List<Bm25Index.Scored> hits = catalog.search(params.query(), topK);
        if (hits.isEmpty()) {
            return "没有匹配的工具";
        }
        StringBuilder out = new StringBuilder("匹配的工具（调用 activate 激活，下一轮生效）：\n");
        for (Bm25Index.Scored hit : hits) {
            DeferredToolCatalog.Entry entry = catalog.entry(hit.id());
            out.append("- ").append(entry.name())
                    .append("（匹配分 ").append(String.format(java.util.Locale.ROOT, "%.2f", hit.score()))
                    .append("）：").append(entry.description()).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
