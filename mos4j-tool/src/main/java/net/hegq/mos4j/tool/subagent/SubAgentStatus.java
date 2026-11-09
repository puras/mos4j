package net.hegq.mos4j.tool.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 子代理回执的状态。
 *
 * <p><b>三档，不多不少</b>：<b>子代理失败不是父轮次失败</b>——失败也要回一段正常的
 * 工具结果，父模型看得见、可自纠（对齐「工具报错是结果」这条口径）。
 */
public enum SubAgentStatus {

    /** 子代理跑完了。 */
    COMPLETED("completed"),

    /** 子代理跑失败了（回执里带错误说明）。 */
    ERROR("error"),

    /** 子代理超时被掐（工具级超时）。 */
    TIMEOUT("timeout");

    private final String wire;

    SubAgentStatus(String wire) {
        this.wire = wire;
    }

    /**
     * 线格式取值（小写，与 Go 侧一致）。
     *
     * <p>这也是回执 JSON 里的写法：父模型看到的是 {@code "status":"completed"}。
     *
     * @return 线格式取值
     */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * 按线格式解析（大小写不敏感）。
     *
     * @param value 线格式取值
     * @return 对应状态；无法识别时返回 {@code null}
     */
    @JsonCreator
    public static SubAgentStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (SubAgentStatus status : values()) {
            if (status.wire.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
}
