package net.hegq.mos4j.tool;

import java.util.List;

/**
 * 工具规范的聚合渲染（T5.10）。
 *
 * <p>宿主把各工具的 {@link Guideline} 交给这里，得到一段可拼进系统提示词
 * {@code tools} 段的文本；没有 guideline 时返回空串（不产生空段）。
 * 渲染是纯函数：同样的规范集合渲染结果逐字节一致（提示词 cache 友好，M8/I4 同源纪律）。
 */
public final class Guidelines {

    private Guidelines() {
    }

    /**
     * 渲染全部规范。
     *
     * @param guidelines 规范集合（可空）
     * @return 渲染文本（无规范时为空串）
     */
    public static String render(List<Guideline> guidelines) {
        if (guidelines == null || guidelines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Guideline guideline : guidelines) {
            if (guideline == null) {
                continue;
            }
            String text = guideline.guideline();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(guideline.render());
        }
        return out.toString();
    }
}
