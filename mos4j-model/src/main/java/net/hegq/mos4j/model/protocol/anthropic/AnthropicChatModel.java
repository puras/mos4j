package net.hegq.mos4j.model.protocol.anthropic;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.Capability;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StatusError;
import net.hegq.mos4j.model.StreamAbortedException;
import net.hegq.mos4j.model.StreamChunk;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.StreamTruncatedException;
import net.hegq.mos4j.model.ContentPart;
import net.hegq.mos4j.model.InputTokenDetails;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.OutputTokenDetails;
import net.hegq.mos4j.model.Role;
import net.hegq.mos4j.model.ToolCall;
import net.hegq.mos4j.model.Usage;
import net.hegq.mos4j.model.UsageDetails;
import net.hegq.mos4j.model.transport.HttpTransport;
import net.hegq.mos4j.model.transport.SseLineReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Anthropic Messages 协议适配器（T6.1/T6.2）。
 *
 * <p><b>关键语义（不得擅改）</b>：</p>
 * <ul>
 *   <li><b>流完整性按结束标记判定</b>（机制 M5）：{@code message_stop} 是结束标记；
 *       只发 {@code message_delta.stop_reason} 不发 {@code message_stop} 是网关形态，
 *       结束原因即完成；<b>两者皆无才是截断</b>（A4/A6 语料）。结束原因为空 ≠ 截断（A5 语料）。</li>
 *   <li><b>usage 分两处到达、逐字段合并、delta 赢</b>（机制 M6）：
 *       {@code message_start.message.usage} 给输入侧，
 *       {@code message_delta.usage} 给最终快照——实测内网网关的
 *       {@code message_start.cache_read_input_tokens} 可能报「总输入」而非「命中子集」，
 *       必须 delta 赢、start 只补 delta 省略的字段（A10 语料）。</li>
 *   <li><b>工具调用参数按 index 分片累加</b>（{@code input_json_delta.partial_json}），
 *       原文拼接不解析（载荷冻结）。</li>
 *   <li><b>思维链</b>：{@code thinking_delta} 单独通道，与文本分开（A3 语料）。</li>
 * </ul>
 */
public final class AnthropicChatModel implements ChatModel {

    /** 提供方标识（错误文本前缀）。 */
    public static final String PROVIDER = "anthropic";

    /** 缺省 max_tokens（Anthropic 是必填字段；宿主未给时兜底）。 */
    public static final int DEFAULT_MAX_TOKENS = 4096;

    private final AnthropicConfig config;
    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public AnthropicChatModel(AnthropicConfig config) {
        this(config, new HttpTransport(config == null ? null : config.normalized().timeout()));
    }

