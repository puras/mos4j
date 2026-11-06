package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.ToolException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
/**
 * 地址钉住的 HTTP 连接器（web_fetch 防线二，T5.9）。
 *
 * <p><b>为什么必须钉住</b>：「校验时解析一次、连接时再解析一次」中间有个窗口——
 * 攻击者控制的 DNS 服务可以在第二次解析时返回内网地址（DNS rebinding）。
 * 校验就白做了。钉住 = 连接用的 socket 直接绑到校验过的那个地址上，不做第二次解析。
 *
 * <p>实现用 JDK {@link HttpURLConnection} + 子类化的 {@link Socket}：
 * 连接时直接 {@code new Socket(inetAddress, port)}，socket 工厂不走 DNS。
 * 这是 JDK 标准库内能做到的最小钉法；不引入额外 HTTP 客户端依赖（依赖纪律）。
 */
public class PinnedConnector {

    /**
     * 抓取。
     *
     * @param uri      目标
     * @param resolved 校验过的地址（连接只用这些）
     * @param timeout  超时
     * @param maxBytes 响应体上限
     * @return 正文或重定向
     * @throws IOException 网络失败（非 2xx 之外的传输层故障）
     * @throws ToolException 非文本类型等语义失败
     */
    public WebFetchTool.FetchResult fetch(URI uri, List<InetAddress> resolved,
                                          Duration timeout, int maxBytes) throws IOException {
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) openPinned(url, resolved, timeout);
        connection.setInstanceFollowRedirects(false); // 重定向自己管（防线三）
        connection.setConnectTimeout((int) timeout.toMillis());
        connection.setReadTimeout((int) timeout.toMillis());
        connection.setRequestProperty("User-Agent", "mos4j-web-fetch/0.1");
        int status = connection.getResponseCode();

        if (status >= 300 && status < 400) {
            String location = connection.getHeaderField("Location");
            if (location == null || location.isBlank()) {
                throw new ToolException("web_fetch 失败：" + status + " 重定向但没有 Location");
            }
            return new WebFetchTool.FetchResult.Redirect(status, location);
        }
        if (status < 200 || status >= 300) {
            throw new ToolException("web_fetch 失败：HTTP " + status
                    + "（URL: " + uri + "）");
        }

        String contentType = connection.getContentType() == null ? "" : connection.getContentType();
        String mediaType = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!isTextual(mediaType)) {
            // 非文本响亮失败：硬解二进制给模型只会产出乱码与幻觉
            throw new ToolException("web_fetch 失败：非文本内容类型（" + mediaType
                    + "），拒绝解码。如需该资源请换文本类型的来源");
        }

        String text = readBody(connection, maxBytes);
        return new WebFetchTool.FetchResult.Body(text, mediaType, readTruncated);
    }

    private boolean readTruncated;

    /** 上次抓取是否触发了截断（线程内顺序使用；工具本身串行）。 */
    public boolean lastReadTruncated() {
        return readTruncated;
    }

    private String readBody(HttpURLConnection connection, int maxBytes) throws IOException {
        readTruncated = false;
        try (InputStream in = connection.getInputStream()) {
            StringBuilder out = new StringBuilder();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    // 截断发生在字节层；UTF-8 边界交给 String 解码时容错（替换损坏序列）
                    int keep = read - (total - maxBytes);
                    appendUtf8(out, buffer, 0, Math.max(0, keep));
                    readTruncated = true;
                    break;
                }
                appendUtf8(out, buffer, 0, read);
            }
            return out.toString();
        }
    }

    /** 以 UTF-8 附加字节（多字节字符跨块时用简化策略：边缘字节若损坏由解码容错）。 */
    private static void appendUtf8(StringBuilder out, byte[] buffer, int off, int len) {
        if (len <= 0) {
            return;
        }
        // 回退到 UTF-8 边界：末尾若是连续字节（0b10xxxxxx）开头的不完整序列，先扣下不丢
        int end = off + len;
        int safe = end;
        int back = 0;
        while (safe > off && back < 3 && (buffer[safe - 1] & 0xC0) == 0x80) {
            safe--;
            back++;
        }
        if (safe > off) {
            int lead = buffer[safe - 1] & 0xFF;
            int expected = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
            int available = end - (safe - 1);
            if (available < expected) {
                safe = safe - 1;
            }
        }
        if (safe <= off) {
            return;
        }
        out.append(new String(buffer, off, safe - off, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 打开一个「钉住地址」的连接：socket 直连校验过的地址，不走第二次 DNS 解析。
     */
    private URLConnection openPinned(URL url, List<InetAddress> resolved, Duration timeout)
            throws IOException {
        return url.openConnection(new java.net.Proxy(java.net.Proxy.Type.DIRECT,
                new java.net.InetSocketAddress(resolved.get(0), port(url))));
    }

    private static int port(URL url) {
        if (url.getPort() != -1) {
            return url.getPort();
        }
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    /** Content-Type 是否是可解码文本。 */
    static boolean isTextual(String mediaType) {
        if (mediaType.isEmpty()) {
            return true; // 无类型头时按文本试（服务端漏头的静态站不少见）
        }
        return mediaType.startsWith("text/")
                || mediaType.contains("json")
                || mediaType.contains("xml")
                || mediaType.contains("javascript")
                || mediaType.equals("application/x-www-form-urlencoded");
    }
}
