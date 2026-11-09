package net.hegq.mos4j.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词分节装配。
 *
 * <p><b>为什么要分节</b>：提示词是一堆不同来源、不同生命周期的内容
 * （宿主规则、项目说明、环境信息、工具说明、历史摘要）。拼成一坨字符串之后，
 * 没人能只改其中一段——改工具说明就得重写整个提示词。
 *
 * <p><b>为什么要固定顺序</b>：服务端对提示词前缀做缓存（prompt cache）。
 * 顺序一变 → 前缀变了 → 缓存全废 → 每轮都按全价重新计费。
 * 所以段的位置由 {@link #CANONICAL_ORDER} 决定，不按加入顺序漂移。
 *
 * <p><b>为什么装配期渲染一次</b>：渲染一次、之后每轮复用同一份字符串，
 * 逐字节一致是缓存命中的前提。每轮重新拼（哪怕结果一样）都可能因为
 * 拼接顺序或空值处理产生细微差异。
 */
public final class PromptSections {

    /**
     * 中心顺序常量：段的标准排列顺序。
     *
     * <p>不在此表中的段按加入顺序排在最后（宿主自定义段不抢标准段的位置）。
     *
     * <p>{@code mcp_servers} 落在 {@code skills} 之后、{@code instructions} 之前：
     * 技能清单 → MCP 服务器摘要 → 工具指引（与 Go 参考实现的提示词顺序一致）。
     * <p>{@code subagents} 落在 {@code mcp_servers} 之后：子代理也是「有哪些能力可用」的索引，
     * 但它是<b>间接</b>能力（模型要先想到派谁才用得上），比本地技能与远端工具都更靠后。
     */
    public static final List<String> CANONICAL_ORDER =
            List.of("base", "environment", "context", "skills", "mcp_servers", "subagents",
                    "instructions", "tools");

    private final List<PromptSection> sections;
    private final String rendered;

    private PromptSections(List<PromptSection> sections) {
        this.sections = List.copyOf(sections);
        this.rendered = render(this.sections);
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 段列表（按最终顺序）。 */
    public List<PromptSection> sections() {
        return sections;
    }

    /**
     * 渲染后的提示词（装配期算一次，之后逐字节复用）。
     *
     * @return 提示词文本
     */
    public String render() {
        return rendered;
    }

    /** 取某一段的内容（不存在返回 null）。 */
    public String content(String name) {
        for (PromptSection section : sections) {
            if (section.name().equals(name)) {
                return section.content();
            }
        }
        return null;
    }

    private static String render(List<PromptSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (PromptSection section : sections) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("## ").append(section.name()).append('\n').append(section.content());
        }
        return sb.toString();
    }

    /**
     * 往已经渲染好的提示词里插入一段（位置由中心顺序决定）。
     *
     * <p><b>为什么需要字符串层的插入</b>：宿主常常把提示词写成一整串自己交给 {@code Config}
     * （不经过 {@link Builder}）。技能清单仍然必须落在<b>中心顺序的位置</b>上（项目上下文之后、
     * 工具指引之前），否则同一份提示词换个装配路径就换了顺序，prompt cache 就废了。
     *
     * <p>算法：找到第一个「中心顺序比 {@code name} 靠后」的段标题（形如 {@code ## x}，
     * 即 {@link #render} 的输出形态），插到它前面；没有这样的段就追加到末尾。
     * 非标准段排在所有标准段之后。
     *
     * <p>不负责去重：调用方保证同一个段不插两次（装配期插一次就固定，不同于运行期改写）。
     *
     * @param rendered 已渲染的提示词（可空 = 空提示词）
     * @param name     段名（不能为空）
     * @param content  段内容（空 = 不插）
     * @return 插入后的提示词
     */
    public static String insert(String rendered, String name, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("prompt: 段名不能为空");
        }
        if (content == null || content.isEmpty()) {
            return rendered == null ? "" : rendered;
        }
        String base = rendered == null ? "" : rendered;
        String block = "## " + name + "\n" + content;
        int self = positionOf(name);
        int at = -1;
        int lineStart = 0;
        while (lineStart < base.length()) {
            int lineEnd = base.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = base.length();
            }
            String line = base.substring(lineStart, lineEnd);
            if (line.startsWith("## ")) {
                String other = line.substring(3).strip();
                if (positionOf(other) > self) {
                    at = lineStart;
                    break;
                }
            }
            lineStart = lineEnd + 1;
        }
        if (at < 0) {
            return base.isEmpty() ? block : base + "\n\n" + block;
        }
        return base.substring(0, at) + block + "\n\n" + base.substring(at);
    }

    /** 段在中心顺序里的位置（非标准段视为最后）。 */
    private static int positionOf(String name) {
        int index = CANONICAL_ORDER.indexOf(name);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * 装配器。
     *
     * <p>三个动词的差别是刻意的：
     * <ul>
     *   <li>{@code add} —— <b>严格插入</b>：段名必须全新，重名直接报错。
     *       静默合并会让「我加了一段」变成「我覆盖了别人的段」，这类 bug 极难查。</li>
     *   <li>{@code set} —— <b>覆写</b>：段必须已存在（不存在报错：覆写一个不存在的段
     *       说明调用方搞错了段名），内容替换，位置不变。</li>
     *   <li>{@code complete} —— <b>整段替换</b>：段必须已存在，整段内容换新
     *       并回到中心顺序里的位置（可能与别的段交换先后）。</li>
     * </ul>
     */
    public static final class Builder {

        private final Map<String, String> content = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * 严格插入一个新段。
         *
         * @param name    段名（必须未被使用）
         * @param content 段内容
         * @return this
         */
        public Builder add(String name, String content) {
            requireName(name);
            if (this.content.containsKey(name)) {
                throw new IllegalStateException("prompt: 段已存在，不能重复插入: " + name);
            }
            this.content.put(name, content == null ? "" : content);
            return this;
        }

        /**
         * 覆写已有段的内容（位置不变）。
         *
         * @param name    段名（必须已存在）
         * @param content 新内容
         * @return this
         */
        public Builder set(String name, String content) {
            requireName(name);
            if (!this.content.containsKey(name)) {
                throw new IllegalStateException("prompt: 段不存在，不能覆写: " + name);
            }
            this.content.put(name, content == null ? "" : content);
            return this;
        }

        /**
         * 整段替换：内容换新，并回到中心顺序里的位置（非标准段会移到同类末尾）。
         *
         * @param name    段名（必须已存在）
         * @param content 新内容
         * @return this
         */
        public Builder complete(String name, String content) {
            requireName(name);
            if (!this.content.containsKey(name)) {
                throw new IllegalStateException("prompt: 段不存在，不能整段替换: " + name);
            }
            this.content.put(name, content == null ? "" : content);
            // 整段替换 = 拿出来重新放回中心顺序（位置可能被别的段挤掉）
            String value = this.content.remove(name);
            this.content.put(name, value);
            return this;
        }

        /**
         * 派生一份独立副本（改副本不影响原装配器）。
         *
         * @return 副本
         */
        public Builder clone() {
            Builder copy = new Builder();
            copy.content.putAll(this.content);
            return copy;
        }

        /**
         * 是否已有某一段。
         *
         * <p>给框架侧用：宿主注入的装配器可能已经放了同名段，框架要能先问一句
         * 「这个位置是不是已经有人占了」，而不是直接撞上「段已存在」的报错。
         *
         * @param name 段名
         * @return 存在则 {@code true}
         */
        public boolean has(String name) {
            return name != null && this.content.containsKey(name);
        }

        /**
         * 取某一段的内容（不存在返回 {@code null}）。
         *
         * <p>框架侧用来读「宿主已经放好的段」（比如项目上下文那一段），
         * 不必先 {@code build()} 再回头找。
         *
         * @param name 段名
         * @return 段内容；不存在返回 {@code null}
         */
        public String content(String name) {
            return name == null ? null : this.content.get(name);
        }

        /** 装配完成（渲染一次）。 */
        public PromptSections build() {
            return new PromptSections(order(content));
        }

        private void requireName(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("prompt: 段名不能为空");
            }
        }

        /**
         * 按中心顺序排序：标准段在前（按常量顺序），其余按加入顺序在后。
         */
        private static List<PromptSection> order(Map<String, String> content) {
            List<PromptSection> ordered = new ArrayList<>();
            for (String name : CANONICAL_ORDER) {
                String value = content.get(name);
                if (value != null) {
                    ordered.add(new PromptSection(name, value));
                }
            }
            for (Map.Entry<String, String> entry : content.entrySet()) {
                if (!CANONICAL_ORDER.contains(entry.getKey())) {
                    ordered.add(new PromptSection(entry.getKey(), entry.getValue()));
                }
            }
            return ordered;
        }
    }
}
