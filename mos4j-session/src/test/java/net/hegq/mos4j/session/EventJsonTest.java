package net.hegq.mos4j.session;

import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.llm.model.Usage;
import net.hegq.mos4j.llm.model.UsageDetails;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件序列化的 JSON 口径守护（机制：NON_DEFAULT 语义）。
 *
 * <p><b>为什么是守护测试</b>：口径错了不会立刻报错——日志里多一个 {@code "turn":0}
 * 没人会发现，但审计脚本「按键存在即有值」消费时会读到假数据。
 * 这些断言就是当初 G0 门机械判定项的 Java 端固化。</p>
 */
class EventJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void turnZeroIsOmitted() throws Exception {
        Event event = Event.builder(EventKind.ASSISTANT).text("hi").build();

        String json = MAPPER.writeValueAsString(event);

        assertFalse(json.contains("\"turn\""), "turn=0 必须省略：" + json);
        assertTrue(json.contains("\"kind\":\"assistant\""));
        assertTrue(json.contains("\"seq\":0"), "seq 无条件输出");
        assertTrue(json.contains("\"time\""), "time 无条件输出");
    }

    @Test
    void turnNonZeroIsKept() throws Exception {
        Event event = Event.builder(EventKind.USER).turn(3).text("hi").build();

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"turn\":3"), json);
    }

    @Test
    void emptyUsageDetailsIsOmitted() throws Exception {
        Event event = Event.builder(EventKind.ASSISTANT)
                .usage(new Usage(10, 10, 3, 13, UsageDetails.empty()))
                .build();

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"usage\""), json);
        assertFalse(json.contains("\"details\""), "空 details 必须省略：" + json);
    }

    @Test
    void toolResultIsErrorFalseIsOmitted() throws Exception {
        Event event = Event.builder(EventKind.TOOL_RESULT)
                .toolResult(new ToolResult("call_1", "内容", false))
                .build();

        String json = MAPPER.writeValueAsString(event);

        assertFalse(json.contains("isError"), "isError=false 必须省略：" + json);
        assertTrue(json.contains("\"callId\":\"call_1\""));
    }

    @Test
    void compactionTokensBeforeZeroIsOmitted() throws Exception {
        Event event = Event.builder(EventKind.COMPACTION)
                .compaction(new Compaction("摘要", 5L, 0))
                .build();

        String json = MAPPER.writeValueAsString(event);

        assertFalse(json.contains("tokensBefore"), "tokensBefore=0 必须省略：" + json);
        assertTrue(json.contains("\"firstKeptSeq\":5"));
    }

    @Test
    void skillActivateSerializesMetadataOnly() throws Exception {
        Event event = Event.builder(EventKind.SKILL_ACTIVATE)
                .turn(2)
                .skill(new SkillEvent("deploy", "发布流程", "/skills/deploy", null))
                .build();

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"kind\":\"skill/activate\""), json);
        assertTrue(json.contains("\"skill\":{\"name\":\"deploy\""), json);
        assertTrue(json.contains("\"description\":\"发布流程\""), json);
        assertTrue(json.contains("\"dir\":\"/skills/deploy\""), json);
        assertFalse(json.contains("missingAllowed"), "激活事件不带 mismatch 载荷：" + json);
    }

    @Test
    void skillMismatchSerializesMissingAllowedArray() throws Exception {
        Event event = Event.builder(EventKind.SKILL_ALLOWED_TOOLS_MISMATCH)
                .turn(2)
                .skill(new SkillEvent("scoped", null, null, java.util.List.of("deploy", "git")))
                .build();

        String json = MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"kind\":\"skill/allowed-tools-mismatch\""), json);
        assertTrue(json.contains("\"missingAllowed\":[\"deploy\",\"git\"]"), json);
        assertFalse(json.contains("\"description\""), "空描述必须省略（Go omitempty）：" + json);
        assertFalse(json.contains("\"dir\""), "空目录必须省略：" + json);
    }
}
