package net.hegq.mos4j.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * MCP 工具的公开名（模型看到的名字）。
 *
 * <p>形态：{@code mcp__<server>__<tool>}，与 Go 参考实现 {@code tool/mcp.ToolName} 同规则：
 * <ul>
 *   <li>非 {@code [A-Za-z0-9_]} 的字符一律换成 {@code _}（名字要进模型的 function name 段，
 *       字符集越窄越安全）；</li>
 *   <li>超过 {@value #MAX_NAME_BYTES} <b>字节</b>时截断并追加 8 位 SHA-256 后缀
 *       （后缀由**完整原名**算出，截断后仍能区分不同工具）。</li>
 * </ul>
 *
 * <p><b>与 Go 的一处有意差异</b>：Go 按字节切名字，可能切在多字节字符中间而产生非法 UTF-8；
 * Java 侧保证结果始终是合法 UTF-8（切到半个字符时用替换字符收尾）。
 * 名字只用于标识，合法性比逐字节一致更重要。
 */
public final class McpToolNaming {

    /** 命名空间前缀。 */
    public static final String PREFIX = "mcp__";

    /** 公开名长度上限（字节；模型侧工具名的常见上限）。 */
    public static final int MAX_NAME_BYTES = 64;

    /** 超长时追加的哈希后缀长度（8 位十六进制）。 */
    private static final int HASH_SUFFIX_LENGTH = 8;

    private McpToolNaming() {
    }

    /**
     * 拼装公开名。
     *
     * @param server 服务器名
     * @param tool 上游工具名
     * @return 公开名
     */
    public static String publicName(String server, String tool) {
        String name = PREFIX + sanitize(server) + "__" + sanitize(tool);
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_NAME_BYTES) {
            return name;
        }
        String suffix = sha256Hex(bytes).substring(0, HASH_SUFFIX_LENGTH);
        int keep = MAX_NAME_BYTES - HASH_SUFFIX_LENGTH - 1;
        return new String(Arrays.copyOf(bytes, keep), StandardCharsets.UTF_8) + "_" + suffix;
    }

    /**
     * 把名字里的非法字符换成 {@code _}。
     *
     * <p>按 UTF-8 <b>字节</b>处理（非 ASCII 字符每字节一个 {@code _}）——名字是标识符不是文本，
     * 一个汉字变成几个下划线无所谓，只要稳定（同名每次算出同一个结果）。
     */
    static String sanitize(String raw) {
        byte[] bytes = (raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            boolean legal = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            sb.append(legal ? c : '_');
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
