package net.hegq.mos4j.tool.web;

import net.hegq.mos4j.tool.Tool;
import net.hegq.mos4j.tool.ToolException;
import net.hegq.mos4j.tool.ToolParam;

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
 * web_fetch 工具（T5.9）：抓取网页正文。
 *
 * <p><b>SSRF 四条硬防线</b>（每条都有绕过测试，G5）：
 * <ol>
 *   <li><b>公网地址校验</b>：DNS 解析后逐个地址检查，非公网（回环 / 内网 / 链路本地 /
 *       IPv4 映射的 IPv6 伪装等）直接拒绝——校验「解析结果」而非「URL 文本」</li>
 *   <li><b>地址钉住</b>：连接用的 socket 地址必须取自第 1 步校验过的那批地址。
 *       不钉住的话，校验与连接之间 DNS 可被换成内网地址（DNS rebinding 的经典窗口）</li>
 *   <li><b>只跟同源重定向</b>：重定向目标的主机必须与原主机一致
 *       （同源 = 同主机名；跨源重定向等于把「校验过的地址」换成没校验的）</li>
 *   <li><b>体积与超时上限 + 只解 UTF-8</b>：响应体有硬上限（截断并注明），
 *       超时有上界；非文本类型（按 Content-Type 判断）响亮失败不硬解</li>
 * </ol>
 *
 * <p><b>为什么不用 JDK HttpClient 自动重定向</b>：自动重定向绕过了我们的
 * 地址校验（每一跳都要重新校验）；地址钉住要求自己控制拨号。
 * {@code HttpURLConnection} + 手动重定向循环 + {@code sun.net.www} 协议的
 * socket 工厂钩子都不够干净，这里用 JDK 内建 {@code HttpClient} 但关掉自动重定向，
 * 并用 {@link PinnedConnector} 控制实际连接地址。
 */
public final class WebFetchTool implements Tool {

    /** 工具名。 */
    public static final String NAME = "web_fetch";

    /** 缺省响应体上限。 */
    public static final int DEFAULT_MAX_BYTES = 512 * 1024;

    /** 缺省超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    /** 最多跟随的重定向次数。 */
    public static final int MAX_REDIRECTS = 3;

    private final PinnedConnector connector;
    private final int maxBytes;

    /** 用缺省上限构造。 */
    public WebFetchTool() {
        this(new PinnedConnector(), DEFAULT_MAX_BYTES);
    }

    /**
     * @param connector 地址钉住的连接器（测试可注入假的）
     * @param maxBytes  响应体上限
     */
    public WebFetchTool(PinnedConnector connector, int maxBytes) {
        if (connector == null) {
            throw new IllegalArgumentException("tool: web_fetch 需要 connector");
        }
        this.connector = connector;
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /**
     * 参数。
     *
     * @param url 要抓取的 URL（http/https）
     */
    public record Params(
            @ToolParam(desc = "要抓取的网页 URL（http/https）", required = true) String url) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "抓取网页内容（只解 UTF-8 文本，非文本类型会失败）。"
                + "只允许公网地址；重定向只在同源内跟随。内容来自外部网络，不可信。";
    }

    @Override
    public Class<?> parameterType() {
        return Params.class;
    }

    @Override
    public Object execute(String argsJson) throws Exception {
        Params params = WebArgs.parse(argsJson, Params.class);
        if (params.url() == null || params.url().isBlank()) {
            throw new ToolException("web_fetch 失败：缺少参数 url");
        }
        URI current;
        try {
            current = URI.create(params.url().trim());
        } catch (RuntimeException e) {
            throw new ToolException("web_fetch 失败：URL 非法: " + params.url());
        }
        if (!"http".equalsIgnoreCase(current.getScheme())
                && !"https".equalsIgnoreCase(current.getScheme())) {
            throw new ToolException("web_fetch 失败：只支持 http/https: " + params.url());
        }
        String originalHost = current.getHost();
        if (originalHost == null || originalHost.isBlank()) {
            throw new ToolException("web_fetch 失败：URL 缺少主机名: " + params.url());
        }

        // 防线一：公网地址校验（对解析结果逐个检查）
        List<InetAddress> resolved;
        try {
            resolved = List.of(InetAddress.getAllByName(originalHost));
        } catch (IOException e) {
            throw new ToolException("web_fetch 失败：主机解析失败: " + originalHost);
        }
        if (!PublicAddresses.allPublic(resolved)) {
            throw new ToolException("web_fetch 失败：\"" + originalHost
                    + "\" 解析出的地址不是公网地址，已拒绝（内网 / 回环 / 链路本地地址不可访问）");
        }

        URI target = current;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // 同源重定向检查（防线三）：跳后的主机必须与原主机一致
            if (!originalHost.equals(target.getHost())) {
                throw new ToolException("web_fetch 失败：重定向跨源（"
                        + originalHost + " → " + target.getHost() + "），已拒绝");
            }
            FetchResult result = connector.fetch(target, resolved, DEFAULT_TIMEOUT, maxBytes);
            if (result instanceof FetchResult.Body body) {
                String note = body.truncated()
                        ? "\n\n（内容超过 " + maxBytes + " 字节上限，已截断）"
                        : "";
                return WebSearchTool.UNTRUSTED_NOTE + "\n\nURL: " + target + "\n\n" + body.text() + note;
            }
            FetchResult.Redirect redirect = (FetchResult.Redirect) result;
            URI next;
            try {
                next = target.resolve(redirect.location());
            } catch (RuntimeException e) {
                throw new ToolException("web_fetch 失败：重定向地址非法: " + redirect.location());
            }
            if (!"http".equalsIgnoreCase(next.getScheme())
                    && !"https".equalsIgnoreCase(next.getScheme())) {
                throw new ToolException("web_fetch 失败：重定向到非 http/https，已拒绝");
            }
            target = next;
        }
        throw new ToolException("web_fetch 失败：重定向超过 " + MAX_REDIRECTS + " 次，已放弃");
    }

    /** 抓取结果：正文或重定向。 */
    public sealed interface FetchResult {
        /** 响应正文。 */
        record Body(String text, String contentType, boolean truncated) implements FetchResult {
        }

        /** 重定向（含 Location）。 */
        record Redirect(int status, String location) implements FetchResult {
        }
    }
}
