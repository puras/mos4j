package net.hegq.mos4j.context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具输出的截断（行/字节双限 + 按工具定向保留）。
 *
 * <p><b>为什么截断要分工具</b>：同样是「太长」，有用的部分不一样。
 * 读文件时**开头**是内容（要从头读），命令输出时**结尾**才是结果与报错
 * （报错几乎总在最后一行）。一刀切留头会把报错丢掉，模型看到「跑得好好的」，
 * 然后开始编造；一刀切留尾会把文件头丢掉，模型看不到结构。
 *
 * <p><b>为什么按 UTF-8 字节算</b>：模型上下文按 token 限，但限的是字符；
 * 一个中文字符 3 字节，一个 emoji 4 字节。按字符数截会截出半个字符
 * （半个代理对 = 乱码），按字节截又必须落在字符边界上，否则一样是乱码。
 * 所以：按字节限，切在字符边界。
 *
 * <p><b>硬上限是硬的</b>：截断标记本身也占预算——标记写在外头不算的话，
 * 「最多 50KB」实际上会变成 50KB + 标记长度，边界就名存实亡了。
 */
public final class Truncation {

    /** 缺省行数上限。 */
    public static final int DEFAULT_MAX_LINES = 2000;

    /** 缺省字节上限（50KB）。 */
    public static final int DEFAULT_MAX_BYTES = 50 * 1024;

    /** 留哪些内容。 */
    public enum Keep {
        /** 留头（读文件类）。 */
        HEAD,
        /** 留尾（命令执行类）。 */
        TAIL,
        /** 两头各半（其余）。 */
        BOTH
    }

    private final int maxLines;
    private final int maxBytes;
    private final Set<String> headTools;
    private final Set<String> tailTools;

    private Truncation(Builder builder) {
        this.maxLines = builder.maxLines;
        this.maxBytes = builder.maxBytes;
        this.headTools = Set.copyOf(builder.headTools);
        this.tailTools = Set.copyOf(builder.tailTools);
    }

    /** 缺省策略：2000 行 / 50KB；读文件留头、shell 留尾、其余两头各半。 */
    public static Truncation defaultPolicy() {
        return builder().build();
    }

    /** 构造器。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 行数上限。 */
    public int maxLines() {
        return maxLines;
    }

    /** 字节上限。 */
    public int maxBytes() {
        return maxBytes;
    }

    /**
     * 某个工具用哪种保留策略。
     *
     * @param toolName 工具名（可空）
     * @return 保留策略
     */
    public Keep keepFor(String toolName) {
        if (toolName != null) {
            if (headTools.contains(toolName)) {
                return Keep.HEAD;
            }
            if (tailTools.contains(toolName)) {
                return Keep.TAIL;
            }
        }
        return Keep.BOTH;
    }

    /**
     * 截断一段工具输出。
     *
     * <p><b>没超限就原样返回</b>：截断会改内容（加标记），不该截的时候一个字都不动——
     * 模型看到的内容与工具真实产出逐字一致，是「日志即事实」在工具层的延伸。
     *
     * @param toolName 工具名（决定保留策略）
     * @param content  原始内容
     * @return 截断结果
     */
    public TruncatedOutput apply(String toolName, String content) {
        return apply(toolName, content, maxBytes);
    }

