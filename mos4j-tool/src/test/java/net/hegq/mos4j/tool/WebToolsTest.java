package net.hegq.mos4j.tool;

import net.hegq.mos4j.tool.web.FetchLimits;
import net.hegq.mos4j.tool.web.FetchResult;
import net.hegq.mos4j.tool.web.Fetcher;
import net.hegq.mos4j.tool.web.PinnedConnector;
import net.hegq.mos4j.tool.web.SearchProvider;
import net.hegq.mos4j.tool.web.WebFetchTool;
import net.hegq.mos4j.tool.web.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.8 + T5.9：web_search / web_fetch。
 *
 * <p>web_search 的假后端是 lambda（{@link SearchProvider} 单方法）。
 * web_fetch 的后端是 {@link Fetcher}（{@code name()} + {@code fetch()} 两个方法，
 * 不是函数接口），假后端用匿名类。
 *
 * <p>SSRF 防线在抓取后端（{@link PinnedConnector}）里，不在工具层——所以
 * 「回环 / 内网 / 链路本地 / IPv4 映射」这些用例<b>用缺省后端</b>跑，
 * 而「注入自定义后端」的用例必须能绕开公网校验（否则换实现就没意义了）。
 */
class WebToolsTest {

    // ---- web_search ----

    @Test
    @DisplayName("web_search：provider 结果归一化输出，带不可信标注")
    void searchRendersProviderResults() throws Exception {
        SearchProvider provider = (queries, limit) -> SearchProvider.Outcome.of(List.of(
                new SearchProvider.SearchResult("标题一", "https://a.example/1", "摘要一"),
                new SearchProvider.SearchResult("标题二", "https://b.example/2", null)));
        WebSearchTool tool = new WebSearchTool(provider);

        String out = contentOf(tool.execute("{\"query\":\"测试\"}"));

        assertTrue(out.contains("外部内容不可信"), out);
        assertTrue(out.contains("标题一"), out);
        assertTrue(out.contains("https://a.example/1"), out);
        assertFalse(out.contains("null"), out);
    }

    @Test
    @DisplayName("web_search：回执是结构化 map（queries/summary/note/sources/count/truncated/content）")
    @SuppressWarnings("unchecked")
    void searchReturnsStructuredReceipt() throws Exception {
        SearchProvider provider = (queries, limit) -> new SearchProvider.Outcome(
                "生成式摘要", "", List.of(
                new SearchProvider.SearchResult("标题一", "https://a.example/1", "摘要一")), false);
        WebSearchTool tool = new WebSearchTool(provider);

        Map<String, Object> out = (Map<String, Object>) tool.execute("{\"query\":\"测试\"}");

        assertEquals(List.of("测试"), out.get("queries"));
        assertEquals("生成式摘要", out.get("summary"));
        assertEquals("", out.get("note"));
        assertEquals(1, out.get("count"));
        assertEquals(false, out.get("truncated"));
        assertTrue(out.get("content").toString().contains("生成式摘要"), out.toString());
        assertTrue(out.containsKey("sources"));
    }

