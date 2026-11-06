package net.hegq.mos4j.tool.searchtools;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

import java.util.List;

/**
 * activate 工具（T5.7）：模型激活搜索到的 deferred 工具。
 *
 * <p>激活在<b>下一轮生效</b>：本工具只改目录的激活集，
 * 驱动在下一轮组装工具清单时才把激活的工具定义并入。
 * 立即生效会造出「本轮清单里有模型上轮看不到的工具」的时序错乱。
 */
public final class ActivateTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "activate";

    private final DeferredToolCatalog catalog;

    /** @param catalog deferred 工具目录 */
    public ActivateTool(DeferredToolCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("tool: activate 需要 deferred 目录");
        }
        this.catalog = catalog;
    }

    /**
     * 参数。
     *
     * @param tools 要激活的工具名（可多个）
     */
    public record Params(
            @ToolParam(desc = "要激活的工具名（可多个）", required = true) List<String> tools) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "激活 tool_search 搜到的工具。激活在下一轮生效；激活后即可直接调用该工具。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = SearchArgs.parse(argsJson, Params.class);
        if (params.tools() == null || params.tools().isEmpty()) {
            throw new ToolException("activate 失败：缺少参数 tools");
        }
        List<String> activated = catalog.activate(params.tools());
        List<String> unknown = params.tools().stream()
                .filter(n -> n != null && !n.isBlank() && catalog.entry(n) == null)
                .toList();
        StringBuilder out = new StringBuilder();
        if (activated.isEmpty()) {
            out.append("没有新激活的工具");
        } else {
            out.append("已激活（下一轮生效）: ").append(String.join(", ", activated));
        }
        if (!unknown.isEmpty()) {
            out.append("\n未登记的工具名（已忽略）: ").append(String.join(", ", unknown));
        }
        return out.toString();
    }
}
