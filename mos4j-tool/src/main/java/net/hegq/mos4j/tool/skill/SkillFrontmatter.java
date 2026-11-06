package net.hegq.mos4j.tool.skill;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.databind.DeserializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code SKILL.md} 的 frontmatter 拆分与解析。
 *
 * <p><b>为什么用成熟 YAML 方案</b>：frontmatter 是 YAML，YAML 的坑（布尔字面量、
 * 标签、锚点、缩进）远比 frontmatter 需要的子集多。手写一个「最小 YAML 子集」
 * 等于自己造第二套解析真相——遇到子集没覆盖的写法时，行为取决于实现细节，
 * 而技能文件是宿主写的、随时会变。这里用 Jackson 的 YAML 模块（成熟实现），
 * 并把与 Go 侧 {@code yaml.v3} 的口径差异逐条对齐（见 {@link #normalize}）。
 *
 * <p><b>安全边界</b>：不开 default typing，只解成 {@code Map}（受控数据），
 * YAML 标签不会实例化任意 Java 类；frontmatter 文本有大小上限（见
 * {@link #MAX_FRONTMATTER_BYTES}），避免一个畸形文件把内存吃光。
 */
final class SkillFrontmatter {

    /** frontmatter 文本上限（1 MiB）：超过即视为坏技能（跳过并诊断）。 */
    static final int MAX_FRONTMATTER_BYTES = 1024 * 1024;

    private static final YAMLMapper YAML = YAMLMapper.builder()
            // 与 Go 的 yaml.v3 对齐：重复键是错误（Go: "mapping key already defined"），
            // Jackson 默认后者覆盖前者——那会让「两个 name」悄悄取后者。
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SkillFrontmatter() {
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
     * {@code ---} 不是分隔符（水平线在 Markdown 里太常见了，当分隔符会把正文拦腰截断）。
     *
     * @param raw 文件原始字节
     * @return 拆分结果；没有 frontmatter 时 {@code front} 为 null
     */
    static Split split(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            // BOM 开头是合法的 UTF-8 文件（Windows 编辑器常带），但它会让首行
            // 不再是 "---" —— 技能被误判成「没有 frontmatter」。
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
                String front = String.join("\n", lines.subList(0, i));
                String body = String.join("\n", lines.subList(i + 1, lines.size()));
                return new Split(front, body);
            }
        }
        return new Split(null, text);
    }

    /**
     * 解析 frontmatter 为元数据。
     *
     * <p>走「先解成 map 再挑字段」的路子，而不是直接解进结构体：frontmatter 是
     * <b>宿主写的文件</b>，字段类型可能不认（{@code metadata} 写成字符串、布尔写成
     * {@code "true"}）。直接解结构体遇到一个怪字段就整份报废；解成 map 再挑，
     * 坏的单个字段被忽略，其余照常能用。
     *
     * @param front frontmatter 文本
     * @return 元数据（未校验；校验由调用方做，fail-closed）
     * @throws SkillException 不是合法 YAML
     */
    static SkillMetadata parse(String front) {
        if (front == null) {
            throw new SkillException("缺少 frontmatter（文件须以 --- 开头）");
        }
        if (front.getBytes(StandardCharsets.UTF_8).length > MAX_FRONTMATTER_BYTES) {
            throw new SkillException("frontmatter 过大（上限 " + MAX_FRONTMATTER_BYTES + " 字节）");
        }
        Map<String, Object> raw;
        if (front.isBlank()) {
            raw = Map.of();
        } else {
            try {
                Map<String, Object> parsed = YAML.readValue(front, Map.class);
                raw = parsed == null ? Map.of() : parsed;
            } catch (Exception e) {
                throw new SkillException("不是合法 YAML: " + e.getMessage(), e);
            }
        }
        Map<String, Object> normalized = normalize(raw);
        Map<String, Object> extra = asMap(normalized.get("metadata"));
        return new SkillMetadata(
                asString(normalized.get("name")),
                asString(normalized.get("description")),
                asString(normalized.get("when-to-use")),
                asStringList(normalized.get("allowed-tools")),
                asBool(normalized.get("disable-model-invocation")),
                extra);
    }

    /**
     * 对齐 Go {@code yaml.v3} 的标量口径。
     *
     * <p>唯一已知的差异：裸 {@code ~}。Go 的 yaml.v3 把它解析成 {@code nil}，
     * Jackson 3 解析成字符串 {@code "~"}——照搬会让 {@code description: ~}
     * 变成「描述是波浪号」而不是「没有描述」，进而绕过「description 必需」的校验。
     * 这里把 {@code "~"} 归一成 null（与 Go 一致）。
     */
    private static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return out;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof String text) {
            return "~".equals(text) ? null : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        return value;
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    /** 字符串列表：接受列表，也接受单个字符串（当一项）；空串丢弃。 */
    private static List<String> asStringList(Object value) {
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

    /** 布尔：也接受 {@code "true"}/{@code "false"} 字符串——YAML 里手写字符串很常见。 */
    private static boolean asBool(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            String normalized = text.strip().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "yes".equals(normalized);
        }
        return false;
    }
}
