package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.ToolException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 缺省抓取后端：带全套 SSRF 防线的 HTTP 客户端（对齐 Go {@code web.HTTPFetcher}）。
 *
 * <p><b>为什么防线在这里而不是工具层</b>：{@link Fetcher} 是可替换的，工具层若也做校验，
 * 宿主自备后端（企业代理 / 测试内网）就会被工具层拦死——「换掉实现 = 换掉防线」就成了一句空话。
 * Go 的 {@code web.Fetch.Execute} 只做「参数校验 + 截断 + 整形」，地址校验、钉住、重定向、
 * 内容归类全在 {@code HTTPFetcher.Fetch} 里；这里保持同样的分层。
 *
 * <p><b>五条防线</b>：
 * <ol>
 *   <li><b>URL 校验</b>：长度上限、只允许 http/https、禁止 URL 内嵌凭据；</li>
 *   <li><b>公网地址校验</b>：解析主机的<b>全部</b>地址，任一非公网即拒绝（见 {@link PublicAddresses}）；</li>
 *   <li><b>地址钉住</b>：直接用 {@link Socket} 连校验过的那批地址，
 *       <b>不做第二次 DNS 解析</b>——否则「检查时公网、连接时内网」就能穿透（DNS rebinding）；</li>
 *   <li><b>只跟同源重定向</b>：协议 + 主机 + 有效端口必须一致，跨源一律拒绝并要求重新调用；</li>
 *   <li><b>体积 / 时间上限</b>：字节上限（读完多一字节判断截断）、字符上限、连接与读超时。</li>
 * </ol>
 *
 * <p><b>为什么自己写 HTTP/1.1 请求</b>：钉住要求「连到哪个地址」由我们控制。
 * {@code HttpURLConnection} 没有拨号钩子——它的 {@code Proxy.Type.DIRECT} 会忽略代理地址、
 * 照常解析主机名，等于假钉住；{@code HttpClient} 同样没有拨号钩子。所以这里用裸
 * {@link Socket} 发一个 GET：够用（只要 GET + 状态码 + 头 + 正文），且钉得住。
 *
 * <p><b>诚实边界</b>（对齐 Go 的注释）：
 * <ul>
 *   <li><b>只解 UTF-8</b>：不认页面声明的非 UTF-8 字符集（不引第三方依赖）——
 *       乱码比报错更糟，但这里也不假装处理了编码；</li>
 *   <li><b>不判内容是否恶意</b>：只管地址与体积的硬边界；「这是资料不是指令」
 *       靠工具层加的 {@code ExternalContentNotice} 标注；</li>
 *   <li>需要代理的宿主<b>自备 {@link Fetcher} 实现</b>（本类的钉住传输用不上代理）。</li>
 * </ul>
 */
public class PinnedConnector implements Fetcher {

    /** 请求头 Accept（对齐 Go）。 */
    static final String ACCEPT = "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5";

    /** URL 长度上限（对齐 Go {@code maxURLLength}）。 */
    public static final int MAX_URL_LENGTH = 2048;

    /** 主机名 → 地址集（{@code null} = 系统解析）。 */
    private final Resolver resolver;

    /**
     * 拨号器（{@code null} = 钉住拨号）。
     *
     * <p><b>给了它就不钉住</b>——调用方自负「连到哪」的责任（对齐 Go {@code HTTPFetcher.Dial}）。
     */
    private final Dialer dialer;

    /** 缺省后端：系统解析 + 钉住拨号。 */
    public PinnedConnector() {
        this(null, null);
    }

    /**
     * 可注入后端（测试 / 自建代理）。
     *
     * <p>只注入 {@code resolver} 时仍然是钉住拨号：地址来源变了，防 DNS rebinding
     * 的性质不变。注入 {@code dialer} 就不再钉住：这个参数存在的理由就是「连到别处」。
     *
     * @param resolver 主机名解析器（{@code null} = 系统解析）
     * @param dialer   拨号器（{@code null} = 钉住拨号）
     */
    public PinnedConnector(Resolver resolver, Dialer dialer) {
        this.resolver = resolver;
        this.dialer = dialer;
    }

