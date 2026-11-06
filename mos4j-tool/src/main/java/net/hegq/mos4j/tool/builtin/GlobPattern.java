package net.hegq.mos4j.tool.builtin;

import com.google.re2j.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * glob 模式匹配（任务 T5.2）。
 *
 * <p><b>为什么用 RE2J 而不是手写状态机</b>：模式来自模型（不可信输入），
 * 自己写递归下降匹配器容易被恶意模式拖入指数回溯。RE2 是线性时间引擎，
 * 把模式编译交给它，匹配成本可预期。
 *
 * <p><b>语义（与常见 glob 一致）</b>：
 * <ul>
 *   <li>{@code *} — 不跨 {@code /} 的任意串</li>
 *   <li>{@code **} — 跨 {@code /} 的任意串（含空）</li>
 *   <li>{@code ?} — 恰好一个字符（不含 {@code /}）</li>
 *   <li>{@code [abc]} / {@code [a-z]} / {@code [!abc]} — 字符类（{@code !} 取反，POSIX 风格）</li>
 *   <li>{@code {a,b}} — 花括号展开（支持嵌套一层）</li>
 *   <li>{@code \\} — 转义下一个字符</li>
 * </ul>
 *
 * <p><b>匹配范围</b>：模式不含 {@code /} 时按<b>文件名</b>匹配；
 * 含 {@code /} 时按<b>相对根的完整路径</b>匹配。
 */
final class GlobPattern {

    private final Pattern pattern;
    private final String source;

    private GlobPattern(Pattern pattern, String source) {
        this.pattern = pattern;
        this.source = source;
    }

    /** 源模式（诊断用）。 */
    String source() {
        return source;
    }

    /**
     * 编译 glob 模式。
     *
     * @param glob glob 模式
     * @return 编译后的匹配器
     * @throws IllegalArgumentException 模式非法（括号不配对等）
     */
    static GlobPattern compile(String glob) {
        if (glob == null || glob.isEmpty()) {
            throw new IllegalArgumentException("glob: 模式不能为空");
        }
        String regex = toRegex(glob);
        try {
            return new GlobPattern(Pattern.compile(regex), glob);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("glob: 模式非法 \"" + glob + "\": " + e.getMessage(), e);
        }
    }

    /** 是否匹配。 */
    boolean matches(String text) {
        return pattern.matches(text);
    }

    /**
     * glob → 正则（RE2J 语法）。
     */
    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        List<int[]> braceSpans = new ArrayList<>(); // 花括号 span（校验配对用）
        int i = 0;
        int len = glob.length();
        while (i < len) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < len && glob.charAt(i + 1) == '*') {
                        i += 2;
                        // `**/` → 目录部分可选（src/**/x 同时匹配 src/x 与 src/a/x）
                        if (i < len && glob.charAt(i) == '/') {
                            sb.append("(?:.*/)?");
                            i++;
                        } else {
                            sb.append(".*");
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    sb.append("[^/]");
                    i++;
                }
                case '[' -> {
                    i = appendCharClass(glob, i, sb);
                }
                case '{' -> {
                    int close = findClosingBrace(glob, i);
                    if (close < 0) {
                        throw new IllegalArgumentException("花括号不配对");
                    }
                    sb.append("(?:");
                    braceSpans.add(new int[]{i, close});
                    i++;
                }
                case '}' -> {
                    int idx = i;
                    if (braceSpans.stream().noneMatch(span -> span[1] == idx)) {
                        throw new IllegalArgumentException("花括号不配对");
                    }
                    sb.append(')');
                    i++;
                }
                case ',' -> {
                    int idx = i;
                    boolean insideBrace = braceSpans.stream().anyMatch(
                            span -> idx > span[0] && idx < span[1]);
                    sb.append(insideBrace ? "|" : ",");
                    i++;
                }
                case '\\' -> {
                    if (i + 1 >= len) {
                        throw new IllegalArgumentException("转义符后没有字符");
                    }
                    sb.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                    i += 2;
                }
                default -> {
                    sb.append(Pattern.quote(String.valueOf(c)));
                    i++;
                }
            }
        }
        return sb.toString();
    }

    /**
     * 追加字符类 {@code [...]}，返回消费后的下标。
     *
     * <p>{@code [!...]} 转成 RE2J 的 {@code [^...]}；{@code ]} 放首位按字面量处理（POSIX 风格）。
     */
    private static int appendCharClass(String glob, int start, StringBuilder sb) {
        int i = start + 1;
        int len = glob.length();
        StringBuilder body = new StringBuilder();
        boolean negate = false;
        if (i < len && glob.charAt(i) == '!') {
            negate = true;
            i++;
        }
        // ] 放在首位是字面量（POSIX 风格）
        if (i < len && glob.charAt(i) == ']') {
            body.append("\\]");
            i++;
        }
        while (i < len && glob.charAt(i) != ']') {
            char c = glob.charAt(i);
            if (c == '\\' && i + 1 < len) {
                body.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                i += 2;
                continue;
            }
            if (c == '[' && i + 1 < len && glob.charAt(i + 1) == ':') {
                throw new IllegalArgumentException("不支持 POSIX 字符类（[:alpha:] 等）");
            }
            body.append(c);
            i++;
        }
        if (i >= len) {
            throw new IllegalArgumentException("字符类不配对（缺少 ]）");
        }
        sb.append('[').append(negate ? "^" : "").append(body).append(']');
        return i + 1;
    }

    /** 找配对的 {@code }}（忽略嵌套与字符类里的花括号；本实现只支持一层花括号）。 */
    private static int findClosingBrace(String glob, int open) {
        int depth = 0;
        for (int i = open; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