    /**
     * 按给定的字节上限截断（比缺省上限更小时用）。
     *
     * <p>给框架自己的注解留预算用：落盘提示要跟在截断内容后面给模型看，
     * 那段提示也得占预算，否则「最多 50KB」会被提示顶破。
     *
     * @param toolName  工具名（决定保留策略）
     * @param content   原始内容
     * @param byteLimit 字节上限
     * @return 截断结果
     */
    public TruncatedOutput apply(String toolName, String content, int byteLimit) {
        String text = content == null ? "" : content;
        Keep keep = keepFor(toolName);
        List<String> lines = splitLines(text);
        int originalBytes = utf8Length(text);

        int keptLineCount = Math.min(lines.size(), maxLines);
        boolean lineTruncated = lines.size() > maxLines;
        boolean byteTruncated = false;
        String result = text;
        // 保留行数只数内容行，不数截断标记（标记不是原文的一部分）
        int keptLines = lines.size();

        if (lineTruncated) {
            result = joinByPolicy(lines, keptLineCount, keep, lines.size());
            keptLines = keptLineCount;
        }

        int keptBytes = utf8Length(result);
        if (keptBytes > byteLimit) {
            byteTruncated = true;
            Cut cut = cutByBytes(result, byteLimit, keep);
            result = cut.text();
            keptLines = cut.lines();
            keptBytes = utf8Length(result);
        }

        boolean truncated = lineTruncated || byteTruncated;
        return new TruncatedOutput(result, truncated, lines.size(), originalBytes,
                keptLines, keptBytes, keep);
    }

    /**
     * 按保留策略拼内容（并插截断标记）。
     */
    private String joinByPolicy(List<String> lines, int kept, Keep keep, int total) {
        List<String> head = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        switch (keep) {
            case HEAD -> head.addAll(lines.subList(0, kept));
            case TAIL -> tail.addAll(lines.subList(lines.size() - kept, lines.size()));
            case BOTH -> {
                int half = kept / 2;
                head.addAll(lines.subList(0, half));
                tail.addAll(lines.subList(lines.size() - (kept - half), lines.size()));
            }
            default -> throw new IllegalStateException("未知的保留策略: " + keep);
        }
        return assemble(head, tail, marker(total, head.size(), tail.size(), keep));
    }

    /**
     * 截断标记：说清楚丢了什么。
     *
     * <p>只写「已截断」不够——模型不知道丢了多少、丢的是哪头，
     * 会以为文件本来就那么短，然后基于残缺内容下结论。
     */
    private String marker(int total, int headLines, int tailLines, Keep keep) {
        StringBuilder sb = new StringBuilder("…（已截断：共 ").append(total).append(" 行");
        switch (keep) {
            case HEAD -> sb.append("，仅保留前 ").append(headLines).append(" 行");
            case TAIL -> sb.append("，仅保留后 ").append(tailLines).append(" 行");
            case BOTH -> sb.append("，仅保留前 ").append(headLines)
                    .append(" 行与后 ").append(tailLines).append(" 行");
            default -> sb.append("，仅保留部分");
        }
        return sb.append("）").toString();
    }

    /** 极小预算下的短标记。 */
    private static final String SHORT_MARKER = "…（已截断）";

