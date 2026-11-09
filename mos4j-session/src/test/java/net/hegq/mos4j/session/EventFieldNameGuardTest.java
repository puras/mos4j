package net.hegq.mos4j.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.FieldNames;
import net.hegq.mos4j.model.InputTokenDetails;
import net.hegq.mos4j.model.OutputTokenDetails;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.model.UsageDetails;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段名守护（防线 1 的第二道：真实 JSON 键集合）。
 *
 * <p>{@link net.hegq.mos4j.core.FieldNameContractTest} 守的是「Java 常量 ⊆ 冻结清单」——
 * 它能挡住改常量，但挡不住「绕过常量、在 DTO 上另起一个键」。
 * 本测试直接序列化真实事件，把**实际出现在 JSON 里的每一个键**（递归）拿去比对冻结清单：
 * 任何自创字段（当年被清掉的 {@code skill} 就是这么漏进来的）当场红。
 *
 * <p>用法：把每种事件类型各造一个「载荷填满」的例子，保证每条可能出现的键都被覆盖到。
 */
class EventFieldNameGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TreeSet<String> frozenNames() throws IOException {
        try (InputStream in = EventFieldNameGuardTest.class.getResourceAsStream(FieldNames.FROZEN_LIST_RESOURCE)) {
            assertNotNull(in, "冻结清单必须在 classpath: " + FieldNames.FROZEN_LIST_RESOURCE);
            JsonNode root = MAPPER.readTree(in);
            TreeSet<String> names = new TreeSet<>();
            root.get("fields").forEach(f -> names.add(f.get("name").asText()));
            return names;
        }
    }

    private static void collect(JsonNode node, List<String> sink) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                sink.add(name);
                collect(node.get(name), sink);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, sink));
        }
    }

    private static List<String> keysOf(Object value) throws IOException {
        JsonNode tree = MAPPER.readTree(MAPPER.writeValueAsString(value));
        List<String> sink = new ArrayList<>();
        collect(tree, sink);
        return sink;
    }

    private static Event.Builder full(EventKind kind) {
        return Event.builder(kind)
                .turn(1)
                .time("2026-11-05T00:00:00Z")
                .text("文本")
                .reasoning("推理")
                .toolCalls(List.of(new ToolCall("call_1", "calculator", "{\"a\":1}")))
                .toolCall(new ToolCall("call_1", "calculator", "{\"a\":1}"))
                .toolResult(new ToolResult("call_1", "结果", true))
                .usage(fullUsage())
                .reason("stop")
                .approval(new Approval("ap_1", "calculator", "call_1", "原因", "approved"))
                .compaction(new Compaction("摘要", 3, 100))
                .error("错误详情");
    }

    private static Usage fullUsage() {
        return new Usage(10, 6, 3, 13, new UsageDetails(
                new InputTokenDetails(4, 1, 2),
                new OutputTokenDetails(5, 1)));
    }

    @Test
    void everySerializedEventKeyIsInTheFrozenList() throws IOException {
        TreeSet<String> frozen = frozenNames();

        List<Event> events = new ArrayList<>();
        for (EventKind kind : EventKind.values()) {
            events.add(full(kind).build());
        }

        List<String> seen = new ArrayList<>();
        for (Event event : events) {
            seen.addAll(keysOf(event));
        }

        for (String key : seen) {
            assertTrue(frozen.contains(key),
                    () -> "事件 JSON 里出现了不在冻结清单中的键 '" + key + "'——自创字段会让消费端读到空值而不是报错；"
                            + "字段名变更必须重新抽取清单并走 PR 评审");
        }
        assertFalse(seen.isEmpty(), "守护测试自身失效：没有收集到任何键");
    }

    @Test
    void headerKeysAreInTheFrozenList() throws IOException {
        TreeSet<String> frozen = frozenNames();
        Header header = new Header("s1", "2026-11-05T00:00:00Z", "标题", "/tmp", "parent", 7L);
        for (String key : keysOf(header)) {
            assertTrue(frozen.contains(key),
                    () -> "Header JSON 里出现了不在冻结清单中的键 '" + key + "'");
        }
    }

    @Test
    void usageKeysAreInTheFrozenList() throws IOException {
        TreeSet<String> frozen = frozenNames();
        for (String key : keysOf(fullUsage())) {
            assertTrue(frozen.contains(key),
                    () -> "Usage JSON 里出现了不在冻结清单中的键 '" + key + "'");
        }
    }
}
