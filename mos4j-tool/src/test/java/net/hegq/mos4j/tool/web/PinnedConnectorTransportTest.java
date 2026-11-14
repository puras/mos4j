package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.ToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输层端到端：真 socket、真 HTTP/1.1 报文（对齐 Go {@code fetch_test.go}）。
 *
 * <p><b>为什么必须真连</b>：这一层是手写的 HTTP/1.1 解析（状态行、头、chunked、
 * gzip、截断）。用 mock 去断言「解析器的解析结果」等于自己跟自己对账；只有把
 * 字节写进 socket、把回执读回来，才能证明它认得真服务器发出的东西。
 *
 * <p><b>怎么连本地服务又保住 SSRF 防线不变</b>：注入两个点（对齐 Go 的
 * {@code Resolver} / {@code Dial}）——解析器谎报一个公网地址，拨号器指向本地监听。
 * 生产不这么干（缺省就是系统解析 + 钉住拨号）；这里变的是「连到哪」，
 * <b>不是</b>「什么地址被允许」：{@code resolveRejectsPrivateAddresses} 证明
 * 注入解析器同样过公网校验。
 */
class PinnedConnectorTransportTest {

    /** 本地假服务器（一次性响应，避免测试之间互相污染）。 */
    private final List<ServerSocket> servers = new ArrayList<>();

