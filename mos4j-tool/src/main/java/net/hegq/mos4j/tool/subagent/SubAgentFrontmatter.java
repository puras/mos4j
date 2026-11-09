package net.hegq.mos4j.tool.subagent;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.databind.DeserializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code AGENT.md} 的 frontmatter 拆分与解析。
 *
 * <p><b>为什么不复用技能的 frontmatter 解析</b>：两个包的字段集不同
 * （技能是 {@code name/description/when-to-use/allowed-tools}，
 * 子代理是 {@code name/description/model/tools}），解析辅助各写一份——
 * 抽公共包要有明确需求，不是「看着像重复」。
 *
 * <p><b>为什么用成熟 YAML 方案</b>：frontmatter 是 YAML，YAML 的坑（布尔字面量、标签、
 * 锚点、缩进）远比 frontmatter 需要的子集多。手写「最小 YAML 子集」等于自造第二套
 * 解析真相。这里用 Jackson 的 YAML 模块，并把与 Go 侧 {@code yaml.v3} 的口径差异对齐
 * （裸 {@code ~} 归一成 null）。
 */
final class SubAgentFrontmatter {

    /** frontmatter 文本上限（1 MiB）：超过即视为坏定义（跳过并诊断）。 */
    static final int MAX_FRONTMATTER_BYTES = 1024 * 1024;

    private static final YAMLMapper YAML = YAMLMapper.builder()
            // 与 Go 的 yaml.v3 对齐：重复键是错误（Go: "mapping key already defined"），
            // Jackson 默认后者覆盖前者——那会让「两个 name」悄悄取后者。
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SubAgentFrontmatter() {
    }

    /**
     * 拆分结果。
     *
     * @param front frontmatter 文本（{@code ---} 之间的内容）
     * @param body  正文（{@code ---} 之后的内容）
     */
    record Split(String front, String body) {
    }

    /**
     * 拆出 frontmatter 与正文。
     *
     * <p>只认<b>文件开头</b>的 frontmatter（首行必须是 {@code ---}）：正文里出现的
     * {@code ---} 不是分隔符（水平线在 Markdown 里太常见，当分隔符会把正文拦腰截断）。
     *
     * @param raw 文件原始字节
     * @return 拆分结果；没有 frontmatter 时 {@code front} 为 null
     */
    static Split split(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            // BOM 开头是合法的 UTF-8 文件（Windows 编辑器常带），但它会让首行
            // 不再是 "---" —— 子代理被误判成「没有 frontmatter」。
            text = text.substring(1);
        }
        if (!text.startsWith("---")) {
            return new Split(null, text);
        }
        String rest = text.substring(3);
        if (rest.startsWith("\r")) {
            rest = rest.substring(1);
        }
        if (rest.startsWith("\n")) {
            rest = rest.substring(1);
        }
        List<String> lines = new ArrayList<>(List.of(rest.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().equals("---")) {
                return new Split(String.join("\n", lines.subList(0, i)),
                        String.join("\n", lines.subList(i + 1, lines.size())));
            }
        }
        return new Split(null, text);
    }

    /**
     * 解析 frontmatter 为字段表。
     *
     * <p>走「先解成 map 再挑字段」的路子，而不是直接解进结构体：frontmatter 是
     * <b>宿主写的文件</b>，字段类型可能不认（{@code tools} 写成字符串、
     * {@code model} 写成数字）。直接解结构体遇到一个怪字段就整份报废；
     * 解成 map 再挑，坏的单个字段被忽略，其余照常能用。
     *
     * @param front frontmatter 文本
     * @return 字段表（空表 = 没有可识别字段）
     * @throws SubAgentException 不是合法 YAML
     */
    static Map<String, Object> parse(String front) {
        if (front == null) {
            throw new SubAgentException("缺少 frontmatter（文件须以 --- 开头）");
        }
        if (front.getBytes(StandardCharsets.UTF_8).length > MAX_FRONTMATTER_BYTES) {
            throw new SubAgentException("frontmatter 过大（上限 " + MAX_FRONTMATTER_BYTES + " 字节）");
        }
        Map<String, Object> raw;
        if (front.isBlank()) {
            raw = Map.of();
        } else {
            try {
                Map<String, Object> parsed = YAML.readValue(front, Map.class);
                raw = parsed == null ? Map.of() : parsed;
            } catch (Exception e) {
                throw new SubAgentException("不是合法 YAML: " + e.getMessage(), e);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            normalized.put(entry.getKey(), normalize(entry.getValue()));
        }
        return normalized;
    }

    /** 对齐 Go {@code yaml.v3} 的标量口径：裸 {@code ~} 解析成 null。 */
    private static Object normalize(Object value) {
        if (value instanceof String text) {
            return "~".equals(text) ? null : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    /** 字符串字段（非字符串 → 空串）。 */
    static String asString(Object value) {
        return value instanceof String text ? text : "";
    }

    /** 字符串列表字段：接受列表，也接受单个字符串（当一项）；空串丢弃。 */
    static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    out.add(text.strip());
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            out.add(text.strip());
        }
        return out;
    }
}
