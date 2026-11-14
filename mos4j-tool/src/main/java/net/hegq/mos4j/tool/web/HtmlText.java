package net.hegq.mos4j.tool.web;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;

/**
 * HTML → 纯文本与标题提取（对齐 Go {@code tool/web/fetch.go} 的
 * {@code extractTitle} / {@code htmlToText} / {@code decodeEntities}）。
 *
 * <p><b>为什么照抄 Go 的粗糙实现</b>：这是抓取侧的<b>输入清洗</b>，不是渲染器。
 * 目标只有一个——别把标签噪声塞进模型上下文。真正的 HTML 解析要考虑嵌套、
 * 表格、{@code <pre>} 空白保留，成本远超收益；Go 侧六条正则就够用，
 * 两边产出同形状比「转得更漂亮」重要（跨语言对位是可验证的）。
 *
 * <p><b>为什么不新增依赖</b>：SDK 模块禁 {@code java.util.regex}（CI C6 门），
 * 用已有的 RE2J。这几条正则（内联标志 {@code (?is)}、非贪婪 {@code .*?}、
 * 非捕获组 {@code (?:...)}、字符组）RE2 全部支持，可逐字移植。
 *
 * <p>实体解码刻意做成<b>两趟</b>（先命名实体、再数值实体），与 Go 一致：
 * {@code "&amp;#39;"} 在 Go 里先变 {@code "&#39;"} 再变 {@code "'"}，
 * 一趟扫描会停在 {@code "&#39;"}。
 */
final class HtmlText {

    /** {@code <title>} 的内容。 */
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /** 整块丢掉的内容（脚本 / 样式 / 兜底文本 / 内联 SVG）。 */
    private static final Pattern SKIP = Pattern.compile(
            "(?is)<(script|style|noscript|svg)[^>]*>.*?</(?:script|style|noscript|svg)>");

    /** 块级标签 → 换行（保留段落结构，否则整页挤成一行）。 */
    private static final Pattern BLOCK_BREAK = Pattern.compile(
            "(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|/section|/article|/header|/footer"
                    + "|/blockquote|pre)[^>]*>");

    /** 其余标签直接抹掉。 */
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    /** 常见命名实体（与 Go 的 {@code strings.NewReplacer} 同表同序）。 */
    private static final String[][] NAMED_ENTITIES = {
            {"&amp;", "&"},
            {"&lt;", "<"},
            {"&gt;", ">"},
            {"&quot;", "\""},
            {"&#39;", "'"},
            {"&apos;", "'"},
            {"&nbsp;", " "},
            {"&hellip;", "…"},
            {"&mdash;", "—"},
            {"&ndash;", "–"},
    };

    /** 数值实体最长位数（超长的必然非法，不必再往后找 {@code ;}）。 */
    private static final int MAX_ENTITY_DIGITS = 12;

    private HtmlText() {
    }

    /**
     * 取 {@code <title>} 的文本。
     *
     * @param html 原始 HTML
     * @return 标题（无标题时为空串）
     */
    static String title(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) {
            return "";
        }
        return toText(m.group(1)).strip();
    }

    /**
     * HTML → 可读纯文本：去脚本与样式、块级标签换行、去标签、解实体、压空白。
     *
     * @param html 原始 HTML
     * @return 纯文本（行内空白压缩成单个空格，空行丢弃）
     */
    static String toText(String html) {
        String s = SKIP.matcher(html).replaceAll(" ");
        s = BLOCK_BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            String squeezed = squeeze(line);
            if (squeezed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(squeezed);
        }
        return out.toString();
    }

    /**
     * 解 HTML 实体（命名实体 + 十进制 / 十六进制数值实体）。
     *
     * @param s 文本
     * @return 解码后的文本
     */
    static String decodeEntities(String s) {
        return decodeNumeric(decodeNamed(s));
    }

    /** 第一趟：命名实体（单趟替换，替换结果不再参与匹配）。 */
    private static String decodeNamed(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '&') {
                String replaced = null;
                int width = 0;
                for (String[] pair : NAMED_ENTITIES) {
                    if (s.startsWith(pair[0], i)) {
                        replaced = pair[1];
                        width = pair[0].length();
                        break;
                    }
                }
                if (replaced != null) {
                    out.append(replaced);
                    i += width;
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** 第二趟：数值实体（{@code &#39;} / {@code &#x27;}）。 */
    private static String decodeNumeric(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&' && i + 2 < s.length() && s.charAt(i + 1) == '#') {
                int limit = Math.min(s.length(), i + 3 + MAX_ENTITY_DIGITS);
                int semi = s.indexOf(';', i + 2);
                if (semi > i + 2 && semi < limit) {
                    String body = s.substring(i + 2, semi);
                    boolean hex = body.charAt(0) == 'x' || body.charAt(0) == 'X';
                    String digits = hex ? body.substring(1) : body;
                    int code = parseCodePoint(digits, hex ? 16 : 10);
                    if (code > 0) {
                        out.appendCodePoint(code);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 解析码点；非法返回 {@code -1}（调用方原样保留实体）。 */
    private static int parseCodePoint(String digits, int radix) {
        if (digits.isEmpty() || digits.length() > MAX_ENTITY_DIGITS) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = Character.digit(digits.charAt(i), radix);
            if (digit < 0) {
                return -1;
            }
            value = value * radix + digit;
            if (value > 0x10FFFF) {
                return -1;
            }
        }
        // 代理区码点不是合法字符（Go 侧 string(rune(n)) 会产出替换字符，这里保持一致：保留实体）
        if (value >= 0xD800 && value <= 0xDFFF) {
            return -1;
        }
        return value;
    }

    /**
     * 压行内空白（等价 Go 的 {@code strings.Join(strings.Fields(l), " ")}）。
     *
     * <p>手写而不调 {@code String.replaceAll}：那会走 {@code java.util.regex}，
     * 而 SDK 模块不用它。
     */
    private static String squeeze(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean pendingSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }
}