package net.hegq.mos4j.model.decorate;

import net.hegq.mos4j.core.CancellationToken;
import net.hegq.mos4j.model.ChatModel;
import net.hegq.mos4j.model.Request;
import net.hegq.mos4j.model.Response;
import net.hegq.mos4j.model.StreamEmitter;
import net.hegq.mos4j.model.ContentPart;
import net.hegq.mos4j.model.Message;
import net.hegq.mos4j.model.Tool;
import net.hegq.mos4j.model.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地结果缓存装饰器（对齐 Go 参考实现 {@code llm.WithCache}）。
 *
 * <p><b>它是什么</b>：按<b>请求内容</b>缓存<b>非流式</b>结果——同一个请求再发一次，
 * 直接拿上次的结果，不打服务端。
 *
 * <p><b>它不是什么</b>：它<b>不是</b>服务端的 prompt cache（服务端按前缀缓存输入
 * token，计费口径完全不同）。这里缓存的是整次请求的结果对象，命中就完全不调服务端。
 *
 * <p><b>四条语义</b>（与 Go 侧一致）：
 * <ol>
 *   <li><b>只缓存非流式结果</b>：流式调用直接透传（流没法「重放」给第二次订阅者）；</li>
 *   <li><b>{@code ttl <= 0} 表示不过期</b>：条目一直有效（宿主自己管失效）；</li>
 *   <li><b>失败不缓存</b>：模型报错的结果不进缓存（错误不是「答案」）；</li>
 *   <li><b>缓存对象共享只读</b>：命中时返回的是<b>同一个</b>响应对象，调用方不要改它。</li>
 * </ol>
 *
 * <p><b>缓存键</b>：请求的规范化文本（消息、工具、采样参数）取 SHA-256。
 * 键里包含工具清单与全部消息——参数变了就是不同的请求，不会拿旧答案糊弄新问题。
 *
 * <p><b>取消优先于缓存命中</b>：命中时若令牌已取消，照旧抛取消异常——
 * 「叫停」被缓存悄悄吞掉，是比多花一次 token 更糟的事。
 */
public final class CachingChatModel extends ChatModelDecorator {

    /** 分隔符（请求各部分之间）：用不可见字符，避免字段内容里出现同样的串导致歧义。 */
    private static final String SEP = "\0";

    private final long ttlMillis;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * @param delegate 被装饰的模型
     * @param ttl      存活时间（{@code <= 0} = 不过期）
     */
    public CachingChatModel(ChatModel delegate, Duration ttl) {
        super(delegate);
        this.ttlMillis = ttl == null ? 0L : ttl.toMillis();
    }

    @Override
    public Response chat(Request request) {
        return chat(request, CancellationToken.none());
    }

    @Override
    public Response chat(Request request, CancellationToken cancellation) {
        if (cancellation != null) {
            // 取消优先于缓存命中：叫停不能被缓存悄悄吞掉。
            cancellation.throwIfCancelled();
        }
        String key = cacheKey(request);
        Entry entry = lookup(key);
        if (entry != null) {
            return entry.response();
        }
        Response response = delegate.chat(request, cancellation);
        if (response == null) {
            return null;
        }
        entries.put(key, new Entry(response, expiry()));
        return response;
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter) {
        // 流式不缓存：直接透传。
        return delegate.chatStream(request, emitter);
    }

    @Override
    public Response chatStream(Request request, StreamEmitter emitter, CancellationToken cancellation) {
        // 流式不缓存：直接透传。
        return delegate.chatStream(request, emitter, cancellation);
    }

    /** 当前缓存条目数（运维/测试用）。 */
    public int size() {
        return entries.size();
    }

    /** 清空缓存（宿主想强制刷新时用）。 */
    public void clear() {
        entries.clear();
    }

    /** 查一条没过期的缓存；过期即删。 */
    private Entry lookup(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiryMillis() > 0 && System.currentTimeMillis() > entry.expiryMillis()) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    /** 到期时刻（0 = 不过期）。 */
    private long expiry() {
        return ttlMillis <= 0 ? 0L : System.currentTimeMillis() + ttlMillis;
    }

    /** 缓存条目。 */
    private record Entry(Response response, long expiryMillis) {
    }

    /**
     * 缓存键：请求的规范化文本取 SHA-256。
     *
     * <p>刻意不用「消息条数 + 最后一条」这种粗粒度的键——那会让「同一句追问、
     * 不同历史」共用一个答案，模型拿到的是别人的上下文。
     */
    static String cacheKey(Request request) {
        StringBuilder sb = new StringBuilder();
        if (request == null) {
            return sha256("");
        }
        List<Message> messages = request.messages();
        if (messages != null) {
            for (Message message : messages) {
                sb.append(message.role() == null ? "" : message.role().wire()).append(SEP);
                sb.append(nullToEmpty(message.content())).append(SEP);
                sb.append(nullToEmpty(message.reasoningContent())).append(SEP);
                sb.append(nullToEmpty(message.toolCallId())).append(SEP);
                sb.append(nullToEmpty(message.name())).append(SEP);
                List<ContentPart> parts = message.contentParts();
                if (parts != null) {
                    for (ContentPart part : parts) {
                        sb.append(nullToEmpty(part.type())).append(SEP);
                        sb.append(nullToEmpty(part.text())).append(SEP);
                        sb.append(nullToEmpty(part.url())).append(SEP);
                        sb.append(nullToEmpty(part.mimeType())).append(SEP);
                        sb.append(nullToEmpty(part.data())).append(SEP);
                    }
                }
                List<ToolCall> calls = message.toolCalls();
                if (calls != null) {
                    for (ToolCall call : calls) {
                        sb.append(nullToEmpty(call.id())).append(SEP);
                        sb.append(nullToEmpty(call.name())).append(SEP);
                        sb.append(nullToEmpty(call.args())).append(SEP);
                    }
                }
                sb.append(SEP);
            }
        }
        List<Tool> tools = request.tools();
        if (tools != null) {
            for (Tool tool : tools) {
                sb.append(nullToEmpty(tool.name())).append(SEP);
                sb.append(nullToEmpty(tool.description())).append(SEP);
                appendCanonical(sb, tool.parameters());
                sb.append(SEP);
            }
        }
        sb.append(request.temperature() == null ? "" : request.temperature()).append(SEP);
        sb.append(request.maxTokens() == null ? "" : request.maxTokens()).append(SEP);
        sb.append(request.reasoningEffort() == null ? "" : request.reasoningEffort()).append(SEP);
        if (request.responseFormat() != null) {
            sb.append(nullToEmpty(request.responseFormat().type())).append(SEP);
            sb.append(nullToEmpty(request.responseFormat().name())).append(SEP);
            appendCanonical(sb, request.responseFormat().schema());
            sb.append(SEP);
        }
        if (request.modalities() != null) {
            for (String modality : request.modalities()) {
                sb.append(nullToEmpty(modality)).append(SEP);
            }
        }
        return sha256(sb.toString());
    }

    /** 递归展开 schema：键按字典序（与加入顺序无关，避免同一份 schema 算出两个键）。 */
    private static void appendCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            List<String> keys = new java.util.ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            java.util.Collections.sort(keys);
            for (String key : keys) {
                sb.append(key).append(':');
                appendCanonical(sb, map.get(key));
                sb.append(',');
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            for (Object item : iterable) {
                appendCanonical(sb, item);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        sb.append(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** SHA-256 十六进制。 */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法，不可能到这里；真到了说明运行环境坏了，不静默降级成「不缓存」。
            throw new IllegalStateException("llm: SHA-256 不可用（缓存键无法计算）", e);
        }
    }
}
