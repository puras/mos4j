package net.hegq.mos4j.tool;

import net.hegq.mos4j.tool.web.Fetcher;
import net.hegq.mos4j.tool.web.PublicAddresses;
import net.hegq.mos4j.tool.web.SearchProvider;
import net.hegq.mos4j.tool.web.WebFetchTool;
import net.hegq.mos4j.tool.web.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5.8 + T5.9：web_search / web_fetch（SSRF 防线）。
 *
 * <p>web_fetch 的传输层走真实 DNS 与网络——SSRF 防线的绕过测试
 * 针对「地址校验 / 同源重定向判定」这些<b>纯逻辑</b>，
 * 用 localhost / 内网名做真实 DNS 场景（解析结果必然是回环地址）。
 *
 * <p>抓取后端是 {@link Fetcher} 接口：可注入假后端验证「宿主自备 Fetcher」
 * 这条注入路径确实通（GO-DIFF #18）。
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

    // ---- web_fetch：SSRF 防线 ----

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
    @DisplayName("防线边界：非 http/https 与缺主机直接报错")
    void schemeAndHostChecks() {
        WebFetchTool tool = new WebFetchTool();

        ToolException scheme = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"ftp://example.com/x\"}"));
        assertTrue(scheme.getMessage().contains("只支持 http/https"), scheme.getMessage());

        ToolException host = assertThrows(ToolException.class,
                () -> tool.execute("{\"url\":\"http:///nohost\"}"));
        assertTrue(host.getMessage().contains("主机名"), host.getMessage());
    }

    // ---- web_fetch：Fetcher 注入（GO-DIFF #18）----

    @Test
    @DisplayName("web_fetch：宿主自备 Fetcher 可注入并拿到请求参数")
    void customFetcherIsInjectable() throws Exception {
        boolean[] called = new boolean[1];
        Fetcher fake = new Fetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public WebFetchTool.FetchResult fetch(WebFetchTool.FetchRequest request) {
                called[0] = true;
                assertEquals(512 * 1024, request.maxBytes(), "上限从工具传给后端");
                return new WebFetchTool.FetchResult.Body("假后端正文", "text/plain", false);
            }
        };
        WebFetchTool tool = new WebFetchTool(fake);

        try {
            String out = (String) tool.execute("{\"url\":\"https://example.com/a\"}");
            assertTrue(called[0], "注入的后端应被调用");
            assertTrue(out.contains("假后端正文"), out);
            assertTrue(out.contains("外部内容不可信"), out);
        } catch (ToolException e) {
            // 离线沙箱里 DNS 不可用时，公网地址校验仍在生效——注入路径不强求连通
            assertTrue(e.getMessage().contains("解析失败") || e.getMessage().contains("公网地址"),
                    e.getMessage());
        }
    }

    @Test
    @DisplayName("web_fetch：Fetcher 必填；缺 url 报错")
    void fetchRequiresFetcherAndUrl() {
        assertThrows(IllegalArgumentException.class, () -> new WebFetchTool((Fetcher) null));

        WebFetchTool tool = new WebFetchTool(bodyFetcher("x"));
        ToolException e = assertThrows(ToolException.class, () -> tool.execute("{}"));
        assertTrue(e.getMessage().contains("url 不能为空"), e.getMessage());
    }

    /** 固定正文的假后端（{@link Fetcher} 有两个抽象方法，不能用 lambda）。 */
    private static Fetcher bodyFetcher(String text) {
        return new Fetcher() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public WebFetchTool.FetchResult fetch(WebFetchTool.FetchRequest request) {
                return new WebFetchTool.FetchResult.Body(text, "text/plain", false);
            }
        };
    }

    // ---- PublicAddresses 的纯逻辑边界 ----

    @Test
    @DisplayName("公网判定：公网 IP 通过；内网 / CGNAT / ULA 拒绝")
    void publicAddressBoundaries() throws Exception {
        assertTrue(PublicAddresses.allPublic(List.of(
                InetAddress.getByName("93.184.216.34"))));
        assertTrue(PublicAddresses.allPublic(List.of(
                InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946"))));
        for (String bad : new String[]{
                "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.1",
                "224.0.0.1", "198.18.0.1", "::1", "fe80::1", "fd00::1"}) {
            assertFalse(PublicAddresses.allPublic(List.of(InetAddress.getByName(bad))),
                    bad + " 应判为非公网");
        }
        assertFalse(PublicAddresses.allPublic(List.of()), "空地址列表 = 拒绝");
    }

    // ---- Guideline（T5.10）----

    @Test
    @DisplayName("Guideline：聚合渲染稳定，空规范渲染为空串")
    void guidelinesRenderStably() {
        Guideline g1 = () -> "read 之后才能 write/edit";
        Guideline g2 = () -> "先 glob 再 grep，缩小搜索范围";

        String out = Guidelines.render(List.of(g1, g2));
        assertEquals("- read 之后才能 write/edit\n- 先 glob 再 grep，缩小搜索范围", out);
        assertEquals(out, Guidelines.render(List.of(g1, g2)), "渲染逐字节一致");
        assertEquals("", Guidelines.render(List.of()), "空规范 = 空串");
    }
}