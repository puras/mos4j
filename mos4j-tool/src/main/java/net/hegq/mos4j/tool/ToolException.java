package net.hegq.mos4j.tool;

import java.util.Map;

/**
 * 工具调用失败（未知工具、参数解析失败、工具自身报错）。
 *
 * <p><b>为什么是异常而不是返回值</b>：失败必须能被<b>驱动层</b>区分并 fail-closed
 * 处理（拒执行 / 回填失败回执）；混进正常返回值里，失败会被当成成功继续往下走。</p>
 */
public class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
