package net.hegq.mos4j.mcp;

/**
 * MCP 调用失败。
 *
 * <p><b>为什么要分层</b>：「服务器连不上 / 连接断了 / 超时」与「服务器回了但说这次调用被拒绝」
 * 是两件完全不同的事——前者是**服务器不可用**，后者是**这次调用被拒**。
 * 混在一起，宿主就只能靠猜，日志与快照也说不清到底是哪种。
 *
 * <p>对应 Go 参考实现的 transport / protocol 两层错误分类。
 */
public class McpError extends Exception {

    /** 错误分层。 */
    public enum Kind {
        /** 传输层：连不上、超时、连接断开、管道关闭——服务器不可用。 */
        TRANSPORT,
        /** 协议层：握手失败、返回体不合法、工具不存在——这次调用没成。 */
        PROTOCOL,
        /** 认证层：401 / 403、取凭据失败——服务器在，但这次请求没被认可。 */
        AUTH
    }

    private final Kind kind;

    /**
     * @param kind 错误分层
     * @param message 错误说明（会进日志与快照）
     */
    public McpError(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * @param kind 错误分层
     * @param message 错误说明
     * @param cause 原始异常
     */
    public McpError(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** 错误分层。 */
    public Kind kind() {
        return kind;
    }

    /** 服务器不可用（传输层）。 */
    public static McpError transport(String message, Throwable cause) {
        return new McpError(Kind.TRANSPORT, message, cause);
    }

    /** 协议层失败。 */
    public static McpError protocol(String message, Throwable cause) {
        return new McpError(Kind.PROTOCOL, message, cause);
    }

    /** 认证层失败（401 / 403 / 取凭据失败）。 */
    public static McpError auth(String message, Throwable cause) {
        return new McpError(Kind.AUTH, message, cause);
    }
}
