package net.hegq.mos4j.tool;

import net.hegq.mos4j.tool.web.PublicAddresses;
import net.hegq.mos4j.tool.web.SearchProvider;
import net.hegq.mos4j.tool.web.WebFetchTool;
import net.hegq.mos4j.tool.web.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

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
 */
class WebToolsTest {

    // ---- web_search ----

    @Test
    @DisplayName("web_search：provider 结果归一化输出，带不可信标注")
    void searchRendersProviderResults() throws Exception {
        SearchProvider provider = (query, limit) -> List.of(
                new SearchProvider.SearchResult("标题一", "https://a.example/1", "摘要一"),
                new SearchProvider.SearchResult("标题二", "https://b.example/2", null));
        WebSearchTool tool = new WebSearchTool(provider);

        String out = (String) tool.execute("{\"query\":\"测试\"}");

        assertTrue(out.contains("外部内容不可信"), out);
        assertTrue(out.contains("标题一"), out);
        assertTrue(out.contains("https://a.example/1"), out);
        assertFalse(out.contains("null"), out);
    }

    @Test
    @DisplayName("web_search：provider 抛异常 = 「搜索失败」结果，不是工具错误")
    void searchFailureIsAResultNotAnError() throws Exception {
        SearchProvider failing = (query, limit) -> {
            throw new IllegalStateException("HTTP 503");
        };
        WebSearchTool tool = new WebSearchTool(failing);

        String out = (String) tool.execute("{\"query\":\"测试\"}");

        assertTrue(out.contains("搜索失败"), out);
        assertTrue(out.contains("503"), out);
    }

    @Test
    @DisplayName("web_search：缺 query 报错；limit 上限 10")
    void searchValidatesParams() {
        WebSearchTool tool = new WebSearchTool((q, l) -> {
            assertEquals(10, l, "limit 钳到 10");
            return List.of();
        });
        assertThrows(ToolException.class, () -> tool.execute("{}"));
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
