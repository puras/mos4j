package net.hegq.mos4j.tool.web;

import java.net.InetAddress;
import java.util.List;

/**
 * web_fetch 的 SSRF 地址校验（防线二，T5.9）：只允许公网地址。
 *
 * <p><b>为什么这道检查不能省</b>：web_fetch 是模型发起的出网请求。
 * 不校验地址的话，模型可以用 {@code http://127.0.0.1:6379/} 这类 URL
 * 打内网服务（Redis / metadata 服务 / 数据库管理面）——SSRF 的经典利用。
 *
 * <p><b>必须校验「解析结果」而不是「URL 文本」</b>：{@code http://localhost.example.com/}
 * 文本看着无害，解析出来是 127.0.0.1。校验发生在 DNS 解析之后（见 {@link PinnedConnector}）。
 *
 * <p><b>逐字节移植 Go 的 {@code isPublicIP}／{@code isPublicIPv4}／{@code isPublicIPv6}</b>。
 * 不用 JDK 的 {@code isSiteLocalAddress} 之类便捷判定，理由是它们的覆盖集合与 Go 不同：
 * <ul>
 *   <li>JDK 的 {@code isLoopbackAddress} 只认 {@code 0.0.0.0} 一个地址，Go 拒整个 {@code 0/8}；</li>
 *   <li>JDK 不认 TEST-NET（{@code 192.0.2/24} / {@code 198.51.100/24} / {@code 203.0.113/24}）；</li>
 *   <li>JDK 不认 {@code 240/4} 保留段与广播地址（{@code 224/4} 之外的部分）；</li>
 *   <li><b>最要紧的</b>：JDK 完全不认内嵌 IPv4 的 IPv6 变体——{@code 64:ff9b::7f00:1}（NAT64）、
 *       {@code 2002:7f00:1::}（6to4）、{@code 2001::1:2:3:4}（Teredo）在 JDK 眼里都是普通全局地址，
 *       而它们在支持该转换的网络里分别打到 {@code 127.0.0.1} 等 IPv4 目标。
 *       这是可用的 SSRF 绕过路径，实测确认过（见 {@code PinnedConnectorTest.publicAddressClassification}）。</li>
 * </ul>
 *
 * <p><b>内嵌 IPv4 的四种变体：一律拒绝</b>（对齐 Go 的<b>可观测行为</b>，见 GO-DIFF #22）。
 * Go 的注释说「也按 IPv4 判」，但 {@code embeddedIPv4} 返回的是 {@code net.IPv4(...)}
 * 的 16 字节形式，交给 {@code isPublicIPv4} 后第一条 {@code v4[0] == 0} 必然命中，
 * 于是那四个分支实际上<b>永远返回 false</b>（已用真 Go 代码实测：
 * {@code 64:ff9b::808:808} 内嵌 8.8.8.8，Go 仍判 false）。也就是说：
 * 无论内嵌的是什么地址，Go 都拒。这里跟 Go 保持一致的**可观测结果**（拒），而不是跟注释的意图——
 * 因为按意图判内嵌地址会**放行 Go 拒掉的地址**，等于放松安全边界。
 * 代价：只跑 NAT64 的纯 IPv6 网络抓不到网页（与 Go 同样局限，属设计取舍）。
 */
public final class PublicAddresses {

    private PublicAddresses() {
    }

