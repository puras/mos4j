package net.hegq.mos4j.llm.protocol.gemini;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.llm.Capability;
import net.hegq.mos4j.llm.ChatModel;
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

/**
 * Gemini GenerateContent 协议适配器（T6.3）。
 *
 * <p><b>关键语义（不得擅改）</b>：</p>
 * <ul>
 *   <li><b>结束判定（M5）</b>：{@code finishReason} 是结束标记——语义比 OpenAI/Anthropic
 *       更严（有 finishReason 即完整；缺省 {@code require_finish=true} 时无 finishReason
 *       即使流正常读完也按截断报错）。{@code require_finish} 开关可关：
 *       关掉后流读完即完整（g5b 语料），适配「上游网关丢 finishReason」的现实。</li>
 *   <li><b>只取 candidates[0]</b>，多候选边界场景丢弃其余（G6）。</li>
 *   <li><b>functionCall.args 已是完整 JSON 对象</b>：直接序列化成字符串放进
 *       {@link ToolCall#args()}（不拼分片）。</li>
 *   <li><b>usage 直读</b>：prompt/completion/total 直接映射，details 放缓存读。</li>
 * </ul>
 */
public final class GeminiChatModel implements ChatModel {

    /** 提供方标识（错误文本前缀）。 */
    public static final String PROVIDER = "gemini";

    /** 缺省开关值：流结束必须有 finishReason（比 OpenAI/Anthropic 严）。 */
    public static final boolean DEFAULT_REQUIRE_FINISH = true;

    private final GeminiConfig config;
    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final boolean requireFinish;

    public GeminiChatModel(GeminiConfig config) {
        this(config, DEFAULT_REQUIRE_FINISH);
    }

    public GeminiChatModel(GeminiConfig config, boolean requireFinish) {
        this(config, requireFinish, new HttpTransport(config == null ? null : config.normalized().timeout()));
    }

