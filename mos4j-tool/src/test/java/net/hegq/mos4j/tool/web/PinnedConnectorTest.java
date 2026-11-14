package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.ToolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺省抓取后端的防线零件（对齐 Go {@code tool/web/fetch.go} 的同名函数）。
 *
 * <p>放在 {@code net.hegq.mos4j.tool.web} 包里，是为了测得着包内可见的
 * {@code classifyContentType} / {@code sameOrigin} / {@code validateUrl} /
 * {@code resolve}——Go 侧这些函数也都是不导出的，公开面保持一致。
 */
class PinnedConnectorTest {

    @Test
    @DisplayName("Content-Type 归类：html / text / json+xml 通过")
    void contentTypeAcceptsTextKinds() {
        assertEquals("html", PinnedConnector.classifyContentType("text/html; charset=utf-8"));
        assertEquals("html", PinnedConnector.classifyContentType("application/xhtml+xml"));
        assertEquals("text", PinnedConnector.classifyContentType("text/plain"));
        assertEquals("text", PinnedConnector.classifyContentType("text/markdown; charset=UTF-8"));
        assertEquals("text", PinnedConnector.classifyContentType("application/json"));
        assertEquals("text", PinnedConnector.classifyContentType("application/xml"));
        assertEquals("text", PinnedConnector.classifyContentType("application/vnd.api+json"));
        assertEquals("text", PinnedConnector.classifyContentType("image/svg+xml"));
    }

    @Test
    @DisplayName("Content-Type 归类：缺失与二进制响亮失败")
    void contentTypeRejectsMissingAndBinary() {
        ToolException empty = assertThrows(ToolException.class,
                () -> PinnedConnector.classifyContentType(""));
        assertTrue(empty.getMessage().contains("没有 Content-Type"), empty.getMessage());
        ToolException nullType = assertThrows(ToolException.class,
                () -> PinnedConnector.classifyContentType(null));
        assertTrue(nullType.getMessage().contains("没有 Content-Type"), nullType.getMessage());

        for (String binary : new String[]{
                "application/octet-stream", "image/png", "application/pdf"}) {
            ToolException e = assertThrows(ToolException.class,
                    () -> PinnedConnector.classifyContentType(binary));
            assertTrue(e.getMessage().contains("不支持的内容类型"), e.getMessage());
        }
    }

    @Test
    @DisplayName("同源判定：协议 + 主机 + 有效端口")
    void sameOriginSemantics() {
        assertTrue(PinnedConnector.sameOrigin(
                URI.create("https://a.example/x"), URI.create("https://a.example:443/y")),
                "443 是 https 的默认端口");
        assertTrue(PinnedConnector.sameOrigin(
                URI.create("http://a.example/x"), URI.create("http://A.EXAMPLE:80/y")),
                "主机名大小写不敏感");

        assertFalse(PinnedConnector.sameOrigin(
                URI.create("https://a.example/x"), URI.create("http://a.example/y")),
                "协议不同即跨源");
        assertFalse(PinnedConnector.sameOrigin(
                URI.create("https://a.example/x"), URI.create("https://b.example/y")),
                "主机不同即跨源");
        assertFalse(PinnedConnector.sameOrigin(
                URI.create("https://a.example/x"), URI.create("https://a.example:8443/y")),
                "端口不同即跨源");
        assertFalse(PinnedConnector.sameOrigin(null, URI.create("https://a.example/y")));
    }

    @Test
    @DisplayName("URL 校验：空 / 超长 / 非 http(s) / 内嵌凭据 / 缺主机")
    void urlValidation() {
        assertTrue(PinnedConnector.validateUrl("  https://a.example/x  ").toString()
                .endsWith("/x"), "两端空白被 trim");

        for (String blank : new String[]{"", "   ", null}) {
            ToolException e = assertThrows(ToolException.class,
                    () -> PinnedConnector.validateUrl(blank));
            assertTrue(e.getMessage().contains("url 不能为空"), e.getMessage());
        }

        String overlong = "https://a.example/" + "a".repeat(PinnedConnector.MAX_URL_LENGTH);
        ToolException tooLong = assertThrows(ToolException.class,
                () -> PinnedConnector.validateUrl(overlong));
        assertTrue(tooLong.getMessage().contains("最大长度"), tooLong.getMessage());

        ToolException scheme = assertThrows(ToolException.class,
                () -> PinnedConnector.validateUrl("ftp://a.example/x"));
        assertTrue(scheme.getMessage().contains("只支持 http/https"), scheme.getMessage());

        ToolException credentials = assertThrows(ToolException.class,
                () -> PinnedConnector.validateUrl("https://user:pass@a.example/x"));
        assertTrue(credentials.getMessage().contains("内嵌凭据"), credentials.getMessage());

        ToolException noHost = assertThrows(ToolException.class,
                () -> PinnedConnector.validateUrl("http:///path"));
        assertTrue(noHost.getMessage().contains("主机名"), noHost.getMessage());
    }

