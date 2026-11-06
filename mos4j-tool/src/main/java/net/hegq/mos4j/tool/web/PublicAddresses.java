package net.hegq.mos4j.tool.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * web_fetch 的 SSRF 地址校验（防线一，T5.9）：只允许公网地址。
 *
 * <p><b>为什么这道检查不能省</b>：web_fetch 是模型发起的出网请求。
 * 不校验地址的话，模型可以用 {@code http://127.0.0.1:6379/} 这类 URL
 * 打内网服务（Redis / metadata 服务 / 数据库管理面）——SSRF 的经典利用。
 *
 * <p><b>必须校验「解析结果」而不是「URL 文本」</b>：{@code http://localhost.example.com/}
 * 文本看着无害，解析出来是 127.0.0.1。校验发生在 DNS 解析之后（见 WebFetchTool）。
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

    /** 单个地址是否公网。 */
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()          // 0.0.0.0 / ::
                || address.isLoopbackAddress()   // 127/8 / ::1
                || address.isLinkLocalAddress()  // 169.254/16 / fe80::/10
                || address.isMulticastAddress()  // 224/4 / ff00::/8
                || address.isSiteLocalAddress()) { // 10/8, 172.16/12, 192.168/16; fec0::/10 已废弃但仍拒
            return false;
        }
        if (address instanceof Inet4Address v4) {
            int first = v4.getAddress()[0] & 0xFF;
            int second = v4.getAddress()[1] & 0xFF;
            // 100.64/10（CGNAT，运营商内网）
            if (first == 100 && second >= 64 && second <= 127) {
                return false;
            }
            // 192.0.0/24（IETF 协议分配）、198.18/15（基准测试网段）
            if (first == 192 && second == 0 && (v4.getAddress()[2] & 0xFF) == 0) {
                return false;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet6Address v6) {
            byte[] bytes = v6.getAddress();
            // IPv4 映射 ::ffff:0:0/96（::ffff:127.0.0.1 这种伪装）
            boolean v4mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    v4mapped = false;
                    break;
                }
            }
            if (v4mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                byte[] v4 = new byte[4];
                System.arraycopy(bytes, 12, v4, 0, 4);
                try {
                    return isPublic(InetAddress.getByAddress(v4));
                } catch (java.net.UnknownHostException e) {
                    return false; // 4 字节地址不会触发；防御性兜底
                }
            }
            // ULA fc00::/7（bit7=1 的 fd00::/8 常用，fc00 也一并拒）
            if ((bytes[0] & 0xFE) == 0xFC) {
                return false;
            }
            return true;
        }
        return false;
    }
}