    public GeminiChatModel(GeminiConfig config, boolean requireFinish, HttpTransport transport) {
        this.config = config.normalized();
        this.transport = transport;
        this.requireFinish = requireFinish;
        this.mapper = new ObjectMapper();
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.FUNCTION_CALLING, Capability.STREAMING,
                Capability.STRUCTURED_OUTPUT, Capability.MULTIMODAL);
    }

    // ---------------------------------------------------------------- 非流式

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        try {
            String body = mapper.writeValueAsString(buildRequest(request));
            HttpTransport.HttpResponseBody raw =
                    transport.send(config.generateUrl(), body, config.headers(), false, cancellation);
            GeminiDtos.GenerateContentResponse parsed;
            try (InputStream in = raw.body()) {
                parsed = mapper.readValue(in, GeminiDtos.GenerateContentResponse.class);
            }
            return toResponse(parsed);
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("gemini: 请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("gemini: 请求失败", e);
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
        final boolean[] finished = {false};
        try {
            String body = mapper.writeValueAsString(buildRequest(request));
            HttpTransport.HttpResponseBody raw =
                    transport.send(config.streamUrl(), body, config.headers(), true, cancellation);
            try (InputStream in = raw.body()) {
                SseLineReader.read(in, line -> {
                    cancellation.throwIfCancelled();
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        return true;
                    }
                    String payload = trimmed.substring("data:".length()).trim();
                    if (payload.isEmpty()) {
                        // Gemini 无终止哨兵（不发 [DONE]）：空 data 行跳过，读到 EOF 为止
                        return true;
                    }
                    GeminiDtos.GenerateContentResponse chunk =
                            mapper.readValue(payload, GeminiDtos.GenerateContentResponse.class);
                    acc.accept(chunk);
                    if (chunk.candidates() != null && !chunk.candidates().isEmpty()
                            && chunk.candidates().get(0).finishReason() != null
                            && !chunk.candidates().get(0).finishReason().isEmpty()) {
                        // 记下「已见到结束原因」但不停止：usageMetadata 常在
                        // finishReason 之后的 data 行里到达（g1 语料即如此）
                        finished[0] = true;
                    }
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
            }
        } catch (HttpTransport.TransportStatusException e) {
            throw new StatusError(PROVIDER, e.statusCode(), parseErrorBody(e.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("gemini: 流式请求被中断");
        } catch (IOException e) {
            throw new UncheckedIOException("gemini: 流式请求失败", e);
        }

        // M5（Gemini 侧）：finishReason 即结束标记，比 OpenAI/Anthropic 更严——
        // require_finish=true（缺省）时流读完仍无 finishReason = 截断（g5a）；
        // 开关关掉后流读完即完整（g5b，容忍上游网关丢 finishReason）。
        if (!finished[0] && requireFinish) {
            throw new StreamTruncatedException();
        }
        return acc.toResponse();
    }

    // -------------------------------------------------------------- 响应归一

    /** 协议响应 → 统一四元组。 */
    private Response toResponse(GeminiDtos.GenerateContentResponse parsed) {
        String content = "";
        List<ToolCall> toolCalls = null;
        String finishReason = null;
        if (parsed.candidates() != null && !parsed.candidates().isEmpty()) {
            GeminiDtos.Candidate candidate = parsed.candidates().get(0);
            finishReason = candidate.finishReason();
            if (candidate.content() != null && candidate.content().parts() != null) {
                StringBuilder text = new StringBuilder();
                List<ToolCall> calls = new ArrayList<>();
                for (GeminiDtos.Part part : candidate.content().parts()) {
                    if (part.text() != null) {
                        text.append(part.text());
                    } else if (part.functionCall() != null) {
                        GeminiDtos.FunctionCall call = part.functionCall();
                        String args;
                        try {
                            args = call.args() == null ? "{}" : mapper.writeValueAsString(call.args());
                        } catch (JacksonException e) {
                            throw new IllegalStateException("gemini: 序列化工具参数失败", e);
                        }
                        calls.add(new ToolCall(call.id() == null ? "" : call.id(),
                                call.name() == null ? "" : call.name(), args));
                    }
                    // 未知 part 类型忽略
                }
                content = text.toString();
                if (!calls.isEmpty()) {
                    toolCalls = calls;
                }
            }
        } else if (parsed.promptFeedback() != null && parsed.promptFeedback().blockReason() != null) {
            throw new StatusError(PROVIDER, 0, "请求被拦截: " + parsed.promptFeedback().blockReason());
        }
        Message message = new Message(Role.ASSISTANT, content, null, toolCalls,
                null, null, null);
        return new Response(message, toUsage(parsed.usageMetadata()), finishReason);
    }

    /** 用量直读（Gemini 口径：prompt 含缓存读，freshInput = prompt - cached）。 */
    static Usage toUsage(GeminiDtos.UsageMetadata dto) {
        if (dto == null) {
            return new Usage(0, 0, 0, 0, null);
        }
        int prompt = dto.promptTokenCount() == null ? 0 : dto.promptTokenCount();
        int completion = dto.candidatesTokenCount() == null ? 0 : dto.candidatesTokenCount();
        int total = dto.totalTokenCount() == null ? (prompt + completion) : dto.totalTokenCount();
        int cached = dto.cachedContentTokenCount() == null ? 0 : dto.cachedContentTokenCount();
        int thoughts = dto.thoughtsTokenCount() == null ? 0 : dto.thoughtsTokenCount();
        UsageDetails details = new UsageDetails(
                new InputTokenDetails(cached, 0, 0),
                new OutputTokenDetails(thoughts > 0 ? thoughts : 0, 0));
        return new Usage(prompt, Math.max(0, prompt - cached), completion, total, details);
    }

    private String parseErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "未知错误";
        }
        try {
            GeminiDtos.GenerateContentResponse parsed =
                    mapper.readValue(body, GeminiDtos.GenerateContentResponse.class);
            if (parsed.promptFeedback() != null && parsed.promptFeedback().blockReason() != null) {
                return "请求被拦截: " + parsed.promptFeedback().blockReason();
            }
        } catch (JacksonException ignored) {
            // 解析失败退回原文摘要（错误信息必须可见）
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    // -------------------------------------------------------------- 请求构造

    private GeminiDtos.GenerateContentRequest buildRequest(Request request) {
        List<GeminiDtos.Content> contents = new ArrayList<>();
        GeminiDtos.Content system = null;
        for (Message message : request.messages()) {
            if (message.role() == Role.SYSTEM) {
                system = new GeminiDtos.Content("user",
                        List.of(GeminiDtos.Part.ofText(message.content() == null ? "" : message.content())));
                continue;
            }
            String role = message.role() == Role.ASSISTANT ? "model" : "user";
            contents.add(new GeminiDtos.Content(role, partsOf(message)));
        }
        List<GeminiDtos.ToolDeclaration> tools = null;
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<GeminiDtos.FunctionDeclaration> decls = new ArrayList<>();
            for (var tool : request.tools()) {
                decls.add(new GeminiDtos.FunctionDeclaration(tool.name(), tool.description(),
                        tool.parameters()));
            }
            tools = List.of(new GeminiDtos.ToolDeclaration(decls));
        }
        GeminiDtos.GenerationConfig generationConfig =
                new GeminiDtos.GenerationConfig(request.temperature(), request.maxTokens());
        return new GeminiDtos.GenerateContentRequest(contents, system, tools, generationConfig);
    }

    /** 消息 → parts（文本 / 图片 / 工具回执 / 工具调用）。 */
    private List<GeminiDtos.Part> partsOf(Message message) {
        List<GeminiDtos.Part> parts = new ArrayList<>();
        List<ContentPart> contentParts = message.contentParts();
        if (contentParts != null && !contentParts.isEmpty()) {
            for (ContentPart part : contentParts) {
                if ("text".equals(part.type())) {
                    parts.add(GeminiDtos.Part.ofText(part.text() == null ? "" : part.text()));
                } else if ("image".equals(part.type())) {
                    parts.add(GeminiDtos.Part.ofImage(
                            part.mimeType() == null ? "image/png" : part.mimeType(),
                            part.data() == null ? "" : part.data()));
                }
            }
        }
        // 工具回执：functionResponse part（response 是对象；文本结果包在 result 里）
        if (message.toolCallId() != null && !message.toolCallId().isEmpty()) {
            String name = message.toolCallId();
            parts.add(GeminiDtos.Part.ofFunctionResponse(name,
                    Map.of("result", message.content() == null ? "" : message.content())));
        }
        // assistant 的工具调用：functionCall part（args 反序列化回对象）
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            for (ToolCall call : message.toolCalls()) {
                Map<String, Object> args;
                try {
                    args = call.args() == null || call.args().isEmpty()
                            ? Map.of() : mapper.readValue(call.args(), Map.class);
                } catch (JacksonException e) {
                    throw new IllegalArgumentException("gemini: 工具调用参数不是合法 JSON: " + call.id());
                }
                parts.add(GeminiDtos.Part.ofFunctionCall(
                        new GeminiDtos.FunctionCall(call.id(), call.name(), args)));
            }
        }
        if (parts.isEmpty() && message.content() != null && !message.content().isEmpty()) {
            parts.add(GeminiDtos.Part.ofText(message.content()));
        }
        return parts;
    }

    // ------------------------------------------------------------ 流式聚合器

    /** 流式聚合器：parts 拼接、finishReason、usage 直读。 */
    private final class Accumulator {

        private final StringBuilder content = new StringBuilder();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final Map<String, ToolCall> toolCallsById = new LinkedHashMap<>();
        private Integer prompt;
        private Integer completion;
        private Integer total;
        private Integer cached;
        private Integer thoughts;
        private String finishReason;
        private StreamChunk lastEmitted;

        void accept(GeminiDtos.GenerateContentResponse chunk) {
            // 每个只产生至多一个发射块；无内容/usage-only 的 chunk 不能把上一个块再发一遍
            lastEmitted = null;
            if (chunk.candidates() != null && !chunk.candidates().isEmpty()) {
                GeminiDtos.Candidate candidate = chunk.candidates().get(0);
                if (candidate.finishReason() != null && !candidate.finishReason().isEmpty()) {
                    finishReason = candidate.finishReason();
                }
                if (candidate.content() != null && candidate.content().parts() != null) {
                    StringBuilder text = new StringBuilder();
                    for (GeminiDtos.Part part : candidate.content().parts()) {
                        if (part.text() != null) {
                            content.append(part.text());
                            text.append(part.text());
                        } else if (part.functionCall() != null) {
                            GeminiDtos.FunctionCall call = part.functionCall();
                            String args;
                            try {
                                args = call.args() == null ? "{}"
                                        : mapper.writeValueAsString(call.args());
                            } catch (JacksonException e) {
                                throw new IllegalStateException("gemini: 序列化工具参数失败", e);
                            }
                            // 同 id 的 functionCall 跨 chunk 重复（g2 语料：先 {} 再全量）——
                            // 按 id 合并，后到的 args 覆盖（最后一块是最终完整对象）
                            ToolCall incoming = new ToolCall(call.id() == null ? "" : call.id(),
                                    call.name() == null ? "" : call.name(), args);
                            ToolCall existing = toolCallsById.get(incoming.id());
                            if (existing != null) {
                                toolCalls.set(toolCalls.indexOf(existing), incoming);
                            } else {
                                toolCalls.add(incoming);
                                toolCallsById.put(incoming.id(), incoming);
                            }
                        }
                    }
                    lastEmitted = text.isEmpty()
                            ? (finishReason == null ? null
                            : new StreamChunk(null, null, null, finishReason))
                            : new StreamChunk(text.toString(), null, null,
                            finishReason);
                }
            }
            if (chunk.usageMetadata() != null) {
                GeminiDtos.UsageMetadata usage = chunk.usageMetadata();
                // 直读：chunk 间覆盖（快照语义，last wins）
                if (usage.promptTokenCount() != null) {
                    prompt = usage.promptTokenCount();
                }
                if (usage.candidatesTokenCount() != null) {
                    completion = usage.candidatesTokenCount();
                }
                if (usage.totalTokenCount() != null) {
                    total = usage.totalTokenCount();
                }
                if (usage.cachedContentTokenCount() != null) {
                    cached = usage.cachedContentTokenCount();
                }
                if (usage.thoughtsTokenCount() != null) {
                    thoughts = usage.thoughtsTokenCount();
                }
            }
        }

        /** 流结束后产出统一四元组（聚合态直接组装，不走单块归一路径）。 */
        Response toResponse() {
            Message message = new Message(Role.ASSISTANT, content.toString(), null,
                    toolCalls.isEmpty() ? null : List.copyOf(toolCalls), null, null, null);
            GeminiDtos.UsageMetadata usage = usageSnapshot();
            boolean noUsage = usage.promptTokenCount() == null && usage.candidatesTokenCount() == null
                    && usage.totalTokenCount() == null;
            return new Response(message, noUsage ? new Usage(0, 0, 0, 0, null) : toUsage(usage),
                    finishReason);
        }

        /** 聚合的 usage（各字段 last wins 后组装）。 */
        GeminiDtos.UsageMetadata usageSnapshot() {
            return new GeminiDtos.UsageMetadata(prompt, completion, total, cached, thoughts);
        }
    }
}
