package net.hegq.mos4j.core;

import java.util.Collections;
import java.util.List;

/**
 * 事件字段名契约（冻结清单 docs/contract/fieldnames.json 的 Java 视图）。
 *
 * <p><b>为什么存在</b>：事件字段名是会话日志与所有消费端的契约（§4.4：日志与协议同形）。
 * 字段名手抖（camelCase 写成 snake_case、改名、增删）会静默破坏下游解析——
 * 消费端读到的是空字段而不是错误。所以字段名只允许从这里引用，不允许在
 * 序列化注解里手写字符串。</p>
 *
 * <p><b>守护</b>：{@code FieldNameContractTest}（J0 建骨架，J1 点亮断言）把
 * Java 序列化键集合与冻结清单比对，故意改错一个即红。</p>
 */
public final class FieldNames {

    /** 冻结清单路径（classpath: 供守护测试读取）。 */
    public static final String FROZEN_LIST_RESOURCE = "/contract/fieldnames.json";

    // ---- Event（mos4j-session 事件，照抄 Go camelCase 混用不对称性） ----
    public static final String SEQ = "seq";
    public static final String KIND = "kind";
    public static final String TURN = "turn";
    public static final String TIME = "time";
    public static final String TEXT = "text";
    public static final String REASONING = "reasoning";
    public static final String TOOL_CALLS = "toolCalls";
    public static final String TOOL_CALL = "toolCall";
    public static final String TOOL_RESULT = "toolResult";
    public static final String USAGE = "usage";
    public static final String REASON = "reason";
    public static final String APPROVAL = "approval";
    public static final String COMPACTION = "compaction";
    public static final String ERROR = "error";

    // ---- ToolCall / ToolResult ----
    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String ARGS = "args";
    public static final String CALL_ID = "callId";
    public static final String CONTENT = "content";
    public static final String IS_ERROR = "isError";

    // ---- Usage（Go snake_case 口径照抄） ----
    public static final String PROMPT_TOKENS = "prompt_tokens";
    public static final String FRESH_INPUT_TOKENS = "fresh_input_tokens";
    public static final String COMPLETION_TOKENS = "completion_tokens";
    public static final String TOTAL_TOKENS = "total_tokens";
    public static final String DETAILS = "details";

    private FieldNames() {
    }

    /**
     * 核心事件键集合（守护测试的最小断言面；全量以冻结清单为准）。
     *
     * <p>返回不可变视图——调用方修改它没有意义，字段名契约本身不可变。</p>
     */
    public static List<String> coreEventKeys() {
        return List.of(SEQ, KIND, TURN, TIME, TEXT, REASONING, TOOL_CALLS, TOOL_CALL,
                TOOL_RESULT, USAGE, REASON, APPROVAL, COMPACTION, ERROR);
    }

    /** 全量字段名（与冻结清单 {@code _names_sha256} 对应的 name 集合）。 */
    public static List<String> allKeys() {
        return Collections.unmodifiableList(List.copyOf(coreEventKeys()));
    }
}
