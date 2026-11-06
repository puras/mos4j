package net.hegq.mos4j.llm.protocol.openai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
import net.hegq.mos4j.llm.EmptyResponseException;
import net.hegq.mos4j.llm.Request;
import net.hegq.mos4j.llm.Response;
import net.hegq.mos4j.llm.StatusError;
import net.hegq.mos4j.llm.StreamAbortedException;
import net.hegq.mos4j.llm.StreamChunk;
import net.hegq.mos4j.llm.StreamEmitter;
import net.hegq.mos4j.llm.StreamTruncatedException;
import net.hegq.mos4j.llm.model.ContentPart;
import net.hegq.mos4j.llm.model.InputTokenDetails;
import net.hegq.mos4j.llm.model.Message;
import net.hegq.mos4j.llm.model.OutputTokenDetails;
import net.hegq.mos4j.llm.model.Role;
import net.hegq.mos4j.llm.model.ToolCall;
import net.hegq.mos4j.llm.model.Usage;
import net.hegq.mos4j.llm.model.UsageDetails;
import net.hegq.mos4j.llm.transport.HttpTransport;
import net.hegq.mos4j.llm.transport.SseLineReader;

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
 * OpenAI 兼容协议的聊天适配器（DeepSeek / 通义 / GLM / Ollama 等同协议服务共用）。
 *
 * <p><b>本类只做「协议映射」</b>：统一模型 ⇄ 线格式。传输、鉴权、超时由
 * {@link HttpTransport} 负责；用量口径由 {@link #toUsage} 统一。</p>
 *
 * <p><b>关键语义（与 Go 侧一致，不得擅改）</b>：</p>
 * <ul>
 *   <li>流式 {@code tool_calls} 按 {@code index} 分片累加（机制：按 index 聚合）；
 *       参数原文拼接，<b>不解析</b>（载荷冻结）。</li>
 *   <li>流完整性：<b>{@code [DONE]} 或 {@code finish_reason} 任一到即完整</b>；
 *       两者皆无才是截断（机制 M5）。</li>
 *   <li>推理内容：{@code reasoning_content} 优先，{@code reasoning} 兜底。</li>
 *   <li>空响应（无 choices）是瞬时故障，抛 {@link EmptyResponseException} 让重试层认出。</li>
 * </ul>
 */
public final class OpenAiChatModel implements ChatModel {

    /** 提供方标识（错误文本前缀）。 */
    public static final String PROVIDER = "openai";

    private final OpenAiConfig config;
    private final HttpTransport transport;
    private final ObjectMapper mapper;

    public OpenAiChatModel(OpenAiConfig config) {
        this(config, new HttpTransport(config == null ? null : config.normalized().timeout()));
    }

    public OpenAiChatModel(OpenAiConfig config, HttpTransport transport) {
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
                    transport.send(config.chatCompletionsUrl(), body, config.headers(), false, cancellation);
            OpenAiDtos.ChatCompletionResponse parsed;
            try (InputStream in = raw.body()) {
                parsed = mapper.readValue(in, OpenAiDtos.ChatCompletionResponse.class);
            }
            if (parsed.error() != null && parsed.error().message() != null) {
                throw new StatusError(PROVIDER, 0, errorText(parsed.error()));
            }
            if (parsed.choices() == null || parsed.choices().isEmpty()) {
                // 空响应是瞬时故障（网关抖动），值得重试——用哨兵让重试层能认出来。
                throw new EmptyResponseException();
            }
            OpenAiDtos.Choice choice = parsed.choices().get(0);
            Message message = toMessage(choice.message());
            // 注：Go 侧非流式路径没有回填 FinishReason；Java 侧补上（只多不少，不减轻错误）。
            String finishReason = choice.finishReason();
            return new Response(message, toUsage(parsed.usage()), finishReason);
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("openai: 请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("openai: 请求失败", e);
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
        boolean sawDone = false;
        try {
            String body = mapper.writeValueAsString(buildRequest(request, true));
            HttpTransport.HttpResponseBody raw =
                    transport.send(config.chatCompletionsUrl(), body, config.headers(), true, cancellation);
            try (InputStream in = raw.body()) {
                final boolean[] done = {false};
                SseLineReader.read(in, line -> {
                    cancellation.throwIfCancelled();
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        return true;
                    }
                    String payload = trimmed.substring("data:".length()).trim();
                    if (payload.isEmpty()) {
                        // 空 data 行 = 流的结束（与 Go 侧 evt.Chunk 为空时 break 一致）
                        done[0] = true;
                        return false;
                    }
                    if ("[DONE]".equals(payload)) {
                        // [DONE] 是流的合法结束标记（有些网关只发 [DONE]、不带 finish_reason）。
                        done[0] = true;
                        return false;
                    }
                    OpenAiStreamDtos.ChatCompletionChunk chunk =
                            mapper.readValue(payload, OpenAiStreamDtos.ChatCompletionChunk.class);
                    acc.accept(chunk);
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
                sawDone = sawDone || done[0];
            }
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("openai: 流式请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("openai: 流式请求失败", e);
        }
        // 流完整性：结束标记（[DONE]）或结束原因（finish_reason）任一到即算完整。
        // 两者都没有 = 流被掐断（连接断开/服务端异常）——半截文本 + 残缺工具调用
        // 不能当正常响应返回。
        if (acc.finishReason == null || acc.finishReason.isEmpty()) {
            if (!sawDone) {
                throw new StreamTruncatedException();
            }
        }
        Message message = new Message(Role.ASSISTANT, acc.content.toString(),
                null, acc.finishToolCalls(), null, null,
                acc.reasoning.length() > 0 ? acc.reasoning.toString() : null);
        return new Response(message, toUsage(acc.usage), acc.finishReason);
    }

    // -------------------------------------------------------------- 请求构造

    private OpenAiDtos.ChatCompletionRequest buildRequest(Request request, boolean stream) {
        List<OpenAiDtos.MessageParam> messages = new ArrayList<>();
        for (Message message : request.messages()) {
            messages.add(toMessageParam(message));
        }
        List<OpenAiDtos.ToolParam> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            tools = new ArrayList<>();
            for (var tool : request.tools()) {
                tools.add(new OpenAiDtos.ToolParam("function", new OpenAiDtos.ToolFunction(
                        tool.name(), tool.description(), tool.parameters())));
            }
        }
        OpenAiDtos.StreamOptions streamOptions = stream
                ? new OpenAiDtos.StreamOptions(Boolean.TRUE)
                : null;
        return new OpenAiDtos.ChatCompletionRequest(
                config.model(),
                messages,
                tools,
                request.temperature(),
                request.maxTokens(),
                Boolean.valueOf(stream),
                streamOptions,
                request.modalities(),
                request.reasoningEffort(),
                toResponseFormat(request.responseFormat()));
    }

    private OpenAiDtos.MessageParam toMessageParam(Message message) {
        List<OpenAiDtos.ToolCallParam> toolCalls = null;
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            toolCalls = new ArrayList<>();
            for (ToolCall call : message.toolCalls()) {
                toolCalls.add(new OpenAiDtos.ToolCallParam(call.id(), "function",
                        new OpenAiDtos.FunctionParam(call.name(), call.args())));
            }
        }
        return new OpenAiDtos.MessageParam(
                message.role() == null ? Role.USER.wire() : message.role().wire(),
                toContentValue(message),
                toolCalls,
                message.toolCallId(),
                message.name());
    }

    /** 内容映射：多模态展开成 content 数组，否则用字符串。 */
    private Object toContentValue(Message message) {
        List<ContentPart> parts = message.contentParts();
        if (parts == null || parts.isEmpty()) {
            return message.content() == null ? "" : message.content();
        }
        List<Map<String, Object>> array = new ArrayList<>();
        for (ContentPart part : parts) {
            switch (part.type() == null ? "" : part.type()) {
                case "text" -> array.add(Map.of("type", "text", "text", part.text() == null ? "" : part.text()));
                case "image" -> {
                    Map<String, Object> url = new LinkedHashMap<>();
                    url.put("url", part.url() != null ? part.url()
                            : "data:" + (part.mimeType() == null ? "" : part.mimeType()) + ";base64,"
                            + (part.data() == null ? "" : part.data()));
                    array.add(Map.of("type", "image_url", "image_url", url));
                }
                case "audio" -> array.add(audioPart(part));
                default -> array.add(Map.of("type", part.type()));
            }
        }
        return array;
    }

    /**
     * 音频块：OpenAI 协议用 {@code input_audio}（只收 base64 数据，不收 URL）。
     *
     * <p>把音频当图片发（{@code image_url}）是看着能发、实则被服务端拒或静默错解；
     * 这里宁可当场报错，也不发一个语义不对的请求。</p>
     */
    private static Map<String, Object> audioPart(ContentPart part) {
        if (part.data() == null || part.data().isEmpty()) {
            throw new IllegalArgumentException(
                    "openai: 音频内容只支持 base64 数据（input_audio 不接受 URL）");
        }
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("data", part.data());
        audio.put("format", audioFormat(part.mimeType()));
        return Map.of("type", "input_audio", "input_audio", audio);
    }

    /** 从 MIME 类型推音频格式（{@code audio/wav} → {@code wav}）。 */
    private static String audioFormat(String mimeType) {
        if (mimeType == null || !mimeType.contains("/")) {
            return "wav";
        }
        String subtype = mimeType.substring(mimeType.indexOf('/') + 1);
        int semi = subtype.indexOf(';');
        if (semi >= 0) {
            subtype = subtype.substring(0, semi);
        }
        return subtype.isEmpty() ? "wav" : subtype;
    }

    private Map<String, Object> toResponseFormat(Request.ResponseFormat format) {
        if (format == null) {
            return null;
        }
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", format.name() == null ? "response" : format.name());
        jsonSchema.put("schema", format.schema());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", format.type());
        if ("json_schema".equals(format.type())) {
            out.put("json_schema", jsonSchema);
        }
        return out;
    }

    // -------------------------------------------------------------- 响应映射

    private Message toMessage(OpenAiDtos.MessageDto dto) {
        if (dto == null) {
            return new Message(Role.ASSISTANT, "", null, null, null, null, null);
        }
        String reasoning = dto.reasoningContent() != null && !dto.reasoningContent().isEmpty()
                ? dto.reasoningContent()
                : dto.reasoning();
        List<ToolCall> toolCalls = null;
        if (dto.toolCalls() != null && !dto.toolCalls().isEmpty()) {
            toolCalls = new ArrayList<>();
            for (OpenAiDtos.ToolCallDto call : dto.toolCalls()) {
                String name = call.function() == null ? null : call.function().name();
                String args = call.function() == null ? null : call.function().arguments();
                toolCalls.add(new ToolCall(call.id(), name, args));
            }
        }
        Role role = Role.fromWire(dto.role());
        return new Message(role == null ? Role.ASSISTANT : role, dto.content(), null, toolCalls,
                null, null, reasoning);
    }

    /**
     * 用量归一化（机制 M6：用量统一在 FreshInputTokens 口径）。
     *
     * <p><b>OpenAI 口径</b>：{@code prompt_tokens} <b>含</b>缓存命中，
     * 所以去缓存输入 = {@code prompt_tokens - cached_tokens}；
     * {@code total_tokens} = prompt + completion（含缓存命中）。</p>
     *
     * <p>不这么做会出什么事：直接拿 prompt_tokens 当「新鲜输入」跨协议比较，
     * 同一个百分比在 OpenAI 与 Anthropic 下含义完全不同（Anthropic 的 input 不含缓存），
     * 缓存策略和成本估算跟着错。</p>
     */
    static Usage toUsage(OpenAiDtos.UsageDto dto) {
        if (dto == null) {
            return new Usage(0, 0, 0, 0, null);
        }
        int prompt = dto.promptTokens() == null ? 0 : dto.promptTokens();
        int completion = dto.completionTokens() == null ? 0 : dto.completionTokens();
        int total = dto.totalTokens() == null ? prompt + completion : dto.totalTokens();
        int cached = dto.promptTokensDetails() == null || dto.promptTokensDetails().cachedTokens() == null
                ? 0 : dto.promptTokensDetails().cachedTokens();
        int fresh = Math.max(prompt - cached, 0);
        int reasoning = dto.completionTokensDetails() == null || dto.completionTokensDetails().reasoningTokens() == null
                ? 0 : dto.completionTokensDetails().reasoningTokens();
        UsageDetails details = new UsageDetails(
                new InputTokenDetails(cached, 0, 0),
                new OutputTokenDetails(reasoning, 0));
        return new Usage(prompt, fresh, completion, total, details);
    }

    private static String errorText(OpenAiDtos.ErrorDto error) {
        String code = error.code() == null ? "" : error.code();
        return code.isEmpty() ? error.message() : error.message() + " (" + code + ")";
    }

    /** 从错误响应体里解析可读信息（解析不出就用原文摘要）。 */
    private String parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "未知错误";
        }
        try {
            OpenAiDtos.ChatCompletionResponse parsed =
                    mapper.readValue(body, OpenAiDtos.ChatCompletionResponse.class);
            if (parsed.error() != null && parsed.error().message() != null) {
                return errorText(parsed.error());
            }
        } catch (JacksonException ignored) {
            // 解析失败不是致命问题：退回原文摘要（错误信息必须可见，不能吞）
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    // ------------------------------------------------------------ 流式聚合器

    /** 流式聚合器：文本、推理、工具调用（按 index）、用量、结束原因。 */
    private static final class Accumulator {

        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        /** 按 index 排序（TreeMap 保证输出顺序 = index 顺序）。 */
        private final TreeMap<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        private OpenAiDtos.UsageDto usage;
        private String finishReason;
        private StreamChunk lastEmitted;

        void accept(OpenAiStreamDtos.ChatCompletionChunk chunk) {
            if (chunk.usage() != null) {
                this.usage = chunk.usage();
            }
            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                return;
            }
            OpenAiStreamDtos.ChunkChoice choice = chunk.choices().get(0);
            if (choice.finishReason() != null && !choice.finishReason().isEmpty()) {
                this.finishReason = choice.finishReason();
            }
            OpenAiStreamDtos.Delta delta = choice.delta();
            if (delta == null) {
                delta = new OpenAiStreamDtos.Delta(null, null, null, null, null);
            }
            if (delta.content() != null) {
                content.append(delta.content());
            }
            // reasoning_content（DeepSeek/Qwen 扩展）优先，reasoning 兜底。
            String reasoningDelta = delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()
                    ? delta.reasoningContent()
                    : delta.reasoning();
            if (reasoningDelta != null) {
                reasoning.append(reasoningDelta);
            }
            mergeToolCalls(delta.toolCalls());
            String finish = choice.finishReason();
            String text = delta.content() == null ? "" : delta.content();
            String think = reasoningDelta == null ? "" : reasoningDelta;
            if (!text.isEmpty() || !think.isEmpty()) {
                lastEmitted = new StreamChunk(text.isEmpty() ? null : text,
                        think.isEmpty() ? null : think, null, finish);
            } else {
                lastEmitted = null;
            }
        }

        private void mergeToolCalls(List<OpenAiStreamDtos.ToolCallDelta> deltas) {
            if (deltas == null) {
                return;
            }
            for (OpenAiStreamDtos.ToolCallDelta delta : deltas) {
                int index = delta.index() == null ? 0 : delta.index();
                ToolCallBuilder builder = toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
                if (delta.id() != null && !delta.id().isEmpty()) {
                    builder.id = delta.id();
                }
                if (delta.function() != null) {
                    if (delta.function().name() != null && !delta.function().name().isEmpty()) {
                        builder.name = delta.function().name();
                    }
                    if (delta.function().arguments() != null && !delta.function().arguments().isEmpty()) {
                        builder.args.append(delta.function().arguments());
                    }
                }
            }
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
                calls.add(new ToolCall(builder.id, builder.name, builder.args.toString()));
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