    private final List<String> receivedRequests = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (ServerSocket server : servers) {
            server.close();
        }
    }

    // ── 基础回执形状 ──

    @Test
    @DisplayName("200 + text/plain：状态码、内容、最终 URL 都对")
    void fetchesPlainText() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: 9\r\n"
                + "\r\n"
                + "到达终点");

        FetchResult result = connectorFor(port).fetch("http://page.example/start", null);

        assertEquals(200, result.statusCode());
        assertEquals("到达终点", result.content());
        assertEquals("text/plain; charset=utf-8", result.contentType());
        assertEquals("", result.title(), "纯文本没有标题");
        assertFalse(result.truncated());
        assertEquals("http://page.example/start", result.url());
    }

    @Test
    @DisplayName("出站报文：GET + Host + User-Agent + Accept + Connection: close")
    void sendsRequestHeaders() throws Exception {
        String port = serve(ok("text/plain"));

        connectorFor(port).fetch("http://page.example/a/b?x=1", null);

        String request = receivedRequests.get(0);
        assertTrue(request.startsWith("GET /a/b?x=1 HTTP/1.1\r\n"), request);
        // Host 头用 URL 的逻辑端口，不是拨号端口（Host 描述的是「找谁」，不是「怎么连」）
        assertTrue(request.contains("\r\nHost: page.example:80\r\n"), request);
        assertTrue(request.contains("\r\nUser-Agent: " + FetchLimits.DEFAULT_USER_AGENT + "\r\n"),
                request);
        assertTrue(request.contains("\r\nAccept: " + PinnedConnector.ACCEPT + "\r\n"), request);
        assertTrue(request.contains("\r\nConnection: close\r\n"), request);
        assertFalse(request.contains("Mozilla"), "不伪装浏览器: " + request);
    }

    @Test
    @DisplayName("出站报文：URL 显式端口写进 Host（IPv6 加方括号）")
    void sendsHostHeaderWithExplicitPort() throws Exception {
        String port = serve(ok("text/plain"));

        connectorFor(port).fetch("http://page.example:8080/x", null);

        assertTrue(receivedRequests.get(0).contains("\r\nHost: page.example:8080\r\n"),
                receivedRequests.get(0));
    }

    @Test
    @DisplayName("非 2xx 是结果不是错误（404 正常返回）")
    void non2xxIsAResultNotAnError() throws Exception {
        String port = serve("HTTP/1.1 404 Not Found\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 6\r\n"
                + "\r\n"
                + "没找到");

        FetchResult result = connectorFor(port).fetch("http://page.example/missing", null);

        assertEquals(404, result.statusCode());
        assertEquals("没找到", result.content());
    }

    // ── 正文解码 ──

    @Test
    @DisplayName("chunked 传输解码（chunk 长度是字节数，不是字符数）")
    void decodesChunkedBody() throws Exception {
        // 第一段 = 9 字节，尾巴 = 6 字节
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "9\r\n第一段\r\n"
                + "6\r\n尾巴\r\n"
                + "0\r\n"
                + "\r\n");

        FetchResult result = connectorFor(port).fetch("http://page.example/c", null);

        assertEquals("第一段尾巴", result.content());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("chunked 带 trailer 与 chunk 扩展（真服务器会发）")
    void decodesChunkedWithTrailerAndExtensions() throws Exception {
        // 正文 = 6 字节
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "6;ext=1\r\n正文\r\n"
                + "0\r\n"
                + "X-Trailer: t\r\n"
                + "\r\n");

        assertEquals("正文", connectorFor(port).fetch("http://page.example/c", null).content());
    }

    @Test
    @DisplayName("chunked 长度非法：响亮失败")
    void rejectsInvalidChunkLength() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "zz\r\n正文\r\n"
                + "0\r\n\r\n");

        IOException e = assertThrows(IOException.class,
                () -> connectorFor(port).fetch("http://page.example/c", null));

        assertTrue(e.getMessage().contains("chunked 长度无效"), e.getMessage());
    }

    @Test
    @DisplayName("gzip 压缩的正文自动解压")
    void decodesGzipBody() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("解压后的正文".getBytes(StandardCharsets.UTF_8));
        }
        byte[] body = compressed.toByteArray();

        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n";
        String port = serveBytes(head.getBytes(StandardCharsets.ISO_8859_1), body);

        assertEquals("解压后的正文",
                connectorFor(port).fetch("http://page.example/g", null).content());
    }

    @Test
    @DisplayName("头名大小写不敏感（Content-Type / CONTENT-TYPE 都认）")
    void headerNamesAreCaseInsensitive() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "CONTENT-TYPE: text/plain\r\n"
                + "Content-Length: 2\r\n"
                + "\r\n"
                + "ok");

        FetchResult result = connectorFor(port).fetch("http://page.example/h", null);

        assertEquals("text/plain", result.contentType());
        assertEquals("ok", result.content());
    }

    // ── 体积与字符上限 ──

    @Test
    @DisplayName("字节上限：超额截断并标 truncated")
    void capsResponseBytes() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 5000\r\n"
                + "\r\n"
                + "x".repeat(5000));

        FetchResult result = connectorFor(port)
                .fetch("http://page.example/big", new FetchLimits(100, 100_000, null, 0, ""));

        assertTrue(result.truncated(), "超量时 truncated 应为 true");
        assertEquals(100, result.content().length());
    }

    @Test
    @DisplayName("字符上限：HTML 转文本后再按码点截断")
    void capsContentCharsAfterConversion() throws Exception {
        String body = "<html><body><p>" + "字".repeat(200) + "</p></body></html>";
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n"
                + body);

        FetchResult result = connectorFor(port)
                .fetch("http://page.example/long", new FetchLimits(0, 50, null, 0, ""));

        assertEquals(50, result.content().codePointCount(0, result.content().length()));
        assertTrue(result.truncated());
    }

    @Test
    @DisplayName("字符上限不劈代理对（emoji 保持完整）")
    void capsContentCharsByCodePoint() throws Exception {
        String body = "😀".repeat(10);
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n"
                + body);

        FetchResult result = connectorFor(port)
                .fetch("http://page.example/emoji", new FetchLimits(0, 3, null, 0, ""));

        assertEquals("😀😀😀", result.content());
    }

    // ── HTML 清洗 ──

    @Test
    @DisplayName("HTML：取标题、去脚本、解实体、转纯文本")
    void convertsHtml() throws Exception {
        String body = "<html><head><title>页面标题</title><style>b{}</style></head>"
                + "<body><script>alert(1)</script>"
                + "<p>第一段</p><p>第二段 &amp; 更多</p></body></html>";
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "\r\n"
                + body);

        FetchResult result = connectorFor(port).fetch("http://page.example/p", null);

        assertEquals("页面标题", result.title());
        assertFalse(result.content().contains("alert(1)"), "脚本应被去掉: " + result.content());
        assertTrue(result.content().contains("第一段"), result.content());
        assertTrue(result.content().contains("& 更多"), result.content());
        assertEquals("text/html; charset=utf-8", result.contentType(), "分类用 mime，回执保留原头");
    }

    // ── 内容类型守卫 ──

    @Test
    @DisplayName("二进制内容类型：响亮失败（不把二进制塞进模型上下文）")
    void rejectsBinaryContentType() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: 3\r\n"
                + "\r\n"
                + "abc");

        ToolException e = assertThrows(ToolException.class,
                () -> connectorFor(port).fetch("http://page.example/bin", null));

        assertTrue(e.getMessage().contains("不支持的内容类型"), e.getMessage());
    }

    @Test
    @DisplayName("缺 Content-Type：响亮失败（不猜是文本）")
    void rejectsMissingContentType() throws Exception {
        String port = serve("HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "\r\n"
                + "ok");

        ToolException e = assertThrows(ToolException.class,
                () -> connectorFor(port).fetch("http://page.example/none", null));

        assertTrue(e.getMessage().contains("没有 Content-Type"), e.getMessage());
    }

    // ── 重定向 ──

    @Test
    @DisplayName("同源重定向：跟随并返回最终 URL")
    void followsSameOriginRedirect() throws Exception {
        String port = serve(
                "HTTP/1.1 302 Found\r\n"
                        + "Location: /final\r\n"
                        + "Content-Length: 0\r\n"
                        + "\r\n",
                ok("text/plain"));

        FetchResult result = connectorFor(port).fetch("http://page.example/start", null);

        assertEquals("http://page.example/final", result.url());
        assertEquals("内容", result.content());
    }

    @Test
    @DisplayName("跨源重定向：拒绝并提示重新调用")
    void refusesCrossOriginRedirect() throws Exception {
        String port = serve("HTTP/1.1 302 Found\r\n"
                + "Location: http://other.example/target\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n");

        ToolException e = assertThrows(ToolException.class,
                () -> connectorFor(port).fetch("http://page.example/start", null));

        assertTrue(e.getMessage().contains("跨源"), e.getMessage());
        assertTrue(e.getMessage().contains("重新调用"), e.getMessage());
        assertEquals(1, receivedRequests.size(), "跨源不该真的发出第二个请求");
    }

    @Test
    @DisplayName("重定向跳数超上限：报错（不无限跟）")
    void capsRedirectHops() throws Exception {
        String response = "HTTP/1.1 302 Found\r\nLocation: /loop\r\nContent-Length: 0\r\n\r\n";
        String port = serve(response, response, response, response);

        ToolException e = assertThrows(ToolException.class,
                () -> connectorFor(port)
                        .fetch("http://page.example/loop", new FetchLimits(0, 0, null, 2, "")));

        assertTrue(e.getMessage().contains("跳数超过上限 2"), e.getMessage());
    }

    @Test
    @DisplayName("3xx 但没有 Location：报错而不是当正文返回")
    void redirectWithoutLocationFails() throws Exception {
        String port = serve("HTTP/1.1 301 Moved\r\nContent-Length: 0\r\n\r\n");

        ToolException e = assertThrows(ToolException.class,
                () -> connectorFor(port).fetch("http://page.example/x", null));

        assertTrue(e.getMessage().contains("没有 Location"), e.getMessage());
    }

    // ── 防线不因注入而削弱 ──

    @Test
    @DisplayName("注入解析器同样过公网校验（防线不因可注入而削弱）")
    void injectedResolverStillEnforcesPublicAddresses() {
        PinnedConnector connector = new PinnedConnector(
                host -> List.of(InetAddress.getLoopbackAddress()),
                (host, port, timeout) -> {
                    throw new AssertionError("不该拨号：地址校验应先失败");
                });

        ToolException e = assertThrows(ToolException.class,
                () -> connector.fetch("http://page.example/", null));

        assertTrue(e.getMessage().contains("不是公网地址"), e.getMessage());
    }

    @Test
    @DisplayName("解析失败 / 空地址集：报错而不是连空")
    void resolverFailuresSurface() {
        PinnedConnector failing = new PinnedConnector(
                host -> {
                    throw new IOException("DNS 挂了");
                }, null);
        ToolException dns = assertThrows(ToolException.class,
                () -> failing.fetch("http://page.example/", null));
        assertTrue(dns.getMessage().contains("解析主机"), dns.getMessage());
        assertTrue(dns.getMessage().contains("DNS 挂了"), dns.getMessage());

        PinnedConnector empty = new PinnedConnector(host -> List.of(), null);
        ToolException none = assertThrows(ToolException.class,
                () -> empty.fetch("http://page.example/", null));
        assertTrue(none.getMessage().contains("没有可用地址"), none.getMessage());
    }

    @Test
    @DisplayName("非 HTTP 响应：响亮失败（连错端口的典型表现）")
    void nonHttpResponseFails() throws Exception {
        String port = serve("SSH-2.0-OpenSSH\r\n\r\n");

        IOException e = assertThrows(IOException.class,
                () -> connectorFor(port).fetch("http://page.example/x", null));

        assertTrue(e.getMessage().contains("不是 HTTP"), e.getMessage());
    }

    // ── 辅助：本地假服务器 ──

    /**
     * 造一个「能连本地、但地址判定为公网」的后端（对齐 Go {@code testFetcher}）：
     * 解析器谎报公网地址，拨号器指向本地监听。
     */
    private PinnedConnector connectorFor(String port) {
        return new PinnedConnector(
                host -> List.of(InetAddress.getByName("93.184.216.34")),
                (host, ignored, timeout) -> new Socket("127.0.0.1", Integer.parseInt(port)));
    }

    /** 起一个只听一条请求的假服务器，回一段报文。 */
    private String serve(String response) throws IOException {
        return serveBytes(response.getBytes(StandardCharsets.UTF_8), new byte[0]);
    }

    /** 起假服务器，按顺序回多段报文（测试重定向链）。 */
    private String serve(String... responses) throws IOException {
        ServerSocket server = listen();
        Thread.ofVirtual().start(() -> {
            try {
                for (String response : responses) {
                    if (!respond(server, response.getBytes(StandardCharsets.UTF_8), new byte[0])) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                // 测试结束时 server 被关掉是正常现象
            }
        });
        return String.valueOf(server.getLocalPort());
    }

    /** 起假服务器，回「文本头 + 二进制正文」（gzip 用）。 */
    private String serveBytes(byte[] head, byte[] body) throws IOException {
        ServerSocket server = listen();
        Thread.ofVirtual().start(() -> {
            try {
                respond(server, head, body);
            } catch (IOException ignored) {
                // 同上
            }
        });
        return String.valueOf(server.getLocalPort());
    }

    private ServerSocket listen() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        servers.add(server);
        return server;
    }

    /** 接受一条连接、记下请求、写回报文；返回 {@code false} 表示 server 已关。 */
    private boolean respond(ServerSocket server, byte[] head, byte[] body) throws IOException {
        try (Socket socket = server.accept()) {
            receivedRequests.add(readRequest(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            out.write(head);
            out.write(body);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 读到请求头结束（{@code \r\n\r\n}）为止（GET 没有正文）。 */
    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
            if (matched == 4) {
                break;
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    /** 常见的一段 200 文本响应。 */
    private static String ok(String contentType) {
        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: 6\r\n"
                + "\r\n"
                + "内容";
    }
}