    @Test
    @DisplayName("web_search：queries[] 多条并行、URL 去重合并（SearchProvider.Single 装配）")
    void searchMergesMultipleQueriesInParallel() throws Exception {
        WebSearchTool tool = WebSearchTool.of((query, limit) -> switch (query) {
            case "甲" -> List.of(new SearchProvider.SearchResult("A", "https://same/1", null));
            case "乙" -> List.of(
                    new SearchProvider.SearchResult("A", "https://same/1", null),
                    new SearchProvider.SearchResult("B", "https://other/2", null));
            default -> List.of();
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.execute(
                "{\"queries\":[\"甲\",\"乙\"]}");

        assertEquals(List.of("甲", "乙"), out.get("queries"));
        assertEquals(2, out.get("count"), "同 URL 去重后只剩 2 条：" + out);
    }

    @Test
    @DisplayName("web_search：query 单条容错形状照样能用")
    void searchAcceptsSingleQueryShape() throws Exception {
        WebSearchTool tool = new WebSearchTool((queries, limit) -> {
            assertEquals(List.of("单条"), queries, "query 应被归一成 1 条查询");
            return SearchProvider.Outcome.empty();
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.execute("{\"query\":\"单条\"}");

        assertEquals(List.of("单条"), out.get("queries"));
        assertEquals(0, out.get("count"));
        assertTrue(out.get("content").toString().contains("没有找到结果"),
                out.get("content").toString());
    }

    @Test
    @DisplayName("web_search：queries 为空 / 超过 4 条都报错")
    void searchValidatesQueries() {
        WebSearchTool tool = new WebSearchTool((queries, limit) -> SearchProvider.Outcome.empty());

        ToolException empty = assertThrows(ToolException.class, () -> tool.execute("{}"));
        assertTrue(empty.getMessage().contains("queries 不能为空"), empty.getMessage());

        ToolException tooMany = assertThrows(ToolException.class,
                () -> tool.execute("{\"queries\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"));
        assertTrue(tooMany.getMessage().contains("最多 4 条"), tooMany.getMessage());

        ToolException allBlank = assertThrows(ToolException.class,
                () -> tool.execute("{\"queries\":[\"  \",\"\"]}"));
        assertTrue(allBlank.getMessage().contains("queries 不能为空"), allBlank.getMessage());
    }

    @Test
    @DisplayName("web_search：limit 缺省 8、上限 8")
    void searchClampsLimit() throws Exception {
        int[] seen = new int[1];
        WebSearchTool tool = new WebSearchTool((queries, limit) -> {
            seen[0] = limit;
            return SearchProvider.Outcome.empty();
        });
        tool.execute("{\"query\":\"x\"}");
        assertEquals(8, seen[0], "缺省 limit = 8");

        seen[0] = 0;
        tool.execute("{\"query\":\"x\",\"limit\":99}");
        assertEquals(8, seen[0], "limit 上限 = 8");
    }

    @Test
    @DisplayName("web_search：provider 抛异常 = 工具错误（对齐 Go 的 web_search 失败）")
    void searchFailureIsAToolError() {
        SearchProvider failing = (queries, limit) -> {
            throw new IllegalStateException("HTTP 503");
        };
        WebSearchTool tool = new WebSearchTool(failing);

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"query\":\"测试\"}"));
        assertTrue(e.getMessage().startsWith("web_search 失败"), e.getMessage());
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    @DisplayName("web_search：部分查询失败不算整体失败，失败条数进 note")
    void searchPartialFailureKeepsResults() throws Exception {
        WebSearchTool tool = WebSearchTool.of((query, limit) -> {
            if ("坏".equals(query)) {
                throw new IllegalStateException("HTTP 500");
            }
            return List.of(new SearchProvider.SearchResult("好", "https://ok/1", null));
        });

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tool.execute(
                "{\"queries\":[\"好\",\"坏\"]}");

        assertEquals(1, out.get("count"), out.toString());
        assertTrue(out.get("note").toString().contains("坏"), out.toString());
        assertTrue(out.get("content").toString().contains("以下查询失败"), out.toString());
    }

    @Test
    @DisplayName("web_search：全部查询失败 = 工具错误")
    void searchAllQueriesFailedIsError() {
        WebSearchTool tool = WebSearchTool.of((query, limit) -> {
            throw new IllegalStateException("HTTP 500");
        });

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"queries\":[\"甲\",\"乙\"]}"));
        assertTrue(e.getMessage().contains("全部查询都失败"), e.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static String contentOf(Object receipt) {
        Map<String, Object> map = (Map<String, Object>) receipt;
        return String.valueOf(map.get("content"));
    }

    // ---- web_fetch：SSRF 防线（在抓取后端里）----

    @Test
    @DisplayName("防线一：回环地址被拒（localhost 解析结果 = 127.0.0.1）")
    void loopbackIsRejected() {
        WebFetchTool tool = new WebFetchTool();

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"http://localhost/x\"}"));
        assertTrue(e.getMessage().contains("不是公网地址"), e.getMessage());
    }

    @Test
    @DisplayName("防线一：内网 IP 字面量被拒")
    void privateLiteralIpIsRejected() {
        WebFetchTool tool = new WebFetchTool();

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"http://10.0.0.1/x\"}"));
        assertTrue(e.getMessage().contains("不是公网地址"), e.getMessage());
    }

    @Test
    @DisplayName("防线一：IPv4 映射的 IPv6 回环（::ffff:127.0.0.1 伪装）被拒")
    void ipv4MappedLoopbackIsRejected() {
        WebFetchTool tool = new WebFetchTool();

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"http://[::ffff:127.0.0.1]/x\"}"));
        assertTrue(e.getMessage().contains("不是公网地址"), e.getMessage());
    }

    @Test
    @DisplayName("防线一：链路本地地址（169.254.x，云 metadata 服务）被拒")
    void linkLocalIsRejected() {
        WebFetchTool tool = new WebFetchTool();

        ToolException e = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"));
        assertTrue(e.getMessage().contains("不是公网地址"), e.getMessage());
    }

    @Test
    @DisplayName("防线边界：非 http/https、超长 URL、内嵌凭据都是工具错误")
    void urlRejectionsSurfaceAsToolErrors() {
        WebFetchTool tool = new WebFetchTool();

        ToolException scheme = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"ftp://example.com/x\"}"));
        assertTrue(scheme.getMessage().startsWith("web_fetch 失败"), scheme.getMessage());
        assertTrue(scheme.getMessage().contains("只支持 http/https"), scheme.getMessage());

        String overlong = "https://example.com/" + "a".repeat(PinnedConnector.MAX_URL_LENGTH);
        ToolException tooLong = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"" + overlong + "\"}"));
        assertTrue(tooLong.getMessage().contains("最大长度"), tooLong.getMessage());

        ToolException credentials = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"https://user:pass@example.com/x\"}"));
        assertTrue(credentials.getMessage().contains("内嵌凭据"), credentials.getMessage());
    }

    // ---- web_fetch：Fetcher 注入（GO-DIFF #18）----

    @Test
    @DisplayName("web_fetch：注入自定义 Fetcher 后公网校验不再拦路（换实现 = 换防线）")
    void customFetcherReplacesTheGuards() throws Exception {
        boolean[] called = new boolean[1];
        Fetcher fake = new Fetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public FetchResult fetch(String url, FetchLimits limits) {
                called[0] = true;
                assertEquals(5L << 20, limits.maxResponseBytes(), "上限从工具传给后端");
                // 自定义后端可以抓内网（企业代理 / 测试内网），工具层不该再拦
                return new FetchResult(url, 200, "", "text/plain", "假后端正文", false);
            }
        };

        WebFetchTool tool = new WebFetchTool(fake);

        Map<String, Object> out = receiptOf(tool.execute("{\"url\":\"http://10.0.0.9/internal\"}"));

        assertTrue(called[0], "注入的后端应被调用");
        assertTrue(String.valueOf(out.get("content")).contains("假后端正文"), out.toString());
        assertTrue(String.valueOf(out.get("content")).contains("外部内容不可信"), out.toString());
        assertEquals(200, out.get("status_code"));
    }

    @Test
    @DisplayName("web_fetch：缺 url 与未装配后端都是执行期错误，文案对齐 Go")
    void fetchRequiresFetcherAndUrl() {
        // 后端可以是 null（装配顺序自由），只有真调用时才 fail-closed
        WebFetchTool noBackend = new WebFetchTool((Fetcher) null);
        ToolException missing = assertThrows(ToolException.class, () -> noBackend.execute("{\"url\":\"https://example.com\"}"));
        assertEquals("web_fetch 未装配抓取后端（宿主注入 Fetcher 后才可用）", missing.getMessage());

        WebFetchTool tool = new WebFetchTool(bodyFetcher("x"));
        ToolException e = assertThrows(ToolException.class, () -> tool.execute("{}"));
        assertEquals("url 不能为空", e.getMessage());

        // url 空优先于后端缺失：Go 里 url 检查在前
        ToolException blank = assertThrows(ToolException.class, () -> noBackend.execute("{\"url\":\"   \"}"));
        assertEquals("url 不能为空", blank.getMessage());
    }

    @Test
    @DisplayName("web_fetch：后端抛 IOException / ToolException 都变成工具错误")
    void fetchWrapsBackendFailures() {
        Fetcher ioFailing = new Fetcher() {
            @Override
            public String name() {
                return "io";
            }

            @Override
            public FetchResult fetch(String url, FetchLimits limits) throws java.io.IOException {
                throw new java.io.IOException("web: 抓取失败: 连接超时");
            }
        };
        ToolException io = assertThrows(ToolException.class,
                () -> new WebFetchTool(ioFailing).execute("{\"url\":\"https://example.com/\"}"));
        assertTrue(io.getMessage().startsWith("web_fetch 失败"), io.getMessage());
        assertTrue(io.getMessage().contains("连接超时"), io.getMessage());

        Fetcher semanticFailing = new Fetcher() {
            @Override
            public String name() {
                return "semantic";
            }

            @Override
            public FetchResult fetch(String url, FetchLimits limits) {
                throw new ToolException("web: 不支持的内容类型 \"image/png\"（只取文本类）");
            }
        };
        ToolException semantic = assertThrows(ToolException.class,
                () -> new WebFetchTool(semanticFailing)
                        .execute("{\"url\":\"https://example.com/\"}"));
        assertTrue(semantic.getMessage().contains("不支持的内容类型"), semantic.getMessage());
    }

    // ---- web_fetch：回执形状对齐 Go ----

    @Test
    @DisplayName("web_fetch：回执六个键齐全（url/status_code/title/content_type/truncated/content）")
    void fetchReturnsStructuredReceipt() throws Exception {
        WebFetchTool tool = new WebFetchTool(bodyFetcher(
                "https://x.dev/final", 200, "标题", "text/html; charset=utf-8", "正文", false));

        Map<String, Object> out = receiptOf(tool.execute("{\"url\":\"https://x.dev/p\"}"));

        assertEquals("https://x.dev/final", out.get("url"), "url 是后端给的最终地址");
        assertEquals(200, out.get("status_code"));
        assertEquals("标题", out.get("title"));
        assertEquals("text/html; charset=utf-8", out.get("content_type"), "Content-Type 原样透传");
        assertEquals(false, out.get("truncated"), "Go 的 map 字面量没有 omitempty：false 也发");
        assertTrue(String.valueOf(out.get("content")).contains("正文"), out.toString());
        assertTrue(String.valueOf(out.get("content")).contains("外部内容不可信"),
                "抓回内容应带不可信标注");
    }

    @Test
    @DisplayName("web_fetch：非 2xx 是结果不是错误（404 带着 status_code 正常返回）")
    void non2xxIsAResultNotAnError() throws Exception {
        WebFetchTool tool = new WebFetchTool(
                bodyFetcher("https://x.dev/missing", 404, "", "text/html", "没有这个页面", false));

        Map<String, Object> out = receiptOf(tool.execute("{\"url\":\"https://x.dev/missing\"}"));

        assertEquals(404, out.get("status_code"), "404 是资源状态，不是抓取失败");
        assertTrue(String.valueOf(out.get("content")).contains("没有这个页面"), out.toString());
    }

    @Test
    @DisplayName("web_fetch：空 title / content_type 发空串（不省略键）")
    void fetchKeepsEmptyFields() throws Exception {
        WebFetchTool tool = new WebFetchTool(
                bodyFetcher("https://x.dev/p", 200, "", "", "纯文本", false));

        Map<String, Object> out = receiptOf(tool.execute("{\"url\":\"https://x.dev/p\"}"));

        assertTrue(out.containsKey("title"), "键在，值是空串");
        assertEquals("", out.get("title"));
        assertTrue(out.containsKey("content_type"));
        assertEquals("", out.get("content_type"));
    }

    @Test
    @DisplayName("web_fetch：正文超字符上限 → 截断并标注，truncated=true")
    void fetchTruncatesContentByChars() throws Exception {
        WebFetchTool tool = new WebFetchTool(
                bodyFetcher("https://x.dev/p", 200, "", "text/plain", "字".repeat(500), false),
                FetchLimits.defaults(), 100);

        Map<String, Object> out = receiptOf(tool.execute("{\"url\":\"https://x.dev/p\"}"));

        assertEquals(true, out.get("truncated"), "超长时 truncated 应为 true");
        String content = String.valueOf(out.get("content"));
        assertTrue(content.contains("已截断"), content);
        assertTrue(content.contains("完整上限 100 字符"), content);
        assertTrue(content.contains("字".repeat(100)), "保留前 100 个字符");
        assertFalse(content.contains("字".repeat(101)), "第 101 个字符不该出现");
    }

    @Test
    @DisplayName("web_fetch：截断按码点算（emoji 不被劈成半个）")
    void fetchTruncatesByCodePoint() throws Exception {
        WebFetchTool tool = new WebFetchTool(
                bodyFetcher("https://x.dev/p", 200, "", "text/plain", "😀".repeat(10), false),
                FetchLimits.defaults(), 5);

        String content = String.valueOf(
                receiptOf(tool.execute("{\"url\":\"https://x.dev/p\"}")).get("content"));

        assertTrue(content.contains("😀".repeat(5)), content);
        assertFalse(content.contains("😀".repeat(6)), content);
    }

    /** 固定正文的假后端（{@link Fetcher} 有两个抽象方法，不能用 lambda）。 */
    private static Fetcher bodyFetcher(String content) {
        return bodyFetcher("https://x.dev/p", 200, "", "text/plain", content, false);
    }

    /** 可指定全部回执字段的假后端。 */
    private static Fetcher bodyFetcher(String url, int status, String title, String contentType,
                                       String content, boolean truncated) {
        return new Fetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public FetchResult fetch(String target, FetchLimits limits) {
                return new FetchResult(url, status, title, contentType, content, truncated);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receiptOf(Object receipt) {
        return (Map<String, Object>) receipt;
    }
}