    @Test
    @DisplayName("解析 + 公网校验：回环 / 内网 / 链路本地 / IPv4 映射一律拒绝")
    void resolveRejectsNonPublicAddresses() {
        for (String host : new String[]{
                "localhost", "127.0.0.1", "10.0.0.1", "169.254.169.254",
                "::1", "::ffff:127.0.0.1"}) {
            ToolException e = assertThrows(ToolException.class,
                    () -> PinnedConnector.resolve(host, null), host + " 应被拒");
            assertTrue(e.getMessage().contains("不是公网地址")
                            || e.getMessage().contains("解析主机"),
                    host + " → " + e.getMessage());
        }

        ToolException bad = assertThrows(ToolException.class,
                () -> PinnedConnector.resolve("no-such-host.invalid", null));
        assertTrue(bad.getMessage().contains("解析主机"), bad.getMessage());
    }

    @Test
    @DisplayName("公网判定：私有段 / 回环 / 链路本地 / CGNAT / ULA 都不是公网")
    void publicAddressClassification() throws Exception {
        for (String blocked : new String[]{
                "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1",
                "198.18.0.1", "192.0.0.1", "::1", "fc00::1", "fe80::1"}) {
            assertFalse(PublicAddresses.isPublic(InetAddress.getByName(blocked)),
                    blocked + " 不该被判为公网");
        }
        for (String allowed : new String[]{"8.8.8.8", "1.1.1.1", "2606:4700::1111"}) {
            assertTrue(PublicAddresses.isPublic(InetAddress.getByName(allowed)),
                    allowed + " 该被判为公网");
        }
        assertFalse(PublicAddresses.allPublic(null));
        assertFalse(PublicAddresses.allPublic(java.util.List.of()));
    }

    @Test
    @DisplayName("上限归一化：非正值取缺省（对齐 Go limits()）")
    void limitsNormalize() {
        FetchLimits zero = new FetchLimits(0, 0, null, 0, "").normalized();

        assertEquals(5L << 20, zero.maxResponseBytes());
        assertEquals(100_000, zero.maxContentChars());
        assertEquals(Duration.ofSeconds(30), zero.timeout());
        assertEquals(5, zero.maxRedirects());
        assertEquals(FetchLimits.DEFAULT_USER_AGENT, zero.userAgent());

        FetchLimits negative = new FetchLimits(-1, -1, Duration.ofSeconds(-1), -1, null)
                .normalized();
        assertEquals(5L << 20, negative.maxResponseBytes());
        assertEquals(Duration.ofSeconds(30), negative.timeout());

        FetchLimits custom = new FetchLimits(1024, 10, Duration.ofSeconds(2), 1, "ua")
                .normalized();
        assertEquals(1024, custom.maxResponseBytes());
        assertEquals(10, custom.maxContentChars());
        assertEquals(Duration.ofSeconds(2), custom.timeout());
        assertEquals(1, custom.maxRedirects());
        assertEquals("ua", custom.userAgent());
    }

    @Test
    @DisplayName("User-Agent 是显式产品标识，不伪装浏览器（对齐 Go 格式）")
    void userAgentIsHonest() {
        assertTrue(FetchLimits.DEFAULT_USER_AGENT.startsWith("mos-web-fetch/1.0 (+https://"),
                FetchLimits.DEFAULT_USER_AGENT);
        assertFalse(FetchLimits.DEFAULT_USER_AGENT.contains("Mozilla"),
                "不伪装浏览器");
    }
}