    /**
     * 按字节上限裁剪（落在字符边界上，且**含标记**也不超过上限）。
     *
     * <p>标记长度依赖切分结果、切分结果又依赖给标记留多少预算——所以迭代收敛：
     * 先按保守预留切一次，用实际标记长度修正预留，再切一次。
     */
    private Cut cutByBytes(String text, int limit, Keep keep) {
        if (limit <= 0) {
            return new Cut("", 0);
        }
        if (limit < utf8Length(SHORT_MARKER)) {
            // 预算小到连标记都放不下：保留能放下的标记前缀，内容全丢
            return new Cut(cutHead(SHORT_MARKER, limit), 0);
        }
        int reserve = Math.min(96, Math.max(0, limit / 2));
        Cut result = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            int budget = Math.max(0, limit - reserve);
            String head;
            String tail;
            switch (keep) {
                case HEAD -> {
                    head = cutHead(text, budget);
                    tail = "";
                }
                case TAIL -> {
                    head = "";
                    tail = cutTail(text, budget);
                }
                case BOTH -> {
                    int half = budget / 2;
                    head = cutHead(text, half);
                    tail = cutTail(text, budget - utf8Length(head));
                }
                default -> throw new IllegalStateException("未知的保留策略: " + keep);
            }
            String mark = marker(countLines(text), countLines(head), countLines(tail), keep);
            int actualReserve = utf8Length(mark)
                    + (head.isEmpty() ? 0 : 1)
                    + (tail.isEmpty() ? 0 : 1);
            String candidate = assemble(List.of(head), List.of(tail), mark);
            int size = utf8Length(candidate);
            if (size <= limit) {
                result = new Cut(candidate, countLines(head) + countLines(tail));
                break;
            }
            // 标记比预估的长（或内容没填满预算）：按实际标记长度重算预留
            reserve = actualReserve + 1;
        }
        if (result == null) {
            // 兜底：只保留标记
            result = new Cut(marker(countLines(text), 0, 0, keep), 0);
        }
        return result;
    }

    /**
     * 拼装：头 + 标记 + 尾（空段不占换行）。
     */
    private static String assemble(List<String> head, List<String> tail, String mark) {
        StringBuilder sb = new StringBuilder();
        for (String part : head) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part);
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(mark);
        for (String part : tail) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append('\n').append(part);
        }
        return sb.toString();
    }

    /**
     * 保留前缀（UTF-8 字节数不超过 limit，且不切开代理对）。
     */
    private static String cutHead(String text, int limit) {
        if (limit <= 0) {
            return "";
        }
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int cpBytes = utf8Length(codePoint);
            if (bytes + cpBytes > limit) {
                break;
            }
            bytes += cpBytes;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    /**
     * 保留后缀（UTF-8 字节数不超过 limit，且不切开代理对）。
     */
    private static String cutTail(String text, int limit) {
        if (limit <= 0) {
            return "";
        }
        int bytes = 0;
        int start = text.length();
        while (start > 0) {
            int codePoint = text.codePointBefore(start);
            int cpBytes = utf8Length(codePoint);
            if (bytes + cpBytes > limit) {
                break;
            }
            bytes += cpBytes;
            start -= Character.charCount(codePoint);
        }
        return text.substring(start);
    }

    /** 按 {@code \n} 拆行（保留空行与末尾空段）。 */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }

    private static int countLines(String text) {
        return splitLines(text).size();
    }

    /** 字符串的 UTF-8 字节数。 */
    static int utf8Length(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 单个码点的 UTF-8 字节数。 */
    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /** 裁剪结果（文本 + 保留的内容行数）。 */
    private record Cut(String text, int lines) {
    }

    /** 构造器。 */
    public static final class Builder {

        private int maxLines = DEFAULT_MAX_LINES;
        private int maxBytes = DEFAULT_MAX_BYTES;
        /**
         * 留头的工具：文件开头是结构（匹配行在前，尾部多为重复命中）。
         *
         * <p>与 Go 参考实现逐字一致（{@code agent/truncate.go}）：
         * {@code read} / {@code current_time} / {@code grep} / {@code glob} / {@code ls}。
         */
        private final Set<String> headTools =
                new LinkedHashSet<>(List.of("read", "current_time", "grep", "glob", "ls"));

        /**
         * 留尾的工具：编译/测试的报错与摘要几乎总在输出末尾。
         *
         * <p>Go 侧只有 {@code shell}；Java 侧多留一个 {@code bash} 别名（历史兼容）。
         */
        private final Set<String> tailTools = new LinkedHashSet<>(List.of("shell", "bash"));

        private Builder() {
        }

        public Builder maxLines(int maxLines) {
            this.maxLines = maxLines;
            return this;
        }

        public Builder maxBytes(int maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        public Builder headTools(Set<String> names) {
            headTools.clear();
            headTools.addAll(names);
            return this;
        }

        public Builder tailTools(Set<String> names) {
            tailTools.clear();
            tailTools.addAll(names);
            return this;
        }

        public Truncation build() {
            if (maxLines <= 0) {
                throw new IllegalArgumentException("context: maxLines 必须为正");
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("context: maxBytes 必须为正");
            }
            return new Truncation(this);
        }
    }
}
