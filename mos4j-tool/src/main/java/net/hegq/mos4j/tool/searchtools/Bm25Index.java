package net.hegq.mos4j.tool.searchtools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 打分（k1=1.2, b=0.75）+ camelCase 分词 + 停用词 + 简单词干 + CJK bigram（T5.6）。
 *
 * <p><b>这是检索不是语义理解</b>：给一批工具描述（语料）按查询词打相关性分。
 * 用它做「工具太多，先搜再激活」（deferred 工具，T5.7）——
 * 模型看不到几百个工具定义时，先搜出最相关的几个。
 *
 * <p><b>词法</b>：
 * <ul>
 *   <li>camelCase / snake_case / kebab-case 拆词（工具名与参数名的常规形态）</li>
 *   <li>英文小写化 + 极简词干（复数 s/ing/ed 剥离，规则保守——宁可少剥不误伤）</li>
 *   <li>停用词表：英文高频词（the/a/of/…）不进索引</li>
 *   <li><b>CJK bigram</b>：中日韩文本按两字一组切（bigram）。
 *       中文没有空格分词，单字粒度太碎（「文件」会命中「文」「件」），
 *       词典分词又太重——bigram 是检索质量的常见折中</li>
 * </ul>
 */
public final class Bm25Index {

    /** BM25 k1。 */
    public static final double K1 = 1.2;
    /** BM25 b。 */
    public static final double B = 0.75;

    /** 停用词（英文高频词；全部小写）。 */
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "the", "a", "an", "of", "to", "in", "on", "for", "with", "and", "or",
            "is", "are", "be", "as", "by", "at", "it", "this", "that", "from",
            "into", "if", "then", "when", "use", "used", "using", "can", "will");

    /** 一条语料。 */
    public static final class Doc {
        final String id;
        final String text;
        final Map<String, Integer> termCounts = new LinkedHashMap<>();
        int length;

        Doc(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** 一条检索结果：文档 id + 相关性得分。 */
    public record Scored(String id, double score) {
    }

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> docFreq = new LinkedHashMap<>();

    /**
     * 添加语料。
     *
     * @param id   文档标识（检索结果回传）
     * @param text 文本（名称 + 描述拼在一起即可）
     */
    public void add(String id, String text) {
        Doc doc = new Doc(id, text == null ? "" : text);
        for (String term : tokenize(doc.text)) {
            doc.termCounts.merge(term, 1, Integer::sum);
        }
        doc.length = doc.termCounts.values().stream().mapToInt(Integer::intValue).sum();
        for (String term : doc.termCounts.keySet()) {
            docFreq.merge(term, 1, Integer::sum);
        }
        docs.add(doc);
    }

    /**
     * 检索。
     *
     * @param query 查询
     * @param topK  最多返回几条
     * @return 按得分降序的结果（得分为 0 的不返回）
     */
    public List<Scored> search(String query, int topK) {
        List<String> terms = tokenize(query == null ? "" : query);
        int n = docs.size();
        if (n == 0 || terms.isEmpty() || topK <= 0) {
            return List.of();
        }
        double avgLen = docs.stream().mapToInt(d -> d.length).average().orElse(0);
        if (avgLen <= 0) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (Doc doc : docs) {
            double score = 0;
            for (String term : terms) {
                Integer tf = doc.termCounts.get(term);
                if (tf == null) {
                    continue;
                }
                int df = docFreq.getOrDefault(term, 0);
                // IDF：语料内全部文档都含该词时分母取 1，避免除零与负权
                double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);
                double norm = tf * (K1 + 1)
                        / (tf + K1 * (1 - B + B * doc.length / avgLen));
                score += idf * norm;
            }
            if (score > 0) {
                scored.add(new Scored(doc.id, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    /**
     * 分词：camelCase / snake / kebab 拆词 + 词干 + 停用词 + CJK bigram。
     *
     * @param text 原文
     * @return 词元列表
     */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : splitWords(text)) {
            if (raw.isEmpty()) {
                continue;
            }
            if (isCjk(raw)) {
                // CJK：两字一组（bigram）；单字也留一份，查询只有一个汉字时仍有命中
                if (raw.length() == 1) {
                    out.add(raw);
                } else {
                    for (int i = 0; i + 2 <= raw.length(); i++) {
                        out.add(raw.substring(i, i + 2));
                    }
                }
                continue;
            }
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            if (STOPWORDS.contains(lower)) {
                continue;
            }
            String stemmed = stem(lower);
            if (!stemmed.isEmpty()) {
                out.add(stemmed);
            }
        }
        return out;
    }

    /** 按 camelCase / 非字母数字边界拆词。 */
    private static List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (current.length() > 0) {
                    char prev = current.charAt(current.length() - 1);
                    boolean boundary =
                            (Character.isLowerCase(prev) && Character.isUpperCase(c));
                    if (boundary) {
                        words.add(current.toString());
                        current.setLength(0);
                    }
                }
                current.append(c);
            } else {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                if (isCjkChar(c)) {
                    // 把连续 CJK 收成一个词段，交给 bigram
                    StringBuilder cjk = new StringBuilder().append(c);
                    while (i + 1 < len && isCjkChar(text.charAt(i + 1))) {
                        cjk.append(text.charAt(++i));
                    }
                    words.add(cjk.toString());
                }
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }

    /** 极简词干：只剥明显后缀，不猜语言学（剥过头比不剥更伤检索）。 */
    static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ing")) {
            return word.substring(0, word.length() - 3);
        }
        if (word.length() > 4 && word.endsWith("ed")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")
                && !word.endsWith("us") && !word.endsWith("is")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static boolean isCjk(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!isCjkChar(word.charAt(i))) {
                return false;
            }
        }
        return !word.isEmpty();
    }

    private static boolean isCjkChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
