package net.hegq.mos4j.model;

/**
 * 消息角色。
 *
 * <p><b>为什么是枚举而不是字符串</b>：角色决定协议映射分支（system / assistant / tool
 * 在 OpenAI 与 Anthropic 的线格式完全不同）。用字符串会把这个决定推迟到序列化时，
 * 容易漏掉某个分支——漏掉的表现是「system 消息被当成 user 发出」，模型行为静默改变。
 * 枚举让「未处理的角色」在编译期或匹配时暴露。</p>
 *
 * <p>JSON 线上为小写字符串（{@code system}/{@code user}/{@code assistant}/{@code tool}），
 * 与 Go 侧 {@code llm.Role} 一致。</p>
 */
public enum Role {

    /** 系统提示（装配期渲染一次，保 prompt cache）。 */
    SYSTEM("system"),
    /** 用户输入。 */
    USER("user"),
    /** 模型输出。 */
    ASSISTANT("assistant"),
    /** 工具结果回执（配对 {@code tool_call_id}）。 */
    TOOL("tool");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** 线格式取值（小写）。 */
    public String wire() {
        return wire;
    }

    /**
     * 按线格式解析（大小写不敏感）。
     *
     * @return 对应角色；无法识别时返回 {@code null}（调用方按 user 兜底，与 Go 侧 default 分支一致）
     */
    public static Role fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Role role : values()) {
            if (role.wire.equalsIgnoreCase(value)) {
                return role;
            }
        }
        return null;
    }
}
