package net.hegq.mos4j.tool.web;

import java.io.IOException;
import java.time.Duration;

/**
 * 抓取后端（web_fetch 的能力接口，T5.9）。
 *
 * <p><b>为什么这里是接口</b>（对齐 Go 的 {@code web.Fetcher}）：缺省实现
 * {@link PinnedConnector} 带着一整套 SSRF 防线（URL 校验 → 公网地址校验 → 地址钉住 →
 * 同源重定向 → 体积与超时上限），那套防线<b>只对直连公网成立</b>。真实宿主常有自己的
 * 出网方式：企业代理 / 网关 / 测试内网的服务 —— 这些场景下「拒绝一切内网地址」恰恰是错的。
 * 所以抓取后端可以换：需要代理或专线的宿主<b>自备 {@code Fetcher} 实现</b>，
 * 并把访问策略的责任一并接过去（Go 侧注释明写同一句话）。
 *
 * <p><b>换掉实现 = 换掉防线</b>：{@link WebFetchTool} 只做「参数校验 + 结果整形」，
 * URL 校验、地址解析、公网校验、钉住、重定向策略、内容类型判定<b>全在实现里</b>。
 * 注入自定义实现的宿主自己负责这些；SDK 不会、也无法在接口后面补一层。
 *
 * <p><b>内容处理也归实现</b>（对齐 Go {@code HTTPFetcher.Fetch}）：HTML 转纯文本、
 * 提标题都在后端做完，{@code content} 必须是已转好的纯文本。
 *
 * <p><b>重定向由实现自己跟</b>：缺省后端只跟同源、有跳数上限。工具层不看重定向——
 * 一次调用就是「把最终正文给我」。
 *
 * <p>注意：{@link #name()} 用于诊断，让宿主看清「这次抓取走的是哪个后端」；
 * Go 的 {@code Fetcher} 也有 {@code Name()}，两边同形。
 */
public interface Fetcher {

    /** 后端名（诊断用，例如 {@code "http"} / {@code "proxy"}）。 */
    String name();

    /**
     * 抓一次。
     *
     * <p><b>非 2xx 是结果不是错误</b>：404 是资源的状态，不是抓取失败；只有传输层
     * 故障（DNS / 连接 / 超时）与非文本内容才抛异常。
     *
     * @param url    目标地址（已 trim；协议与长度校验由实现负责）
     * @param limits 资源上限（实现须自己归一化）
     * @return 结果（带状态码、最终地址、标题、内容类型、截断标记）
     * @throws IOException 传输层失败（DNS 失败、连接被拒、超时）
     * @throws net.hegq.mos4j.tool.ToolException 语义失败（URL 非法、内网地址、
     *         跨源重定向、非文本类型、无 Content-Type）
     */
    FetchResult fetch(String url, FetchLimits limits) throws IOException;
}