package net.hegq.mos4j.tool.web;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/**
 * 主机名 → 地址集（对齐 Go {@code web.Resolver}）。
 *
 * <p><b>为什么可替换</b>：地址判定是 SSRF 防线的核心，但它需要一个能连本地服务的
 * 环境才能验证。把解析做成注入点，测试可以给「公网字面地址 + 本地监听」的组合，
 * 在生产路径上仍然是系统解析（{@link PinnedConnector} 缺省行为）。
 *
 * <p><b>替换它不会削弱防线</b>：返回的地址仍然逐个过
 * {@link PublicAddresses#isPublic(InetAddress)}——注入解析器只改变「地址从哪来」，
 * 不改变「什么地址被允许」。返 {@code null} 或空列表视为没有可用地址。
 *
 * @see PinnedConnector
 */
@FunctionalInterface
public interface Resolver {

    /**
     * 解析主机名的全部地址（IPv4 + IPv6）。
     *
     * @param host 主机名（不含端口）
     * @return 地址集
     * @throws IOException 解析失败
     */
    List<InetAddress> resolve(String host) throws IOException;
}