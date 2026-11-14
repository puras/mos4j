package net.hegq.mos4j.tool.web;

import java.io.IOException;
import java.net.Socket;

/**
 * 拨号器：把一个「主机:端口」变成已连接的 {@link Socket}（对齐 Go
 * {@code HTTPFetcher.Dial}）。
 *
 * <p><b>它不是安全检查</b>：默认实现（{@link PinnedConnector} 内部的钉住拨号）只会连
 * 到已经校验过的地址；注入实现则<b>不再钉住</b>——「连到哪」的责任转移给调用方。
 * Go 的注释写得更直白：给了它就<b>不再钉住地址</b>，调用方自负责任（自建代理、
 * 测试内网服务时才需要）。
 *
 * <p>因为这条责任转移是真实的，接口刻意做成两个参数都收完整信息（主机名 + 端口），
 * 而不是只收地址——实现方需要自己决定怎么用。
 *
 * @see PinnedConnector
 */
@FunctionalInterface
public interface Dialer {

    /**
     * 连到一个「主机:端口」。
     *
     * @param host          主机名（钉住实现会拒绝不是已校验主机的值）
     * @param port          端口
     * @param timeoutMillis 连接超时（毫秒）
     * @return 已连接的 socket（调用方负责关闭）
     * @throws IOException 连接失败
     */
    Socket dial(String host, int port, int timeoutMillis) throws IOException;
}