    public AnthropicChatModel(AnthropicConfig config, HttpTransport transport) {
        this.config = config.normalized();
        this.transport = transport;
        this.mapper = new ObjectMapper();
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.FUNCTION_CALLING, Capability.STREAMING,
                Capability.STRUCTURED_OUTPUT, Capability.MULTIMODAL, Capability.REASONING);
    }

    // ---------------------------------------------------------------- 非流式

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        try {
            String body = mapper.writeValueAsString(buildRequest(request, false));
            HttpTransport.HttpResponseBody raw =
                    transport.send(config.messagesUrl(), body, config.headers(), false, cancellation);
            AnthropicDtos.MessageResponse parsed;
            try (InputStream in = raw.body()) {
                parsed = mapper.readValue(in, AnthropicDtos.MessageResponse.class);
            }
            if (parsed.error() != null && parsed.error().error() != null
                    && parsed.error().error().message() != null) {
                throw new StatusError(PROVIDER, 0,
                        parsed.error().error().message());
            }
            String content = "";
            String reasoning = null;
            List<ToolCall> toolCalls = null;
            if (parsed.content() != null) {
                StringBuilder text = new StringBuilder();
                StringBuilder think = new StringBuilder();
                toolCalls = new ArrayList<>();
                for (AnthropicDtos.ContentBlock block : parsed.content()) {
                    String type = block.type() == null ? "" : block.type();
                    switch (type) {
                        case "text" -> {
                            if (block.text() != null) {
                                text.append(block.text());
                            }
                        }
                        case "thinking" -> {
                            if (block.thinking() != null) {
                                think.append(block.thinking());
                            }
                        }
                        case "tool_use" -> toolCalls.add(new ToolCall(
                                block.id(), block.name(),
                                block.input() == null ? "{}" : mapper.writeValueAsString(block.input())));
                        default -> {
                            // 未知块类型忽略（不炸，但也不静默冒充文本）
                        }
                    }
                }
                content = text.toString();
                reasoning = think.length() > 0 ? think.toString() : null;
                if (toolCalls.isEmpty()) {
                    toolCalls = null;
                }
            }
            Message message = new Message(Role.ASSISTANT, content, null, toolCalls,
                    null, null, reasoning);
            return new Response(message, toUsage(parsed.usage()), parsed.stopReason());
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("anthropic: 请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("anthropic: 请求失败", e);
        }
    }

    // ------------------------------------------------------------------ 流式

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        return chatStream(request, emitter, CancellationToken.none());
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        Accumulator acc = new Accumulator();
        boolean sawStop = false;
        try {
            String body = mapper.writeValueAsString(buildRequest(request, true));
            HttpTransport.HttpResponseBody raw =
                    transport.send(config.messagesUrl(), body, config.headers(), true, cancellation);
            try (InputStream in = raw.body()) {
                final boolean[] stopped = {false};
                SseLineReader.read(in, line -> {
                    cancellation.throwIfCancelled();
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        return true;
                    }
                    String payload = trimmed.substring("data:".length()).trim();
                    if (payload.isEmpty()) {
                        stopped[0] = true;
                        return false;
                    }
                    AnthropicStreamDtos.StreamEvent event =
                            mapper.readValue(payload, AnthropicStreamDtos.StreamEvent.class);
                    String type = event.type() == null ? "" : event.type();
                    if ("message_stop".equals(type)) {
                        // 结束标记（M5）：message_stop 到即完整
                        stopped[0] = true;
                        return false;
                    }
                    if ("error".equals(type)) {
                        String detail = event.error() != null && event.error().error() != null
                                && event.error().error().message() != null
                                ? event.error().error().message() : "流式错误事件";
                        throw new StatusError(PROVIDER, 0, detail);
                    }
                    acc.accept(event);
                    StreamChunk emitted = acc.lastEmitted;
                    if (emitted != null && emitter != null) {
                        try {
                            emitter.emit(emitted);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new StreamAbortedException("流式输出被中断", e);
                        }
                    }
                    return true;
                });
                sawStop = sawStop || stopped[0];
            }
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("anthropic: 流式请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("anthropic: 流式请求失败", e);
        }

        // 流完整性（M5）：结束标记（message_stop）或结束原因（message_delta.stop_reason）
        // 任一到即完整；两者皆无 = 截断。结束原因为空 ≠ 截断（A5：只有 message_stop 也完整）。
        boolean hasStopReason = acc.stopReason != null && !acc.stopReason.isEmpty();
        if (!hasStopReason && !sawStop) {
            throw new StreamTruncatedException();
        }
        Message message = new Message(Role.ASSISTANT, acc.content.toString(), null,
                acc.finishToolCalls(), null, null,
                acc.reasoning.length() > 0 ? acc.reasoning.toString() : null);
        return new Response(message, acc.usage(), acc.stopReason);
    }

    // -------------------------------------------------------------- 请求构造

    private AnthropicDtos.MessageRequest buildRequest(Request request, boolean stream) {
        List<AnthropicDtos.MessageParam> messages = new ArrayList<>();
        String system = null;
        for (Message message : request.messages()) {
            if (message.role() == Role.SYSTEM) {
                // Anthropic 的 system 是顶层字段，不进 messages
                system = message.content() == null ? "" : message.content();
                continue;
            }
            // assistant 消息的 tool_calls 映射成 tool_use 块数组
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                messages.add(new AnthropicDtos.MessageParam(Role.ASSISTANT.wire(),
                        assistantBlocks(message)));
                continue;
            }
            messages.add(AnthropicDtos.MessageParam.of(message));
        }
        List<AnthropicDtos.ToolParam> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            tools = new ArrayList<>();
            for (var tool : request.tools()) {
                tools.add(AnthropicDtos.ToolParam.of(tool));
            }
        }
        // Anthropic 的 max_tokens 必填；宿主未配时用缺省
        int maxTokens = request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens();
        AnthropicDtos.ThinkingParam thinking = null;
        if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
            thinking = new AnthropicDtos.ThinkingParam("enabled", thinkingBudget(request.reasoningEffort()));
        }
        return new AnthropicDtos.MessageRequest(config.model(), maxTokens, messages,
                system, tools, request.temperature(), Boolean.valueOf(stream), thinking);
    }

    /** assistant 消息的 tool_calls → tool_use 块（文本作为第一个 text 块）。 */
    private List<Map<String, Object>> assistantBlocks(Message message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (message.content() != null && !message.content().isEmpty()) {
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", message.content())));
        }
        for (ToolCall call : message.toolCalls()) {
            Map<String, Object> input;
            try {
                input = mapper.readValue(call.args() == null || call.args().isEmpty()
                        ? "{}" : call.args(), Map.class);
            } catch (JacksonException e) {
                // 参数不是合法 JSON：Anthropic 要求对象；原样透传空对象会让模型看到错执行结果——
                // 这里宁可报错也不发语义错误的请求
                throw new IllegalArgumentException("anthropic: 工具调用参数不是合法 JSON: " + call.id());
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.put("input", input);
            blocks.add(block);
        }
        return blocks;
    }

    /** 推理投入 → thinking 预算（low=1k / medium=4k / high=10k）。 */
    private static int thinkingBudget(String effort) {
        return switch (effort) {
            case "low" -> 1024;
            case "high" -> 10240;
            default -> 4096;
        };
    }

    // -------------------------------------------------------------- 用量合并

    /**
     * 非流式用量归一（M6）：Anthropic 口径 {@code input_tokens} <b>不含</b>缓存命中，
     * 所以 freshInput = inputTokens（去缓存输入即原值）；
     * {@code totalTokens} 含缓存（input + cache_creation + cache_read + output）。
     */
    static Usage toUsage(AnthropicDtos.UsageDto dto) {
        if (dto == null) {
            return new Usage(0, 0, 0, 0, null);
        }
        int input = dto.inputTokens() == null ? 0 : dto.inputTokens();
        int cacheWrite = dto.cacheCreationInputTokens() == null ? 0 : dto.cacheCreationInputTokens();
        int cacheRead = dto.cacheReadInputTokens() == null ? 0 : dto.cacheReadInputTokens();
        int output = dto.outputTokens() == null ? 0 : dto.outputTokens();
        int total = input + cacheWrite + cacheRead + output;
        UsageDetails details = new UsageDetails(
                new InputTokenDetails(cacheRead, cacheWrite, 0),
                new OutputTokenDetails(0, 0));
        return new Usage(input, input, output, total, details);
    }

    private String parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "未知错误";
        }
        try {
            AnthropicDtos.MessageResponse parsed =
                    mapper.readValue(body, AnthropicDtos.MessageResponse.class);
            if (parsed.error() != null && parsed.error().error() != null
                    && parsed.error().error().message() != null) {
                return parsed.error().error().message();
            }
        } catch (JacksonException ignored) {
            // 解析失败退回原文摘要（错误信息必须可见）
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    // ------------------------------------------------------------ 流式聚合器

    /**
     * 流式聚合器：文本 / 思维链 / 工具调用（按 index）/ 结束原因 / <b>两侧 usage 合并</b>。
     *
     * <p><b>合并方向（M6，A10 语料的真实事故）</b>：{@code message_start} 与
     * {@code message_delta} 都带 usage 时，<b>delta 逐字段赢</b>；
     * start 只补 delta 省略的字段。方向反了会拿到「命中 1113、total 2232」的对不上账。
     */
    private static final class Accumulator {

        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        /** 按 index 排序：块序 = index 序。 */
        private final TreeMap<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        /** 两侧合并后的最终 usage（可空字段分别跟踪）。 */
        private Integer inputTokens;
        private Integer cacheWrite;
        private Integer cacheRead;
        private Integer outputTokens;
        private String stopReason;
        private StreamChunk lastEmitted;

        void accept(AnthropicStreamDtos.StreamEvent event) {
            switch (event.type() == null ? "" : event.type()) {
                case "message_start" -> {
                    if (event.message() != null && event.message().usage() != null) {
                        mergeUsage(event.message().usage());
                    }
                    lastEmitted = null;
                }
                case "content_block_start" -> {
                    if (event.contentBlock() != null
                            && "tool_use".equals(event.contentBlock().type())) {
                        int index = event.index() == null ? 0 : event.index();
                        ToolCallBuilder builder = toolCalls.computeIfAbsent(index,
                                k -> new ToolCallBuilder());
                        builder.id = event.contentBlock().id() == null
                                ? builder.id : event.contentBlock().id();
                        builder.name = event.contentBlock().name() == null
                                ? builder.name : event.contentBlock().name();
                    }
                    lastEmitted = null;
                }
                case "content_block_delta" -> {
                    AnthropicStreamDtos.StreamEvent.DeltaBody delta = event.delta();
                    if (delta == null) {
                        lastEmitted = null;
                        return;
                    }
                    String text = "";
                    String think = "";
                    if ("text_delta".equals(delta.type()) && delta.text() != null) {
                        content.append(delta.text());
                        text = delta.text();
                    } else if ("thinking_delta".equals(delta.type()) && delta.thinking() != null) {
                        reasoning.append(delta.thinking());
                        think = delta.thinking();
                    } else if ("input_json_delta".equals(delta.type())
                            && delta.partialJson() != null) {
                        int index = event.index() == null ? 0 : event.index();
                        toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder())
                                .args.append(delta.partialJson());
                    }
                    lastEmitted = text.isEmpty() && think.isEmpty() ? null
                            : new StreamChunk(text.isEmpty() ? null : text,
                            think.isEmpty() ? null : think, null, null);
                }
                case "message_delta" -> {
                    if (event.delta() != null && event.delta().stopReason() != null
                            && !event.delta().stopReason().isEmpty()) {
                        stopReason = event.delta().stopReason();
                    }
                    if (event.usage() != null) {
                        // delta 是最终快照：逐字段赢
                        mergeUsage(event.usage());
                    }
                    lastEmitted = null;
                }
                default -> lastEmitted = null;
            }
        }

        /** 逐字段合并：delta 赢，start 只补 delta 省略的字段。 */
        private void mergeUsage(AnthropicStreamDtos.StreamEvent.UsageSnapshot usage) {
            if (usage.inputTokens() != null) {
                inputTokens = usage.inputTokens();
            }
            if (usage.cacheCreationInputTokens() != null) {
                cacheWrite = usage.cacheCreationInputTokens();
            }
            if (usage.cacheReadInputTokens() != null) {
                cacheRead = usage.cacheReadInputTokens();
            }
            if (usage.outputTokens() != null) {
                outputTokens = usage.outputTokens();
            }
        }

        /** 合并后的最终 usage（Anthropic 口径；两侧都没给 = 零值）。 */
        Usage usage() {
            if (inputTokens == null && cacheWrite == null && cacheRead == null && outputTokens == null) {
                return new Usage(0, 0, 0, 0, null);
            }
            int input = inputTokens == null ? 0 : inputTokens;
            int write = cacheWrite == null ? 0 : cacheWrite;
            int read = cacheRead == null ? 0 : cacheRead;
            int output = outputTokens == null ? 0 : outputTokens;
            UsageDetails details = new UsageDetails(
                    new InputTokenDetails(read, write, 0),
                    new OutputTokenDetails(0, 0));
            return new Usage(input, input, output, input + write + read + output, details);
        }

        /** 流结束后产出完整工具调用列表（index 升序，跳过无名调用）。 */
        List<ToolCall> finishToolCalls() {
            if (toolCalls.isEmpty()) {
                return null;
            }
            List<ToolCall> calls = new ArrayList<>();
            for (ToolCallBuilder builder : toolCalls.values()) {
                if (builder.name.isEmpty()) {
                    continue;
                }
                String args = builder.args.toString();
                calls.add(new ToolCall(builder.id, builder.name,
                        args.isEmpty() ? "{}" : args));
            }
            return calls.isEmpty() ? null : calls;
        }
    }

    /** 单个工具调用的累加状态。 */
    private static final class ToolCallBuilder {
        private String id = "";
        private String name = "";
        private final StringBuilder args = new StringBuilder();
    }
}