    /** 后端名（对齐 Go {@code HTTPFetcher.Name() == "http"}）。 */
    @Override
    public String name() {
        return "http";
    }

    /**
     * 抓一次（含跟随同源重定向）。
     *
     * @param rawUrl 目标地址
     * @param limits 资源上限（{@code null} → 全缺省；非正值逐项取缺省）
     * @return 最终结果（非 2xx 也是结果）
     * @throws IOException   传输层失败
     * @throws ToolException 校验失败 / 跨源重定向 / 非文本内容
     */
    @Override
    public FetchResult fetch(String rawUrl, FetchLimits limits) throws IOException {
        FetchLimits l = limits == null ? FetchLimits.defaults() : limits.normalized();
        URI current = validateUrl(rawUrl);

        // 防线二：解析 + 逐个校验公网（对「解析结果」而不是「URL 文本」）
        String host = current.getHost();
        List<InetAddress> pinned = resolve(host, resolver);

        // 防线三：整条同源重定向链都用同一批已校验地址
        for (int hop = 0; ; hop++) {
            Response response = get(current, pinned, l);            if (response.status() >= 300 && response.status() < 400) {
                String location = response.header("location");
                if (location == null || location.isBlank()) {
                    throw new ToolException("web: " + response.status() + " 重定向但没有 Location");
                }
                URI next;
                try {
                    next = current.resolve(location.trim());
                } catch (RuntimeException e) {
                    throw new ToolException("web: 重定向地址无效: " + location);
                }
                // 防线四：只跟同源（跨源等于把校验过的地址换成没校验的）
                if (!sameOrigin(current, next)) {
                    throw new ToolException("web: 拒绝跨源重定向 " + current + " → " + next
                            + "（请针对新地址重新调用）");
                }
                if (hop + 1 > l.maxRedirects()) {
                    throw new ToolException("web: 重定向跳数超过上限 " + l.maxRedirects());
                }
                current = next;
                continue;
            }
            return finish(current, response, l);
        }
    }

    /** 把响应整理成结果：归类内容类型、HTML 转纯文本、字符上限截断（对齐 Go）。 */
    private FetchResult finish(URI uri, Response response, FetchLimits limits) {
        String contentType = response.header("content-type");
        if (contentType == null) {
            contentType = "";
        }
        String kind = classifyContentType(contentType);

        String content = new String(response.bytes(), StandardCharsets.UTF_8);
        boolean truncated = response.truncated();
        String title = "";
        if ("html".equals(kind)) {
            title = HtmlText.title(content);
            content = HtmlText.toText(content);
        }
        if (content.codePointCount(0, content.length()) > limits.maxContentChars()) {
            content = content.substring(0,
                    content.offsetByCodePoints(0, limits.maxContentChars()));
            truncated = true;
        }
        return new FetchResult(uri.toString(), response.status(), title, contentType,
                content, truncated);
    }

    // ── URL 校验与解析 ──

