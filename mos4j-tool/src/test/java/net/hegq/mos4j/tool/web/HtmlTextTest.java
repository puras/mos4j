package net.hegq.mos4j.tool.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTML 转纯文本与标题提取（对齐 Go {@code tool/web/fetch.go} 的
 * {@code extractTitle} / {@code htmlToText} / {@code decodeEntities}）。
 *
 * <p>放在 {@code net.hegq.mos4j.tool.web} 包里，是为了测得着包内可见的
 * {@link HtmlText}——Go 侧这几个函数也都是不导出的，公开面保持一致。
 *
 * <p><b>这不是渲染器</b>：它只把标签噪声从模型上下文里剔除。期望值照 Go 的实现
 * 口径算（同输入同输出），不追求「转得漂亮」。
 */
class HtmlTextTest {

    @Test
    @DisplayName("整页转纯文本：去脚本样式、块级换行、解实体、压空白")
    void htmlToTextMatchesGo() {
        String html = "<html><head><title> 示例 页面 </title>"
                + "<style>body{color:red}</style>"
                + "<script>alert(1)</script></head>"
                + "<body><p>第一段 &amp; 内容</p>"
                + "<div>第二段&#39;s</div></body></html>";

        // 期望值是 Go htmlToText / extractTitle 的实测输出（.j7log2/htmlref）。
        // 注意 title 不是块级标签，抹掉后与首行合并——这是 Go 的原行为。
        assertEquals("示例 页面 第一段 & 内容\n第二段's", HtmlText.toText(html));
        assertEquals("示例 页面", HtmlText.title(html));
    }

    @Test
    @DisplayName("块级标签保留换行，行内标签只留空格，空行丢弃")
    void keepsBlockStructure() {
        String html = "<h1>标题</h1><p>一段 <b>粗</b> 文本</p><ul>"
                + "<li>甲</li><li>乙</li></ul><br><br><p></p>";

        assertEquals("标题\n一段 粗 文本\n甲\n乙", HtmlText.toText(html));
    }

    @Test
    @DisplayName("noscript / svg 整块丢弃（替换成一个空格，与 Go 同）")
    void dropsScriptLikeBlocks() {
        String html = "<body>正文<noscript>请开启 JS</noscript>"
                + "<svg><path d=\"M0 0\"/></svg>尾巴</body>";

        // Go：整块替换成单个空格（不是空串）→ 两个词之间留下一个空格
        assertEquals("正文 尾巴", HtmlText.toText(html));
    }

    @Test
    @DisplayName("标题提取：无 title / 空 title / title 带嵌套标签")
    void titleExtraction() {
        assertEquals("", HtmlText.title("<html><body>没有标题</body></html>"));
        assertEquals("", HtmlText.title("<title></title>"));
        assertEquals("带 标签 的标题", HtmlText.title("<TITLE>带 <b>标签</b> 的标题</TITLE>"));
        assertEquals("", HtmlText.title(""));
    }

    @Test
    @DisplayName("实体：命名 / 十进制 / 十六进制")
    void entities() {
        assertEquals("< > & \" '", HtmlText.decodeEntities("&lt; &gt; &amp; &quot; &apos;"));
        assertEquals("A", HtmlText.decodeEntities("&#65;"));
        assertEquals("…", HtmlText.decodeEntities("&hellip;"));
        assertEquals("—", HtmlText.decodeEntities("&mdash;"));
        assertEquals(" ", HtmlText.decodeEntities("&nbsp;"));
        assertEquals("😀", HtmlText.decodeEntities("&#128512;"));
    }

    @Test
    @DisplayName("实体：十六进制也解码（GO-DIFF：Go 的十六进制分支实际上从不生效）")
    void hexEntitiesAreDecoded() {
        // Go 的 htmlHexEnt 正则匹配 `&#x41;`，但回调里对 "x41" 调 ParseInt(16) 永远失败，
        // 所以 Go 实测输出是原样的 "&#x41;"。这里按 Go 正则的**意图**解码（GO-DIFF 已登记），
        // 因为把 `&#x27;` 这类实体原样发给模型是明确的噪声。
        assertEquals("A", HtmlText.decodeEntities("&#x41;"));
        assertEquals("A", HtmlText.decodeEntities("&#X41;"));
        assertEquals("😀", HtmlText.decodeEntities("&#x1F600;"));
        assertEquals("'", HtmlText.decodeEntities("&#x27;"));
    }

    @Test
    @DisplayName("实体：非法 / 越界 / 代理区 / 孤立 & 一律原样保留")
    void keepsInvalidEntityForms() {
        assertEquals("&#xZZ;", HtmlText.decodeEntities("&#xZZ;"), "非法十六进制原样保留");
        assertEquals("&#0;", HtmlText.decodeEntities("&#0;"), "0 码点非法，原样保留");
        assertEquals("&#1114112;", HtmlText.decodeEntities("&#1114112;"), "超 0x10FFFF 原样保留");
        assertEquals("&#999999999999999999;",
                HtmlText.decodeEntities("&#999999999999999999;"), "位数超上限原样保留（与 Go 同）");
        assertEquals("a&", HtmlText.decodeEntities("a&"), "孤立 & 原样保留");
        assertEquals("&unknown;", HtmlText.decodeEntities("&unknown;"), "未知实体原样保留");
    }

    @Test
    @DisplayName("实体：代理区码点不产出孤立代理（GO-DIFF：Go 会产出 U+FFFD）")
    void surrogateCodePointsKeepTheEntity() {
        // Go 实测：string(rune(0xD800)) → "\uFFFD"（把非法码点悄悄改成替换符）。
        // 这里保留原文：既是诚实表示，也避免输出非法 UTF-16。
        assertEquals("&#xD800;", HtmlText.decodeEntities("&#xD800;"));
        assertEquals("&#55296;", HtmlText.decodeEntities("&#55296;"));
        assertEquals("&#xDFFF;", HtmlText.decodeEntities("&#xDFFF;"));
    }

    @Test
    @DisplayName("实体：命名先解、数值后解（两趟，与 Go 同）")
    void decodesInTwoPasses() {
        // 两趟才可见：一趟扫描会把 &amp; 解成 & 然后从 #65; 继续抄（留下字面 "&#65;"）
        assertEquals("A", HtmlText.decodeEntities("&amp;#65;"));
        assertEquals("'", HtmlText.decodeEntities("&amp;#39;"));
        assertEquals("A", HtmlText.decodeEntities("&amp;#x41;"),
                "GO-DIFF：Go 因十六进制 bug 停在中途，Java 按意图解到底");
    }
}