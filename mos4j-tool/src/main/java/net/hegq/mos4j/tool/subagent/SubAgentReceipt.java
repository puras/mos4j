package net.hegq.mos4j.tool.subagent;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子代理回执的解析：把子代理的<b>最终回答</b>投影成固定结构回执。
 *
 * <p>子代理被要求回答一段 JSON；答的不是 JSON（模型不听话、或者只说了半句话）
 * 就<b>整段当 summary</b>——<b>回执的形状由框架保证</b>（父模型永远拿到同一形状），
 * 内容是子代理的自述（父模型要自己复核）。
 */
public final class SubAgentReceipt {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SubAgentReceipt() {
    }

    /**
     * 解析子代理的最终回答。
     *
     * @param text 子代理的最终回答文本
     * @return 固定结构回执（永远不抛异常：答得不对也是回执）
     */
    public static SubAgentResult parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return SubAgentResult.completed("（子代理没有给出结论）", List.of());
        }
        String body = stripJsonFence(trimmed);
        try {
            Map<?, ?> map = MAPPER.readValue(body, Map.class);
            if (map != null) {
                String summary = map.get("summary") instanceof String text0 ? text0 : "";
                List<SubAgentFinding> findings = findingsOf(map.get("findings"));
                String error = map.get("error") instanceof String text0 ? text0 : "";
                String statusText = map.get("status") instanceof String text0 ? text0 : "";
                if (!statusText.isBlank()) {
                    // 子代理自报了状态：按状态投影（父模型要看得见它自己说失败了）。
                    SubAgentStatus status = SubAgentStatus.fromWire(statusText);
                    if (status == null) {
                        return SubAgentResult.error("子代理回执 status 非法: \"" + statusText
                                + "\"（只认 completed / error / timeout）");
                    }
                    if (status == SubAgentStatus.ERROR) {
                        return new SubAgentResult(status, summary, findings,
                                error.isBlank() ? "子代理自报失败但没给原因" : error);
                    }
                    if (status == SubAgentStatus.TIMEOUT) {
                        return new SubAgentResult(status, summary, findings,
                                error.isBlank() ? "子代理自报超时但没给原因" : error);
                    }
                    return new SubAgentResult(status, summary, findings, "");
                }
                if (!summary.isBlank() || !findings.isEmpty()) {
                    return SubAgentResult.completed(summary, findings);
                }
            }
        } catch (Exception ignored) {
            // 不是 JSON（或不是预期形状）→ 整段当 summary（下面统一处理）。
        }
        return SubAgentResult.completed(trimmed, List.of());
    }

    /** 读 findings 数组（形状不对就当没有线索，不让子代理的坏 JSON 把整轮回执打废）。 */
    private static List<SubAgentFinding> findingsOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SubAgentFinding> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object ref = map.get("ref");
            Object summary = map.get("summary");
            out.add(new SubAgentFinding(ref instanceof String text0 ? text0 : "",
                    summary instanceof String text0 ? text0 : ""));
        }
        return List.copyOf(out);
    }

    /**
     * 剥掉模型爱加的 {@code ```json} 代码围栏（剥不掉就原样返回）。
     *
     * @param text 文本
     * @return 剥掉围栏后的文本
     */
    public static String stripJsonFence(String text) {
        if (text == null || !text.startsWith("```")) {
            return text == null ? "" : text;
        }
        String body = text.substring(3);
        int newline = body.indexOf('\n');
        if (newline >= 0) {
            // 第一行是语言标记（```json / ```JSON / ```）。
            body = body.substring(newline + 1);
        }
        body = body.strip();
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3).strip();
        }
        return body;
    }
}