    /**
     * 是否全部地址都可用于公网请求。
     *
     * @param addresses 解析结果（任一非公网即整体拒绝——
     *                  部分公网部分内网的解析结果更可疑：攻击者控制 DNS 轮询）
     * @return true = 全部公网
     */
    public static boolean allPublic(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                return false;
            }
        }
        return true;
    }

    /** 单个地址是否公网（对齐 Go {@code isPublicIP}）。 */
    public static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] raw = address.getAddress();
        return switch (raw.length) {
            case 4 -> isPublicV4(raw);
            case 16 -> isPublicV6(raw);
            default -> false;
        };
    }

    /**
     * IPv4 公网判定（对齐 Go {@code isPublicIPv4}，逐条同序）。
     *
     * <p>{@code 224/4} 起整段拒绝：组播、保留段、广播地址都不该被抓取。
     */
    private static boolean isPublicV4(byte[] v4) {
        int b0 = v4[0] & 0xFF;
        int b1 = v4[1] & 0xFF;
        int b2 = v4[2] & 0xFF;

        if (b0 == 0) {                       // 0.0.0.0/8
            return false;
        }
        if (b0 == 10) {                      // 10/8
            return false;
        }
        if (b0 == 127) {                     // 127/8 环回
            return false;
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {   // 100.64/10 CGNAT
            return false;
        }
        if (b0 == 169 && b1 == 254) {         // 169.254/16 链路本地（含云 metadata 169.254.169.254）
            return false;
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {    // 172.16/12
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {      // 192.0.0/24（协议保留，含 NAT64 发现地址）
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {      // 192.0.2/24 TEST-NET
            return false;
        }
        if (b0 == 192 && b1 == 168) {               // 192.168/16
            return false;
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {  // 198.18/15 基准测试
            return false;
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {   // 198.51.100/24 TEST-NET
            return false;
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {    // 203.0.113/24 TEST-NET
            return false;
        }
        if (b0 >= 224) {                            // 224/4 组播 + 240/4 保留 + 广播
            return false;
        }
        return true;
    }

    /**
     * IPv6 公网判定（对齐 Go {@code isPublicIPv6} 的可观测行为）。
     *
     * <p>重点是四类<b>内嵌 IPv4</b> 的地址（IPv4-mapped / NAT64 两种前缀 / 6to4 / Teredo）：
     * Go 在这四个分支上实际永远返回 false（原因见类注释），所以这里也不抽内嵌地址再判，
     * 直接拒绝——宁可 fail-closed。
     */
    private static boolean isPublicV6(byte[] v6) {
        // Go：IsUnspecified / IsLoopback / IsPrivate / IsLinkLocalUnicast /
        //     IsLinkLocalMulticast / IsInterfaceLocalMulticast / IsMulticast 一律拒
        if (isUnspecified(v6) || isLoopback(v6)) {
            return false;
        }
        if ((v6[0] & 0xFE) == 0xFC) {                    // fc00::/7 唯一本地地址
            return false;
        }
        if ((v6[0] & 0xFF) == 0xFE && (v6[1] & 0xC0) == 0x80) {   // fe80::/10 链路本地
            return false;
        }
        if ((v6[0] & 0xFF) == 0xFF) {                    // ff00::/8 组播（含 ff01::/16 接口本地）
            return false;
        }
        // 四类内嵌 IPv4 的地址：Go 实际一律拒（GO-DIFF #22），这里同结论
        return !isEmbeddedV4Form(v6);
    }

    /**
     * 是否是「内嵌 IPv4」的 IPv6 形式（IPv4-mapped / NAT64 两种前缀 / 6to4 / Teredo）。
     *
     * <p>IPv4-mapped（{@code ::ffff:0:0/96}）在 Java 里会被 {@link java.net.InetAddress}
     * 直接折成 IPv4 地址（长度 4），走不到这里；保留判定只是为了与 Go 的前缀清单逐条对位。
     */
    private static boolean isEmbeddedV4Form(byte[] v6) {
        // ::ffff:0:0/96
        if (hasPrefix(v6, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF)) {
            return true;
        }
        // NAT64 熟知前缀 64:ff9b::/96
        if (hasPrefix(v6, 0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0)) {
            return true;
        }
        // NAT64 本地使用前缀 64:ff9b:1::/48
        if (hasPrefix(v6, 0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01)) {
            return true;
        }
        // 6to4：2002::/16
        if ((v6[0] & 0xFF) == 0x20 && (v6[1] & 0xFF) == 0x02) {
            return true;
        }
        // Teredo：2001:0000::/32
        return (v6[0] & 0xFF) == 0x20 && (v6[1] & 0xFF) == 0x01
                && (v6[2] & 0xFF) == 0x00 && (v6[3] & 0xFF) == 0x00;
    }

    /** 全零地址 {@code ::}。 */
    private static boolean isUnspecified(byte[] v6) {
        for (byte b : v6) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** 环回 {@code ::1}。 */
    private static boolean isLoopback(byte[] v6) {
        for (int i = 0; i < 15; i++) {
            if (v6[i] != 0) {
                return false;
            }
        }
        return v6[15] == 1;
    }

    /** 地址是否以给定字节序列开头（对齐 Go {@code hasPrefix}）。 */
    private static boolean hasPrefix(byte[] v6, int... prefix) {
        if (v6.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((v6[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}