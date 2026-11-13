package net.hegq.mos4j.tool.web;

/**
 * 抓取后端（web_fetch 的能力接口，T5.9）。
 *
 * <p><b>为什么这里是接口</b>（对齐 Go 的 {@code web.Fetcher}）：缺省实现
 * {@link PinnedConnector} 带着一整套 SSRF 防线（公网地址校验 → 地址钉住 → 同源重定向 →
 * 体积与超时上限），那套防线<b>只对直连公网成立</b>。真实宿主常有自己的出网方式：
 * 企业代理 / 网关 / 测试内网的服务 —— 这些场景下「拒绝一切内网地址」恰恰是错的。
 * 所以抓取后端可以换：需要代理或专线的宿主<b>自备 {@code Fetcher} 实现</b>，
 * 并把访问策略的责任一并接过去（Go 侧注释明写同一句话）。
 *
 * <p><b>换掉实现 = 换掉防线</b>：注入自定义 {@code Fetcher} 的宿主自己负责
 * SSRF 防护、地址校验、体积与超时上限。SDK 不会、也无法在接口后面补一层
 * ——它拿到的就是一个已经决定好「去哪、怎么去」的实现。
 *
 * <p>{@link #name()} 用于诊断（拼进错误信息），让宿主看清「这次抓取走的是哪个后端」。
 */
public interface Fetcher {

    /** 后端名（诊断用，例如 {@code "pinned"} / {@code "proxy"}）。 */
    String name();

    /**
     * 抓一次。
     *
     * @param request 请求（地址、已解析并校验过的地址、超时、体积上限）
     * @return 正文或重定向（<b>非 2xx 是结果不是错误</b>：404 是资源的状态）
     * @throws java.io.IOException 传输层失败（DNS 失败、连接被拒、超时）
     * @throws net.hegq.mos4j.tool.ToolException 语义失败（非文本类型、重定向无 Location）
     */
    WebFetchTool.FetchResult fetch(WebFetchTool.FetchRequest request) throws java.io.IOException;

    /**
     * 上一次抓取是否触发了体积截断。
     *
     * <p>历史原因留在这个接口上：{@link PinnedConnector} 在流式读取时才知道截断，
     * 而正文与截断标记要一起返回，所以它先记在实例上、随后由调用方取走。
     * 自定义实现可以返回 {@code false}（把截断自己塞进 {@code Body.truncated} 即可）。
     *
     * <p>默认返回 {@code false}：新实现不必关心这个遗留约定。
     */
    default boolean lastReadTruncated() {
        return false;
    }
}