    /** 校验与网络无关的部分：长度、协议、内嵌凭据（对齐 Go {@code validateURL}）。 */
    static URI validateUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            throw new ToolException("web: url 不能为空");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new ToolException("web: URL 超过最大长度 " + MAX_URL_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new ToolException("web: URL 无效: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ToolException("web: 只支持 http/https，收到 \"" + scheme + "\"");
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
            throw new ToolException("web: URL 里不允许内嵌凭据");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ToolException("web: URL 缺少主机名: \"" + url + "\"");
        }
        return uri;
    }

    /** 解析主机名并校验全部地址都是公网（对齐 Go 的 {@code LookupIP} + {@code isPublicIP} 循环）。 */
    static List<InetAddress> resolve(String host, Resolver resolver) {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            List<InetAddress> found = resolver != null
                    ? resolver.resolve(host)
                    : List.of(InetAddress.getAllByName(host));
            if (found != null) {
                addresses.addAll(found);
            }
        } catch (IOException e) {
            throw new ToolException("web: 解析主机 \"" + host + "\" 失败: " + e.getMessage());
        }
        if (addresses.isEmpty()) {
            throw new ToolException("web: 主机 \"" + host + "\" 没有可用地址");
        }
        for (InetAddress address : addresses) {
            if (!PublicAddresses.isPublic(address)) {
                throw new ToolException("web: 拒绝抓取内网地址 \"" + host + "\"（"
                        + address.getHostAddress() + " 不是公网地址）");
            }
        }
        return addresses;
    }

    /** 同源判定（协议 + 主机 + 有效端口；对齐 Go {@code sameOrigin}）。 */
    static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a.getScheme()).equalsIgnoreCase(String.valueOf(b.getScheme()))
                && String.valueOf(a.getHost()).equalsIgnoreCase(String.valueOf(b.getHost()))
                && portOf(a).equals(portOf(b));
    }

    /** 有效端口（未显式给出时按协议取默认；对齐 Go {@code portOf}）。 */
    static String portOf(URI uri) {
        if (uri.getPort() != -1) {
            return String.valueOf(uri.getPort());
        }
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http" -> "80";
            case "https" -> "443";
            default -> "";
        };
    }

    // ── 传输：裸 socket + 钉住地址 ──

    /** 发一个 GET 并读完整响应（对齐 Go 的 client.Do + ReadAll）。 */
    private Response get(URI uri, List<InetAddress> pinned, FetchLimits limits)
            throws IOException {
        String host = uri.getHost();
        int port = Integer.parseInt(portOf(uri));
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, limits.timeout().toMillis());

        Socket socket = dialer != null
                ? dialer.dial(host, port, timeoutMillis)
                : dialPinned(pinned, port, timeoutMillis, host);
        try {
            socket.setSoTimeout(timeoutMillis);
            Socket channel = https ? secure(socket, host, port) : socket;
            try (channel; InputStream in = new BufferedInputStream(channel.getInputStream())) {
                OutputStream out = channel.getOutputStream();
                out.write(request(uri, host, limits.userAgent()).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                return readResponse(in, limits);
            }
        } finally {
            closeQuietly(socket);
        }
    }

    /** 拼请求行与头（Keep-Alive 关闭：一次调用一条连接）。 */
    private static String request(URI uri, String host, String userAgent) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        String hostHeader = host.contains(":")
                ? "[" + host + "]:" + portOf(uri) : host + ":" + portOf(uri);
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: " + userAgent + "\r\n"
                + "Accept: " + ACCEPT + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
    }

    /** 防线三：只连校验过的地址（不解析主机名）。 */
    private static Socket dialPinned(List<InetAddress> pinned, int port, int timeoutMillis,
                                     String host) throws IOException {
        IOException lastError = null;
        for (InetAddress address : pinned) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeoutMillis);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                lastError = e;
            }
        }
        throw new IOException("web: 连接 \"" + host + "\" 失败: "
                + (lastError == null ? "没有可用地址" : lastError.getMessage()), lastError);
    }

    /** 在已连到公网地址的 socket 上做 TLS（SNI 与证书校验仍用原主机名）。 */
    private static Socket secure(Socket socket, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(host)));
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    // ── 响应解析 ──

    /** 读状态行 + 头 + 正文（正文受字节上限约束，支持 chunked 与 gzip）。 */
    private static Response readResponse(InputStream in, FetchLimits limits) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            throw new IOException("web: 响应不是 HTTP: " + statusLine);
        }
        int status;
        try {
            status = Integer.parseInt(statusLine.split(" ", 3)[1]);
        } catch (RuntimeException e) {
            throw new IOException("web: 响应状态行无效: " + statusLine, e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                // 同名头保留第一个（Content-Type / Location 都只取首值）
                headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        InputStream body = in;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = new ChunkedStream(body);
        }
        String contentEncoding = headers.get("content-encoding");
        if (contentEncoding != null
                && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            body = new GZIPInputStream(body);
        }
        return new Response(status, headers, readLimited(body, limits.maxResponseBytes()));
    }

    /** 读正文，最多 {@code max} 字节；超出部分丢掉并标记截断。 */
    private static Body readLimited(InputStream in, long max) throws IOException {
        BodyCollector collector = new BodyCollector(max);
        byte[] buffer = new byte[8192];
        while (!collector.isFull()) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            collector.write(buffer, read);
        }
        return collector.toBody();
    }

    /** 逐行读（CRLF / LF 都认；{@code null} = 流结束）。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * 把 Content-Type 归成可解的种类（对齐 Go {@code classifyContentType}）。
     *
     * <p>它同时是「二进制不能硬解」的守卫：宁可响亮失败，也不要把二进制塞进模型上下文。
     *
     * @param contentType 原始 Content-Type 头
     * @return {@code "html"} 或 {@code "text"}
     * @throws ToolException 无 Content-Type 或不支持的类型
     */
    static String classifyContentType(String contentType) {
        String mime = contentType == null ? ""
                : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (mime.isEmpty()) {
            throw new ToolException("web: 响应没有 Content-Type，无法判定是否为文本");
        }
        if ("text/html".equals(mime) || "application/xhtml+xml".equals(mime)) {
            return "html";
        }
        if (mime.startsWith("text/")) {
            return "text";
        }
        if ("application/json".equals(mime) || "application/xml".equals(mime)
                || mime.endsWith("+json") || mime.endsWith("+xml")) {
            return "text";
        }
        throw new ToolException("web: 不支持的内容类型 \"" + mime + "\"（只取文本类）");
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已得到的结果
        }
    }

    /** 一次响应（状态 + 头 + 正文 + 截断标记）。 */
    private record Response(int status, Map<String, String> headers, Body body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        byte[] bytes() {
            return body.bytes();
        }

        boolean truncated() {
            return body.truncated();
        }
    }

    /** 正文（最多 {@code max} 字节；超了标记截断）。 */
    private record Body(byte[] bytes, boolean truncated) {
    }

    /** 读流时累计到字节上限，超出的部分丢弃并标记截断。 */
    private static final class BodyCollector {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final long max;
        private boolean truncated;
        private boolean full;

        private BodyCollector(long max) {
            this.max = max;
        }

        private boolean isFull() {
            return full;
        }

        private void write(byte[] buffer, int read) {
            long room = max - out.size();
            if (read > room) {
                truncated = true;
                full = true;
            }
            int keep = (int) Math.min(read, Math.max(0, room));
            out.write(buffer, 0, keep);
        }

        private Body toBody() {
            return new Body(out.toByteArray(), truncated);
        }
    }

    /** chunked 传输解码（HTTP/1.1）。 */
    private static final class ChunkedStream extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean finished;

        private ChunkedStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished || length == 0) {
                return finished ? -1 : 0;
            }
            if (remaining == 0) {
                String sizeLine = readLine(in);
                if (sizeLine == null) {
                    finished = true;
                    return -1;
                }
                int semicolon = sizeLine.indexOf(';');
                String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
                if (hex.isEmpty()) {
                    finished = true;
                    return -1;
                }
                try {
                    remaining = Long.parseLong(hex, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("web: chunked 长度无效: " + sizeLine, e);
                }
                if (remaining == 0) {
                    // 吃掉 trailer（读到空行为止）
                    for (String line = readLine(in); line != null && !line.isEmpty();
                         line = readLine(in)) {
                        // 忽略 trailer 头
                    }
                    finished = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                finished = true;
                return -1;
            }
            remaining -= read;
            if (remaining == 0) {
                readLine(in); // 块尾 CRLF
            }
            return read;
        }
    }
}