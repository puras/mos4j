# API — MoS4J 接口契约（J0 · D3）

> **地位**：事件字段名表 + 关键类型签名 + 文案表。字段名的权威基线是 [`fieldnames.json`](fieldnames.json)（T0.4 冻结清单）；本文是人读视图，两者冲突时以 JSON 清单为准并修本文。
> **状态**：冻结。字段名变更 = 事件契约变更，必须两人评审。

---

## 1. 事件契约（`mos4j-session`）

### 1.1 事件种类（EventKind）

| Kind | 载荷字段 | 说明 |
|---|---|---|
| `turn/start` | — | 回合开始 |
| `turn/end` | `reason`、`error` | 回合结束；`error` 是失败详情（分类之外的诊断，只进日志不进投影，上限 `MaxErrorDetail=4096`，按 UTF-8 边界截断） |
| `user` | `text` | 用户输入 |
| `assistant` | `text`、`reasoning`、`toolCalls`、`usage` | 模型输出；`reasoning` 为思维链（未订阅零开销，I6） |
| `tool/call` | `toolCall` | 模型请求调用工具 |
| `tool/result` | `toolResult` | 工具执行回填 |
| `compaction` | `compaction` | 压缩事件（旧历史被遮蔽，不改写日志） |
| `approval/asked` | `approval` | 审批请求（**不走预算制**，PRD 点名全量落日志） |
| `approval/decided` | `approval` | 审批决定（**不走预算制**） |
| `skill/activate` | `skill` | 技能被加载进上下文（J7）；**只记元数据**（`name`/`description`/`dir`），正文已在 `tool/result` 里；**不走预算制**、**不进投影** |
| `skill/allowed-tools-mismatch` | `skill` | 技能声明的 `allowed-tools` 当前不可用（J7）；`missingAllowed` 为求交差集；**只诊断、不改变工具面** |

### 1.2 Event 字段名表（与 `fieldnames.json` 对齐，照抄 Go camelCase 混用不对称性）

| Java 字段 | JSON 键 | 说明 |
|---|---|---|
| `seq` | `seq` | 全局递增从 1，Session 统一分配；0 时省略（`NON_DEFAULT` 口径） |
| `kind` | `kind` | 事件种类 |
| `turn` | `turn` | 回合序号，`turn=0` 时省略 |
| `time` | `time` | 事件时刻（Instant） |
| `text` | `text` | 文本载荷 |
| `reasoning` | `reasoning` | 思维链载荷 |
| `toolCalls` | `toolCalls` | assistant 批量工具调用 |
| `toolCall` | `toolCall` | 单个工具调用（tool/call 载荷） |
| `toolResult` | `toolResult` | 工具结果（tool/result 载荷） |
| `usage` | `usage` | 模型调用遥测 |
| `reason` | `reason` | turn/end 理由 |
| `approval` | `approval` | 审批载荷 |
| `skill` | `skill` | 技能载荷（`name` 无条件；`description`/`dir`/`missingAllowed` 空则省略） |
| `compaction` | `compaction` | 压缩载荷 |
| `error` | `error` | turn/end 失败详情（不进投影） |

### 1.3 载荷类型字段名

**ToolCall**（`net.hegq.mos4j.model.model`）：

| Java 字段 | JSON 键 | 说明 |
|---|---|---|
| `id` | `id` | 调用标识；载荷冻结的比对字段之一 |
| `name` | `name` | 工具名 |
| `args` | `args` | **String 原始 JSON 文本**，不解析成对象（M1 载荷冻结要求逐字比对） |

**ToolResult**：`callId` / `content` / `isError`（boolean，`false` 省略——「日志与协议同形」）。

**Approval**：`id` / `toolName` / `callId`(omitempty) / `reason`(omitempty, asked 才有) / `outcome`(omitempty, decided 才有：`approved`/`rejected`/`cancelled`/`unavailable`)。

**Compaction**：`summary` / `firstKeptSeq` / `tokensBefore`(omitempty)。

**Usage**：`prompt_tokens` / `fresh_input_tokens`(omitempty) / `completion_tokens` / `total_tokens` / `details`(omitempty)。**口径**：`promptTokens` 是协议原始值（含不含缓存随协议），跨协议比较一律 `freshInput()`（M6）。

**UsageDetails**：`input`(omitempty) / `output`(omitempty)；InputTokenDetails：`cache_read` / `cache_write` / `audio`（均 omitempty）；OutputTokenDetails：`reasoning` / `audio`（均 omitempty）。空 details 省略规则按 Go（Jackson 对 POJO 的 `NON_EMPTY` 不生效 → 手写 Serializer）。

**ContentPart**：`type` / `text` / `url` / `data` / `mime_type`（均部分 omitempty）。**Message**（投影消息）：`role` / `content` / `content_parts` / `tool_calls` / `tool_call_id` / `name` / `reasoning_content`。

完整基线见 `fieldnames.json`（**81 条 tag / 72 个唯一字段名**，sha256 `ee43c9ddf6a9cf0c3242d38f42970e3caa1d048441ba19c7f41bb4238042c8cc`；J7 并入 `skill`/`name`/`description`/`dir`/`missingAllowed` 5 条，2026-11-15 并入 `finishReason` 1 条）。

---

## 2. Session 公开签名（`mos4j-session`）

```java
public final class Session {
    public Event append(Event ev)                 // 唯一写入口；seq 统一分配（I2 append-only）
    public List<Event> events()                   // 全量只读视图
    public List<Message> messages()               // 服务商投影（M4 自愈：孤儿 tool/call 补合成中断回执）
    public List<SeqMessage> view()                // 带 seq 的投影视图（消费端定位用）
    public int contextTokens()                    // 当前上下文 token 估算（I4 预算）
    public int nextTurn()                         // 下一回合号
    public Usage totalUsage()                     // 会话累计用量（含压缩调用用量折叠）
}
```

---

## 3. Guard / Hook 契约（`mos4j-tool`，J2 已落地）

Go 侧 `Decision` / `HookEvent` 无 json tag（进程内值对象，不落日志）→ Java 同样走构造器/record，字段名不进本清单。**双轨制（I5）**：`GuardEvent` 与 `HookEvent` 是**两个不同类型**，公开 API 里不得互相出现（`DualTrackStructureTest` 守护）。

```java
// —— 守卫：安全边界。纯判定、无副作用假设、每个调用只判定一次 —— //
public record GuardEvent(String toolName, String args, String callId) {}
public record GuardDecision(Verdict verdict, String reason) {
    public enum Verdict { ALLOW, DENY, ASK, CANCEL }
    public static GuardDecision allow();
    public static GuardDecision deny(String reason);
    public static GuardDecision ask(String reason);
    public static GuardDecision cancel(String reason);
    public boolean permits();               // 只 ALLOW 为 true
}
@FunctionalInterface
public interface Guard {
    GuardDecision check(GuardEvent event);  // null 判定 = 拒绝（fail-closed）
}
public final class GuardBus {               // 串行、单调
    public GuardBus register(Guard guard);
    public int size();
    public GuardDecision check(GuardEvent event);   // 首个 DENY/CANCEL 短路；ASK 不被后续 ALLOW 洗掉
}

// —— 钩子：扩展点。waterfall、可观察、受载荷冻结约束 —— //
public record HookEvent(String toolName, String args, String callId) {
    public static HookEvent of(HookEvent other);
}
@FunctionalInterface
public interface Hook {
    HookEvent onToolCall(HookEvent event, Next next) throws Exception;  // 不许返回 null
    interface Next { HookEvent next(HookEvent event) throws Exception; }
}
public final class HookBus {                // 按注册顺序 waterfall
    public HookBus register(Hook hook);
    public int size();
    public HookEvent snapshot(HookEvent event);  // 不跑钩子的只读快照
    public HookEvent run(HookEvent event);        // 返回 null = 契约破坏，显式报错
}
public class HookRejectedException extends RuntimeException {}   // 钩子主动拒绝这次调用

// —— 执行后钩子：只能加重，不能减轻 —— //
@FunctionalInterface
public interface PostHook {
    String afterToolResult(HookEvent event, String content, boolean isError);
}
```

### 3.1 审批（core 接口 + hook 实现）

```java
// net.hegq.mos4j.core
public record ApprovalRequest(String id, String toolName, String callId, String reason) {}
public enum ApprovalOutcome { APPROVED, REJECTED, CANCELLED, UNAVAILABLE; public String wire(); }
public record ApprovalResult(ApprovalOutcome outcome, String reason) {
    public static ApprovalResult approved();
    public static ApprovalResult rejected(String reason);
    public static ApprovalResult cancelled(String reason);
    public static ApprovalResult unavailable(String reason);
    public boolean permits();
}
public interface ApprovalAudit {           // 审计出口（hook 与 session 都不依赖对方）
    void asked(ApprovalRequest request);
    void decided(ApprovalRequest request, ApprovalOutcome outcome);
}

// net.hegq.mos4j.hook
@FunctionalInterface
public interface ApprovalChannel { ApprovalResult request(ApprovalRequest request); }
@FunctionalInterface
public interface Answerer { ApprovalResult answer(ApprovalRequest request); }
public final class ApprovalService implements ApprovalChannel {   // 通道异常 → unavailable（≠ 人工拒绝）
    public ApprovalService(Answerer answerer);
}
```

四档语义：**未装配 = 拒绝**（ask 无通道 → fail-closed）、**每次问**（批准不缓存）、**通道故障 = unavailable**（不冒充人工拒绝）、**人工拒绝保留拒绝状态**（含「不许绕」半句）。

### 3.2 执行编排顺序（`mos4j-agent`，与 `INVARIANTS.md` I5 一致）

`工具存在性 → 守卫（deny/cancel 短路，ask 记下）→ 钩子链 → 载荷冻结检查 → 审批 → 执行（执行前再查冻结）→ 回填（按调用序）→ 执行后钩子（只能加重）`

守卫排在钩子前面：安全判定不应发生在扩展代码之后。审批排在冻结检查之后：不为反正会被拒的调用打扰人。守卫判定的仍然就是最终载荷——任何改写都被冻结检查拒掉，能执行下去的载荷必然就是守卫看到的那一份（机制 M1）。

### 3.3 名字级 / 参数级策略（`mos4j-tool`）

```java
public final class ToolNamePolicy implements Guard {          // allow 未配置 = 不限制；allow 配成空集 = 全禁
    public static ToolNamePolicy unrestricted();
    public static ToolNamePolicy of(Collection<String> allow, Collection<String> deny);
    public ToolNamePolicy withAllow(Collection<String> names); // 叠加（返回新策略）
    public ToolNamePolicy withDeny(Collection<String> names);  // 叠加（返回新策略）
    public boolean allows(String toolName);                    // Deny 优先
    public List<String> visible(Collection<String> all);       // 可见列表与判定同一份名单
    public GuardDecision check(GuardEvent event);
}
public final class ParamPolicy implements Guard {             // 参数规则取最严裁决；未声明参数默认允许
    public enum Verdict { ALLOW, ASK, DENY }
    public static Builder builder();
    public static ParamPolicy empty();
    public GuardDecision check(GuardEvent event);              // 解析失败 = 拒绝
    public Map<String, Object> snapshot(String args);          // 不可变载荷快照（策略只能读它）
    public static final class Builder {
        public Builder rule(String toolName, String param, Verdict verdict);
        public Builder tool(String toolName, Verdict verdict); // 该工具的默认裁决（"*")
        public ParamPolicy build();
    }
}
```

策略只能读**不可变载荷快照**；参数解析失败 = 拒绝；通配默认规则（`tool(name, verdict)`）只作用于**没有单独规则**的参数，具体参数规则优先，多条规则取最严裁决（deny > ask > allow）；正则一律 RE2J（禁 `java.util.regex`）。

### 3.4 文件与沙箱（J3 已落地）

```java
// ---- mos4j-runtime：jail + 写前观察门 ----
public final class RootSet {                                  // 活根集合：加根是追加，锚点不变
    public static RootSet of(Path anchor) throws IOException;  // 锚点 = 相对路径的基准，永不变动
    public RootSet add(String mountId, Path dir) throws IOException; // 追加；对下一次调用立即生效
    public Path anchor();
    public List<Root> roots();                                // 快照（不可变）
    public List<String> changes();                            // 变更留痕
}
public final class SandboxedFs {                              // 逐段解析符号链接，每段确认还在根内
    public static final int MAX_SYMLINK_DEPTH = 32;
    public SandboxedFs(RootSet roots);
    public Path resolve(String userPath);                     // 越界 → PathDeniedError
    public Path resolveExisting(String userPath) throws IOException;
    public Path resolveForWrite(String userPath) throws IOException;  // 叶子可不存在，父目录必须存在
    public List<String> allowedRoots();
}
public class PathDeniedError extends RuntimeException {       // 结构化拒绝：带「该操作允许的根」
    public String requestedPath();
    public List<String> allowedRoots();
    public String reason();
}
public final class ObserveGate {                              // 写前观察门（M7）
    public Observation observe(Path path) throws IOException; // 读成功后记录
    public Verdict verify(Path path);                         // 闸门只给事实（原因码）
    public record Verdict(boolean allowed, Reason reason, VersionToken expected, VersionToken actual, String detail) {
        public enum Reason { OK, NOT_OBSERVED, DELETED, MODIFIED, CHECK_FAILED }
    }
}
public record VersionToken(long dev, long ino, long size, long mtimeNs, boolean unix) {
    public static VersionToken of(Path path) throws IOException;
    public String value();                                    // dev:ino:size:mtimeNs 或 size:mtimeNs
}

// ---- mos4j-runtime：进程隔离 ----
public final class LocalRunner implements Runner {             // 工作目录与环境由宿主固定
    public static Builder builder(Path workingDirectory);
    public Duration effectiveTimeout(RunRequest request);     // min(请求 ?? 缺省, 上界)
    public int effectiveCap(RunRequest request);
    public ProcessGroupKiller killer();
    public ResourceLimits limits();
    public PathScope pathScope();                             // 可为 null = 未装配
}
public interface ProcessGroupKiller {                        // 降级声明进 Describe
    void kill(Process process);
    String description();
}
public interface PathScope {                                  // 只能收窄不能放开
    PathScope narrowTo(List<Path> writeRoots);
    List<String> wrap(List<String> command);
    List<Path> writeRoots();
    String description();
}
public final class PathScopes {                               // 不支持的平台明确报错
    public static boolean supported();                        // 本版本只有 macOS（Seatbelt）
    public static PathScope create(Path baseWriteRoot);       // 其他平台 → UnsupportedOperationException
}

// ---- mos4j-tool/builtin：内置工具 ----
public final class ReadFileTool implements Tool {             // 工具名 read（参数 path + offset? + limit?）
    public ReadFileTool(SandboxedFs fs, ObserveGate gate);
}
public final class WriteFileTool implements Tool {            // 工具名 write（参数 path + content）
    public WriteFileTool(SandboxedFs fs, ObserveGate gate);
}
public final class EditFileTool implements Tool {             // 工具名 edit（参数 path + edits[]）
    public EditFileTool(SandboxedFs fs, ObserveGate gate);
    // edits：数组 / 单个对象 / JSON 字符串三种形状；每处 old_string 唯一命中；按原文定位；重叠报错
}
public final class ShellTool implements Tool {                // 工具名 shell（参数 command + description? + timeout?）
    public ShellTool(Runner runner);
}
```

**内置工具名**：Java 侧 = `read` / `write` / `edit` / `shell`；
Go 侧 = `read` / `write` / `edit` / `shell`（Go 仓库从未出现 `read_file` / `write_file` / `edit_file`）。
其余内置工具名（`grep` / `glob` / `ls` / `current_time` / `grant_access` /
`tool_search` / `activate` / `web_search` / `web_fetch` / `shell`）**逐字一致**。

> ✅ **B13-1 已落地**（2026-11-06）：三个文件工具的**模型可见名**已改为 Go 的
> `read` / `write` / `edit`（Java 类名 `ReadFileTool` / `WriteFileTool` / `EditFileTool` 不变）。
> 清单由 `mos4j-tool` 的 `ToolContractTest` 机器守护（工具名 + 参数名 + 必填参数）。
>
> ✅ **B13-2 已落地**：`grep` 增 `glob` 过滤、`glob` 增 `limit`（默认 1000 / 上限 10000）、
> `ls` 增 `limit`（默认 500 / 上限 5000）、`shell` 增 `description`、`tool_search` 参数名
> 由 `topK` 改为 `limit`。
>
> ✅ **B13-3 已落地**：`edit` 参数改为 `edits`（数组 / 单对象 / JSON 字符串三种形状），
> 每处 `old_string` 仍须唯一命中，全部按原文定位，重叠报错，原子落盘。
>
> ⏸ **有意差异（不再对齐）**：`grant_access` 保留 Java 的 `mount`+`dir`+`reason`（B13-4）；
> deferred 保留 Java 的 `tool_search` + `activate` 两步式（B13-5）。

### 3.5 上下文工程（J4 已落地）

```java
// ---- mos4j-session：输出限流 ----
public final class Truncation {                             // 行/字节双限 + 按工具定向保留
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 50 * 1024;
    public static Truncation defaultPolicy();                // read/current_time/grep/glob/ls 留头，shell/bash 留尾，其余两头各半
    public static Builder builder();
    public Keep keepFor(String toolName);                    // HEAD / TAIL / BOTH
    public TruncatedOutput apply(String toolName, String content); // 不超限则逐字原样返回
    public TruncatedOutput apply(String toolName, String content, int byteLimit); // 更小的上限（给框架注解留预算）
    public enum Keep { HEAD, TAIL, BOTH }
}
public record TruncatedOutput(String content, boolean truncated, int originalLines,
                              int originalBytes, int keptLines, int keptBytes, Truncation.Keep keep) {
}
public final class SpillStore {                              // 截断落盘逃生通道
    public static SpillStore of(SandboxedFs fs, String spillDir) throws IOException; // 越界 → PathDeniedError
    public String dir();
    public SpillResult spill(String name, String content);   // 失败 → 内容原样返回 + 警告
}
public record SpillResult(boolean spilled, String path, String content, List<String> warnings) {
}
public final class ToolOutputLimiter {                       // 先截断、再落盘
    public static ToolOutputLimiter of(Truncation truncation, SpillStore spillStore);
    // 模型看到的内容 = 截断内容 + 落盘提示；**提示也占字节预算**（硬上限含提示）
    public LimitedOutput limit(String toolName, String callId, String content);
    public record LimitedOutput(String content, boolean truncated, String spillPath, List<String> warnings) {
    }
}

// ---- mos4j-session：压缩 ----
public record CompactionPolicy(Long contextWindow, int reserveTokens, int keepRecentTokens) {
    public static CompactionPolicy of(long contextWindow, int reserveTokens, int keepRecentTokens);
    public boolean enabled();                                 // 未配置 contextWindow = 永不触发
    public int budget();                                      // contextWindow - reserveTokens
    public boolean shouldCompact(int contextTokens);          // contextTokens > budget
}
public final class CutPointFinder {                          // 切割点永不落在 tool 结果上
    public static Cut find(List<Projection.SeqMessage> view, int keepRecentTokens);
    public record Cut(int index, long firstKeptSeq, int keptTokens) {
    }
}
public final class Transcript {                              // 摘要输入格式（冻结）
    public static final String CONVERSATION_OPEN = "<conversation>";
    public static final String CONVERSATION_CLOSE = "</conversation>";
    public static final String PREVIOUS_SUMMARY_OPEN = "<previous-summary>";
    public static final String PREVIOUS_SUMMARY_CLOSE = "</previous-summary>";
    public static String render(List<Projection.SeqMessage> masked, String previousSummary);
}
public interface SummaryGenerator {                          // 摘要器是接口（可无模型替身测试）
    Summary generate(String transcript, String previousSummary);
    public record Summary(String text, Usage usage) {
    }
}
public final class ChatModelSummaryGenerator implements SummaryGenerator { // 默认实现
    public static final String SYSTEM_PROMPT = "...";         // 冻结：不要续写对话 / 不要调用工具
    public ChatModelSummaryGenerator(ChatModel model);        // 请求不带工具定义
}
public final class CompactionService {
    public CompactionService(CompactionPolicy policy, SummaryGenerator generator);
    public CompactionResult maybeCompact(Session session) throws IOException; // 摘要失败 → 抛错，历史未被遮蔽
}
public record CompactionResult(boolean compacted, long firstKeptSeq, String summary,
                               int tokensBefore, List<String> warnings) {
}

// ---- mos4j-session：提示词分节 + 上下文文件 + 任务状态 ----
public final class PromptSections {                          // 装配期只渲染一次
    public static final List<String> CANONICAL_ORDER =
            List.of("base", "environment", "context", "instructions", "tools");
    public static Builder builder();
    public List<PromptSection> sections();
    public String content(String name);
    public String render();                                   // 逐字节可复用
    public static final class Builder {
        public Builder add(String name, String content);      // 严格插入：重名报错
        public Builder set(String name, String content);      // 覆写：不存在的段报错
        public Builder complete(String name, String content); // 整段替换
        public Builder clone();                               // 派生独立副本
        public PromptSections build();
    }
}
public interface ContextFileReader {                         // 框架不碰文件系统
    public byte[] readIfExists(String path) throws IOException;  // 不存在 → null
    public default String realPath(String path);              // 真实路径去重
    public default String parentOf(String dir);               // 向上递归；到顶 → null
    public default String join(String dir, String name);      // 路径拼接（虚拟文件系统宿主覆写）
}
public final class ContextFiles {
    public static final List<String> DEFAULT_CANDIDATES = List.of("AGENTS.md", "MOS.md", ".mos/AGENTS.md");
    public static ContextFiles discover(String startDir, ContextFileReader reader) throws IOException;
    public static ContextFiles discover(String startDir, List<String> candidates,
                                        ContextFileReader reader, int byteBudget) throws IOException;
    public List<ContextFile> files();
    public List<String> warnings();                           // 读失败 / 去重 / 裁剪 / 丢弃都写这里
    public java.util.Set<String> paths();                     // 已发现的文件路径（诊断用）
    public String render();
}
public final class TaskState {                               // 只装环境自证的事实
    public static TaskState empty();
    public TaskState with(String key, String value, String toolName, String callId);
    public Optional<Fact> fact(String key);
    public Map<String, Fact> facts();
    public record Fact(String key, String value, String toolName, String callId) {
    }
}
public final class Auditor {                                 // 审计门：封闭白名单，不允许通配符
    public static final Set<String> DEFAULT_TRUSTED_TOOLS =
            Set.of("read", "ls", "glob", "grep", "current_time");   // 与 Go 同款（agent/auditor.go）
    public static Auditor defaults();
    public static Auditor of(Collection<String> trustedTools); // 含通配符 → IllegalArgumentException
    public boolean admits(String toolName);
    public Decision admit(TaskState state, String key, String value, String toolName, String callId);
    public record Decision(boolean accepted, TaskState state, String reason) {
    }
}
```

**驱动接线**（`mos4j-agent`）：

```java
public final class RunContext {                              // 装配期决定、运行期只读
    public CompactionService compaction();                   // 可空 = 不压缩
    public ToolOutputLimiter outputLimiter();                // 可空 = 不限流
}
public final class Config {                                  // 宿主装配入口
    public Builder compaction(CompactionService compaction);
    public Builder outputLimiter(ToolOutputLimiter outputLimiter);
}
```

压缩在**每轮决策前**触发（`ReActDriver`）：超预算则先摘要并遮蔽旧历史，模型看到的是压缩后的对话。
输出限流在**写 tool/result 前**触发：日志里记的是「模型实际看到的内容 + 完整内容在哪」。

### 3.6 工具全集与联网（J5 已落地）

```java
// ---- mos4j-tool/builtin：搜索类工具 ----
public final class GrepTool implements Tool {                 // 工具名 grep（参数 pattern + path? + glob? + limit?）
    public static final int DEFAULT_LIMIT = 100;              // limit 预设 100 / 硬上限 1000
    public static final int MAX_FILE_BYTES = 1024 * 1024;     // 单文件 1MB，超过按二进制跳过
    public static final int MAX_FILES = 20_000;               // 单次搜索文件数上限
    public GrepTool(SandboxedFs fs);
    // glob：可选文件名过滤（先按相对路径、再按文件名匹配，与 Go 同口径）
}
public final class GlobTool implements Tool {                 // 工具名 glob（参数 pattern + path? + limit?）
    public static final int MAX_FILES = 20_000;
    public static final int DEFAULT_LIMIT = 1000;             // limit 缺省 1000
    public static final int MAX_LIMIT = 10_000;               // limit 硬上限 10000
    public GlobTool(SandboxedFs fs);                          // 模式不含 / 按文件名，含 / 按相对路径
}
public final class LsTool implements Tool {                   // 工具名 ls（不递归；参数 path? + limit?）
    public static final int MAX_ENTRIES = 5000;
    public static final int DEFAULT_LIMIT = 500;              // limit 缺省 500
    public static final int MAX_LIMIT = 5000;                 // limit 硬上限 5000
    public LsTool(SandboxedFs fs);                            // name\ttype\tsize；含隐藏项；按名排序
}
public final class CurrentTimeTool implements Tool {          // 工具名 current_time
    public CurrentTimeTool();                                 // 时钟可注入（测试拨时钟）
    public CurrentTimeTool(java.util.function.Supplier<java.time.Instant> clock);
}
public final class GrantAccessTool implements Tool {          // 工具名 grant_access
    public interface AccessApprover { boolean approve(String path, String reason); }
    public GrantAccessTool(RootSet roots, SandboxedFs fs, AccessApprover approver);
    // 模型只有「申请」能力：批准才 roots.add(mount, dir)；回调异常 = 拒绝（fail-closed）；批准不缓存
}

// ---- mos4j-tool/searchtools：工具面延后加载 ----
public final class Bm25Index {                                // BM25 k1=1.2 b=0.75
    public static final double K1 = 1.2;
    public static final double B = 0.75;
    public void add(String id, String text);
    public List<Scored> search(String query, int topK);       // camelCase 分词 + 词干 + 停用词 + CJK bigram
    public record Scored(String id, double score) {
    }
}
public final class DeferredToolCatalog {                      // 登记名字 + 说明，不进清单
    public void register(String name, String description);    // 重名报错
    public List<Scored> search(String query, int topK);       // 已激活的不重复返回
    public List<String> activate(List<String> names);         // 未知名忽略；已激活幂等
    public boolean isActive(String name);
    public List<String> activeTools();
}
public final class ToolSearchTool implements Tool {           // 工具名 tool_search（默认 limit 5）
}
public final class ActivateTool implements Tool {             // 工具名 activate
}

// ---- mos4j-tool：Registry deferred 接线 ----
public final class ToolRegistry {
    public void registerDeferred(Tool tool, String description); // deferred 非空时自动注册 tool_search/activate
    public DeferredToolCatalog deferredCatalog();
    // list()：非 deferred 始终出现；deferred 只在激活后出现（下一轮生效）
    public void admitHistoricalTools(List<String> names);     // 历史工具名并入（会话恢复兑底）
}

// ---- mos4j-tool/web：联网 ----
public interface SearchProvider {                             // web_search 的 provider（宿主接服务商）
    Outcome search(List<String> queries, int limit) throws Exception;  // 多查询形状（对齐 Go Searcher）
    record Outcome(String summary, String note, List<SearchResult> sources, boolean truncated) {
        static Outcome empty();
        static Outcome of(List<SearchResult> sources);
    }
    record SearchResult(String title, String url, String snippet, String publishedAt) {
        SearchResult(String title, String url, String snippet);        // 无发布时间
    }
    interface Single {                                        // 单查询后端（厂商 API 形状）
        List<SearchResult> search(String query, int limit) throws Exception;
    }
}
public final class WebSearchTool implements Tool {            // 工具名 web_search
    public static final String UNTRUSTED_NOTE = "【外部内容不可信】…"; // 与 web_fetch 共用
    public static final int MAX_QUERIES = 4;                  // queries[] 上限（对齐 Go DefaultMaxQueries）
    public static final int MAX_RESULTS = 8;                  // 来源条数上限（对齐 Go DefaultMaxResults）
    public WebSearchTool(SearchProvider provider);
    public WebSearchTool(SearchProvider provider, int maxQueries, int maxResults);
    public static WebSearchTool of(SearchProvider.Single single);  // 多查询包装（并行 + 去重）
    // 参数：queries[]（首选）/ query（单条容错）/ limit（缺省 8）；回执是结构化 map：
    // queries / summary / note / sources / count / truncated / content
}
public interface Fetcher {                                    // web_fetch 的抓取后端（宿主可自备代理）
    String name();                                            // 诊断用（缺省实现是 "http"）
    FetchResult fetch(String url, FetchLimits limits) throws IOException;
}
public record FetchLimits(long maxResponseBytes, int maxContentChars,
                          Duration timeout, int maxRedirects, String userAgent) {
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 5L << 20;   // 5MB
    public static final int DEFAULT_MAX_CONTENT_CHARS = 100_000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_REDIRECTS = 5;
    public static final String DEFAULT_USER_AGENT = "mos-web-fetch/1.0 (+https://github.com/puras/mos4j)";
    public static FetchLimits defaults();
    public FetchLimits normalized();                          // 非正值/缺省逐项取默认
}
public record FetchResult(String url, int statusCode, String title,
                          String contentType, String content, boolean truncated) {
}
public final class WebFetchTool implements Tool {             // 工具名 web_fetch
    public static final int DEFAULT_MAX_CONTENT_CHARS = FetchLimits.DEFAULT_MAX_CONTENT_CHARS;
    public WebFetchTool();                                    // 缺省后端 PinnedConnector
    public WebFetchTool(Fetcher fetcher);                     // null → 执行期 fail-closed（对齐 Go）
    public WebFetchTool(Fetcher fetcher, FetchLimits limits);
    public WebFetchTool(Fetcher fetcher, FetchLimits limits, int maxContentChars);
    // 未装配后端是执行期错误：`web_fetch 未装配抓取后端（宿主注入 Fetcher 后才可用）`
    // 回执是结构化 map，6 个键固定都在（Go map 字面量无 omitempty）：
    // url / status_code / title / content_type / truncated / content
}
public final class PublicAddresses {                          // SSRF 防线一：公网判定（纯逻辑）
    public static boolean allPublic(List<InetAddress> addresses); // 任一非公网 = 整体拒绝
    public static boolean isPublic(InetAddress address);
}
public interface Resolver {                                   // 主机名 → 地址集（对齐 Go Resolver）
    List<InetAddress> resolve(String host) throws IOException;
}
public interface Dialer {                                     // 拨号器（对齐 Go HTTPFetcher.Dial）
    Socket dial(String host, int port, int timeoutMillis) throws IOException;
}
public class PinnedConnector implements Fetcher {             // 缺省后端：五条防线 + 内容归类 + HTML 清洗
    public static final int MAX_URL_LENGTH = 2048;
    public PinnedConnector();                                // 系统解析 + 钉住拨号
    public PinnedConnector(Resolver resolver, Dialer dialer); // 注入后不再钉住（测试 / 自建代理）
    public String name();                                     // "http"
    public FetchResult fetch(String url, FetchLimits limits) throws IOException;
}

// ---- mos4j-tool：工具规范（T5.10）----
public interface Guideline {                                  // 跨调用指引进系统提示词
    String guideline();
    default String render();
}
public final class Guidelines {                               // 聚合渲染（逐字节可复现）
    public static String render(List<Guideline> guidelines);
}
```

**驱动接线**（`ReActDriver`）：

- 工具清单**每轮决策前重组**：上一轮激活的 deferred 工具本轮进入清单（激活下一轮生效）；schema 走注册期缓存，重组装不走反射。
- 模型发起的工具调用在执行前做「历史工具名并入」兑底（会话恢复后激活集为空的场景）。

**SSRF 五条硬防线**（`web_fetch`，全在缺省后端 `PinnedConnector` 里）：

1. **URL 校验**：长度上限 2048、只允许 http/https、禁止 URL 内嵌凭据。
2. **公网地址校验**：DNS 解析后逐地址检查（逐字节移植 Go `isPublicIPv4` / `isPublicIPv6`）——校验「解析结果」不是「URL 文本」。
   IPv4 拒：`0/8`、`10/8`、`127/8`、`100.64/10`（CGNAT）、`169.254/16`（含云 metadata）、`172.16/12`、`192.0.0/24`、`192.0.2/24`、`192.168/16`、`198.18/15`、`198.51.100/24`、`203.0.113/24`、`224/4` 起整段（含广播）。
   IPv6 拒：`::`、`::1`、`fc00::/7`（ULA）、`fe80::/10`、`ff00::/8`（组播），以及**四类内嵌 IPv4 的地址**——IPv4-mapped `::ffff:0:0/96`、NAT64 `64:ff9b::/96` 与 `64:ff9b:1::/48`、6to4 `2002::/16`、Teredo `2001::/32`，**一律拒且不论内嵌何地址**（对齐 Go 的可观测行为：它的内嵌分支因 `embeddedIPv4` 返回 16 字节而实际永远 false，见 GO-DIFF #22）。代价：只跑 NAT64 的纯 IPv6 网络抓不到网页（与 Go 同样局限）。
3. **地址钉住**：裸 `Socket` 直连校验过的地址，不做第二次 DNS 解析（防 rebinding）。
4. **只跟同源重定向**：协议 + 主机 + 有效端口必须一致；跨源一律拒绝并要求重新调用；跳数超上限报错。
5. **体积与时间上限**：响应体 5MB、转文本后 10 万字符、30s 超时；只解 UTF-8；无 Content-Type 或非文本类型响亮失败。

**为什么钉住必须裸 Socket**：`HttpURLConnection` / `HttpClient` 都没有拨号钩子；`Proxy.Type.DIRECT` 会忽略代理地址、照常解析主机名 = **假钉住**。JDK 内唯一真钉法是自己发 HTTP/1.1 GET（`PinnedConnector` 实现了状态行 / 头 / chunked / gzip / 字节上限 / TLS-SNI + 证书校验）。

**分层**：工具层 `WebFetchTool` 只做「参数校验 + 字符截断 + 回执整形」，**不解析 DNS、不跟重定向、不做地址判定**——否则自备后端会被工具层拦死，「换实现 = 换防线」就不成立。

**web_search 的形状对齐 Go**（`tool/web/web.go`）：`queries[]` 是首选参数（1–4 条，多条并行搜索后按 URL 去重合并），`query` 是单条容错形状；`limit` 缺省与上限都是 8。回执是结构化 map（`queries` / `summary` / `note` / `sources` / `count` / `truncated` / `content`），`content` 是给模型读的文本块（不可信标注 + 查询 + 摘要 + 来源清单）。

**web_search 的 provider 失败是工具错误**（对齐 Go 的 `web_search 失败: %w`）。例外是「部分查询失败」：用 `WebSearchTool.of(Single)` 装配时，某几条查询失败不拖垮其余——有结果就用结果、失败条数进 `note`，全部失败才算整体失败。

**web_fetch 的抓取后端是 `Fetcher` 接口**（对齐 Go 的 `web.Fetcher`）：缺省实现 `PinnedConnector` 带上面五条防线 + 内容归类 + HTML 转纯文本，但那套防线**只对直连公网成立**——需要走企业代理 / 专线 / 测试内网的宿主自备 `Fetcher` 实现，并一并接过访问策略的责任（换掉实现 = 换掉防线，连内容处理也一并换掉）。

**web_fetch 的回执是 6 键 map**：`url` / `status_code` / `title` / `content_type` / `truncated` / `content` **始终都在**（Go map 字面量无 `omitempty`）——空标题发 `""`、`truncated` 发 `false`。**非 2xx 是结果不是错误**：404 带 `status_code=404` 正常返回，因为「这个资源的状态」本身就是模型要的信息。

**可注入的两个点**（对齐 Go `HTTPFetcher.Resolver` / `.Dial`）：`PinnedConnector(Resolver, Dialer)`。只注入 `Resolver` 仍然是钉住拨号（地址来源变了，防 rebinding 的性质不变，注入解析器同样过公网校验）；注入 `Dialer` 就不再钉住——这个参数存在的理由就是「连到别处」。

### 3.7 三协议 + 硬化 + 门面（J6 已落地）

```java
// ---- mos4j-model：Anthropic 协议（T6.1/T6.2）----
public record AnthropicConfig(String apiKey, String baseUrl, String model,
        String version, Duration timeout, Map<String,String> extraHeaders) {
    public static AnthropicConfig of(String apiKey, String baseUrl, String model);
    public AnthropicConfig normalized();          // 补默认值
    public String messagesUrl();                  // {base}/messages
    public Map<String,String> headers();          // x-api-key + anthropic-version + extra
}
public final class AnthropicChatModel implements ChatModel {   // PROVIDER = "anthropic"
    // 流完整性（M5）：message_stop 与 stop_reason 任一到即完整；两者皆无才是截断；
    // 空结束原因 ≠ 截断（A5 语料）。
    // usage（M6）：message_start 与 message_delta 两侧逐字段合并，delta 赢（A10 事故回归）；
    // Anthropic 口径 inputTokens 不含缓存 → freshInput = inputTokens；total 含缓存。
}

// ---- mos4j-model：Gemini 协议（T6.3）----
public record GeminiConfig(String apiKey, String baseUrl, String model,
        Duration timeout, Map<String,String> extraHeaders) {
    public static GeminiConfig of(String apiKey, String baseUrl, String model);
    public String streamUrl();     // {base}/models/{model}:streamGenerateContent?alt=sse
    public String generateUrl();   // {base}/models/{model}:generateContent
}
public final class GeminiChatModel implements ChatModel {      // PROVIDER = "gemini"
    public GeminiChatModel(GeminiConfig config, boolean requireFinish); // 缺省 requireFinish=true
    // 结束判定比 OpenAI/Anthropic 严：finishReason 即结束标记；require_finish=true 时
    // 流读完仍无 finishReason = 截断（g5a）；开关关掉放行（g5b 逃生阀）。
    // Gemini 无终止哨兵：读到 EOF 为止（usageMetadata 在 finishReason 之后到达不丢）。
    // functionCall.args 是完整对象 → 序列化为 ToolCall.args 字符串；同 id 跨 chunk 合并。
}

// ---- mos4j-model：重试（T6.5，装饰器，默认不装配即透传；包 net.hegq.mos4j.model.retry）----
public final class RetryPolicy {
    public static RetryPolicy ofDefaults();       // 5 次 / 500ms 起 / 20s 封顶
    public static Builder builder();              // maxAttempts/initialBackoff/maxBackoff/retryIf
    public boolean isTransient(Throwable error);  // 缺省：429/5xx/408/网络/空响应；取消·截断终止性
    public Duration backoff(int attempt, RandomGenerator random); // 有界指数 + ±20% 抖动
}
public final class RetryingChatModel implements ChatModel {
    public RetryingChatModel(ChatModel delegate, RetryPolicy policy); // policy=null 即透传
    // 流式只在未吐出内容时重试（已吐内容再重试 = 宿主看到重复文本）。
    // 退避等待分片检查取消：等待中取消立即终止，不再发起下一次尝试。
}

// ---- mos4j-agent：取消贯通（T6.4）----
public final class ExecPath {
    // 新增重载：取消后未开始的调用回填「调用已取消，未执行」（错误结果，保持回填序与配对）；
    // 正在执行的不中断（协作式取消，诚实声明）；token=null 零开销。
    public static List<Outcome> executeBatch(ToolRegistry registry, List<ToolCall> calls,
            Options options, Security security, CancellationToken token);
}

// ---- mos4j-agent：取消贯通到驱动/决策器/智能体（加固批次）----
public final class Agent {
    // 带取消的执行：已取消的轮次以 turn/end{interrupted} 收尾再抛 CancellationException
    // （不留孤儿 turn）；旧重载等价于 CancellationToken.none()（零开销）。
    public String run(Session session, String input, CancellationToken cancellation) throws IOException;
}
public interface LoopDriver {
    String run(RunContext rt, Session session, String input) throws IOException;
    // default：只在开始前检查一次，并明说「不响应中途取消」（第三方驱动不一定改过一遍）。
    default String run(RunContext rt, Session session, String input, CancellationToken cancellation)
            throws IOException;
}
public interface Planner {
    Action nextAction(List<Message> history, List<Tool> tools);
    // default：调用前检查一次；ReActPlanner 覆写并把令牌直传到 model.chat(request, token)。
    default Action nextAction(List<Message> history, List<Tool> tools, CancellationToken cancellation);
}

// ---- mos4j-agent：门面（T6.10）----
public final class Mos4j {
    public enum Protocol { OPENAI, ANTHROPIC, GEMINI }   // 缺省 OPENAI
    public static Builder builder();
    public Agent agent();                                // 一次依赖拿到可跑 Agent
    public JsonlPersistence persistence();
    public Session session(String id) throws IOException; // 同 id 再开 = 恢复历史
    public static final class Builder {
        public Builder protocol(Protocol p);             // 缺省 OPENAI
        public Builder apiKey(String k);                 // 缺省读 OPENAI_API_KEY 等；缺密钥 fail-fast 报变量名
        public Builder baseUrl(String url); public Builder model(String m);
        public Builder systemPrompt(String p);
        public Builder logDir(Path dir);                 // 会话日志目录（缺省 .mos4j/sessions）
        public Builder workspace(Path dir);              // 文件工具 jail 锚点（缺省当前目录）
        public Builder fileTools(boolean b);             // 缺省 true：read/write/edit/shell
        public Builder model(ChatModel m);               // 显式给模型（高级）
        public Builder tools(ToolRegistry r);            // 显式给工具面（覆盖缺省四件套）
        public Builder fileSystem(FileSystemBoundary b); // 显式给文件边界快照
        public Builder sandbox(SandboxBoundary b);       // 显式给沙箱边界快照
        public Builder guards(GuardBus b);               // 守卫总线（可空 = 未装配）
        public Builder hooks(HookBus b);                 // 钩子总线（可空 = 未装配）
        public Builder approvals(ApprovalChannel c);     // 审批通道（可空 = 未装配 → fail-closed）
        public Builder postHook(PostHook h);             // 执行后复核钩子（只能加重错误）
        public Builder outputLimiter(ToolOutputLimiter l);// 输出限流（可空 = 不限流）
        public Builder compaction(CompactionService c);  // 上下文压缩（可空 = 不压缩）
        public Builder concurrency(int n);               // 工具并发上限（缺省 0 = 驱动缺省）
        public Builder maxDecisionRounds(int n);         // 单轮决策次数上限（缺省 0 = 不限）
        public Builder stream(boolean b);                // 是否流式决策
        // 流式增量回调：只在 stream(true) 时被调；每增量块一次；在调用线程上同步执行
        // （阻塞会拖慢决策，耗时请自行转交）；不装 = 增量丢弃、链路照走。
        public Builder onChunk(Consumer<StreamChunk> c);
        public Mos4j build() throws IOException;

    // 缺省模型装配口径公开：starter 与门面共用一份「协议 → 环境变量 → 默认地址」真相
    public static ChatModel defaultModel(Protocol protocol, String apiKey, String baseUrl,
                                         String model, Map<String, String> env);
}

// ---- mos4j-agent：CLI（T6.9，单轮最小形态）----
// 用法：mos4j --api-key KEY [--protocol openai|anthropic|gemini] [--base-url URL]
//           [--model NAME] [--system PROMPT] [--workspace DIR] [--log-dir DIR]
//           [--session ID] [--no-file-tools] "输入文本"
// 退出码：0 正常；2 用法/装配错误（缺密钥时报环境变量名）。
```

### 3.8 Spring Boot Starter（按需模块，2026-11-06 已落地）

> 不进默认 reactor；`./mvnw -Pstarter verify` 构建。用法全文见 `docs/spring-boot-starter.md`。
> 原则：**starter 只做装配，不实现任何智能体语义**（第二份真相比没有门面更危险）。

```java
// ---- mos4j-spring-boot-starter：自动装配 ----
@ConfigurationProperties(prefix = "mos4j")
public class Mos4jProperties {
    // enabled(缺省 true) / protocol(缺省 OPENAI) / apiKey / baseUrl / model / systemPrompt
    // workspace / logDir / fileTools(缺省 true) / concurrency / maxDecisionRounds / stream
    // （流式增量回调 onChunk 是宿主的代码，只能程序化装配，不走配置属性）
    // approvals.required(缺省 false) / output.* / compaction.*
}

@AutoConfiguration
@EnableConfigurationProperties(Mos4jProperties.class)
@ConditionalOnProperty(prefix = "mos4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Mos4jAutoConfiguration {
    @Bean @ConditionalOnMissingBean public Mos4j mos4j(...);
    @Bean @ConditionalOnMissingBean public Agent mos4jAgent(Mos4j m);
    @Bean @ConditionalOnMissingBean public JsonlPersistence mos4jPersistence(Mos4j m);
    @Bean @ConditionalOnMissingBean public Mos4jRunner mos4jRunner(Mos4j m);
}

// 执行器：跑一轮（会话按 id 自动开/恢复）；容器关闭时取消在途请求
public final class Mos4jRunner implements AutoCloseable {
    public String run(String sessionId, String input) throws IOException;
    public String run(String sessionId, String input, CancellationToken token) throws IOException;
    public int inFlightCount();
    public void close();
}
```

**fail-closed 口径**（与 SDK 一致）：
- 启用但无 API 密钥 → 启动失败，报错指名 `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`；
- `mos4j.approvals.required=true` 但无 `ApprovalChannel` Bean → 启动失败（不装配审批通道 = 需要人确认的调用全被拒）；
- `mos4j.enabled=false` → 一个 Bean 都不给（状态可见，不是半残的）。

### 3.9 技能机制（J7 已落地，2026-11-07）

> Go 的 `agent/skill` 的 Java 语义重写。**不新增模块**：落在 `mos4j-tool`（`tool/skill` 包）/ `mos4j-core` / `mos4j-session` / `mos4j-agent` / `mos4j-agent`。
> 渐进式披露：**清单常驻系统提示词，正文调用时现读**。

```java
// ---- mos4j-tool：net.hegq.mos4j.tool.skill ----
public final class SkillCatalog {
    public static final String SKILL_FILE_NAME = "SKILL.md";
    public static final int DEFAULT_MAX_BODY_BYTES    = 64 * 1024;   // 与 Go 逐字相同
    public static final int DEFAULT_MAX_CATALOG_BYTES = 8 * 1024;
    public static final String CATALOG_TAG = "available_skills";
    public static final String CONTENT_TAG = "skill_content";
    public record Options(List<String> dirs, SkillReader reader,
                          int maxBodyBytes, int maxCatalogBytes, ToolProbe toolProbe) {
        public static Options of(List<String> dirs);
        public Options limits(int maxBodyBytes, int maxCatalogBytes);  // 轻量派生，保留其余字段
    }
    public static SkillCatalog load(Options opts);   // 坏技能跳过 + 诊断，不静默截断
    public List<Skill> all();                        // 含仅用户可调用的
    public List<Skill> modelVisible();               // 排除 disable-model-invocation
    public List<String> warnings();                  // 装配期诊断（宿主可见，不进模型）
    public String render();                          // 清单文本（纯函数，逐字节稳定）
    public SkillReloadResult reload();               // 显式、幂等、全量、原子快照替换
    public SkillCatalog.Expansion expandInvocation(String text);   // `/name` 手势
    public String renderContent(Skill s);             // 正文现读 + 超预算报错
}

public final class SkillTool implements Tool {      // 模型可见名：skill
    public static final String NAME = "skill";
    public record Params(@ToolParam(desc = "...", required = true) String name) {}
    public void setState(SkillState state);          // 轮级状态（driver 每轮换）
    public void setAudit(SkillAudit audit);          // 会话级审计器（driver 每轮绑定）
}

// ---- mos4j-core：审计只增一个接口 ----
public interface SkillAudit {
    void activate(String name, String description, String dir) throws IOException;
    void allowedToolsMismatch(String name, List<String> missingAllowed) throws IOException;
}

// ---- mos4j-agent 门面（未装配技能目录时全部降级，不需空判断）----
public List<Skill> skills();                        // → List.of()
public SkillReloadResult reloadSkills();            // → unchanged()
public SkillCatalog.Expansion expandSkill(String text);  // → Expansion(text, false)
```

**契约要点**：

| 项 | 口径 |
|---|---|
| 工具面 | 13 → **14**，`skill(name)` **条件注册**：只有 `modelVisible()` 非空才进注册表（能力即证据） |
| 参数 | 只有 `name`（`required=true`）；其余没有任何参数 |
| 正文 | **调用时现读**，不驻留快照；改了 `SKILL.md` 立即生效（不用重扫） |
| 仅用户可调用 | `disable-model-invocation: true` → 模型调用报错（「仅限用户显式调用」）；`/name` 手势仍可用 |
| 审计顺序 | **先审计后返回正文**：`skill/activate` 写失败 → 本次调用失败，正文不外泄（fail-closed） |
| mismatch | `allowed-tools` 与当前可见工具集求交 → `skill/allowed-tools-mismatch`；**写失败被吞**（不拦加载）；**不改变执行工具面** |
| 投影 | skill 事件**不进消息投影**（与 `approval/*` 同口径），只进 append-only 日志 |
| 提示词位置 | `PromptSections.CANONICAL_ORDER` = `base/environment/context/skills/instructions/tools`；宿主把 prompt 当整串交过来时用 `PromptSections.insert(prompt, "skills", catalog)` |
| 重扫通知 | 清单变化走 **user 侧完整替换通知**（`SkillReloadResult.notice()`，内容是当前完整清单）；**静态系统提示词不重渲染**（保 prompt cache） |
| 正文标签转义 | 只转义正文里的 `<skill_content` / `</skill_content` → `&lt;...`（防提前闭框），其余尖括号原样保留 |
| digest | `reload()` 的变更判定只含**元数据**（name / description / whenToUse / disableModelInvocation / sorted tools / dir），不含正文 |
| 沙箱 | 技能目录是**宿主给的路径**，不走工作区 jail；正文只经 `skill` 工具拿，不进 `read` 的文件工具面 |

### 3.10 路径强制的出口与协议旋钮（Q1 / Q2′ / Q4 / Q5，2026-11-07 已落地）

> 评审问题 Q1–Q8 的**实施产物**（Q3/Q8 维持现状）。**不改契约清单、不改事件名、不改 12 模块边界**。

```java
// ---- mos4j-runtime：动态写根 + 可用性探测 ----
public interface PathScopeFactory {                       // 平台分支不上浮到门面
    PathScope create(List<Path> writeRoots);
}

public interface PathScopeProbe {                          // 「这台机器能不能真用起来」
    Result probe();
    record Result(boolean available, String detail) {
        public static Result available(String detail);
        public static Result unavailable(String detail);
    }
}

// LocalRunner.Builder（mos4j-runtime）
public Builder pathScope(PathScope scope);                             // 静态装配
public Builder pathScopeSupplier(Supplier<List<Path>> writeRoots);    // 动态：每次 run 现取
public Builder pathScopeFactory(PathScopeFactory factory);            // 按写根现造 scope
public Builder noPathScope(String reason);                             // 显式关闭（记录原因）
```

```java
// ---- mos4j-agent：门面出口（Q1 / Q4 / Q5）----
public Builder pathScope(PathScope scope);            // 宿主指定（也要探可用性）
public Builder noPathScope();                         // 显式关闭（Describe 会声明）
public Builder pathScopeProbe(PathScopeProbe probe);  // 注入探针（测试 / 受管宿主）
public Builder requireFinish(Boolean requireFinish); // 只对 Gemini 生效；用错 → fail-fast
public Builder maxTokens(Integer maxTokens);         // 模型级缺省 → Config → ReActPlanner → Request

// 静态缺省模型装配（starter 也走这条；协议校验在这里，**唯一真源**）
public static ChatModel defaultModel(Protocol protocol, String apiKey, String baseUrl,
                                     String model, Map<String, String> env, Boolean requireFinish);
```

```java
// ---- mos4j-agent：模型级输出上限（不改 Request 结构）----
Config.Builder.maxTokens(Integer maxTokens);   // → Config.defaultMaxTokens() → ReActPlanner → Request.maxTokens
```

```yaml
# ---- mos4j-spring-boot-starter（新增 3 个属性，共 26 个）----
mos4j:
  path-scope: auto        # auto（缺省：探一次，不通就启动失败）| disabled（显式不装）
  require-finish: false   # 只对 Gemini 生效；用错协议 → 启动失败
  max-tokens: 4096        # 输出上限（进每个请求）
```

```bash
# ---- CLI（mos4j-agent）----
mos4j --no-path-scope "输入"   # 显式不装路径强制（受限 macOS 主机）
```

**契约要点**：

| 项 | 口径 |
|---|---|
| 显式关闭 | `noPathScope()` **不是静默降级**：Describe 的 caveat 写「路径强制=未装配（原因：宿主显式关闭…）」+「shell 可写 jail 之外的目录」 |
| 探测时机 | **装配期**（不让模型在第 N 轮才说「命令失败了」）；不通 → 抛 `IllegalStateException`，文案给两条出路（换机器 / 显式关闭），并声明「不会退化成没隔离的假装隔离」 |
| 探针可注入 | `Mos4j.Builder.pathScopeProbe(...)` / starter 的 `PathScopeProbe` Bean——测试与已知受管主机不必真跑 apply |
| 动态写根 | 每次 `run()` 现取写根 + 现造 scope；写根为空 / 来源抛错 → **fail-closed**（命令不启动） |
| 快照口径 | 静态装 / **动态装（说「已装配（动态写根）」而不是谎报未装配）** / 未装（并说清是「平台没有后端」还是「宿主显式关闭」） |
| `requireFinish` | 只对 Gemini 生效；用在其他协议或宿主自带 `ChatModel` 上 **fail-fast**（不静默忽略） |
| `maxTokens` | 模型级缺省，链路 `sdk → Config → ReActPlanner → Request`；不配就留空（用协议自己的缺省） |
| 请求期 scope | Go 的 `sandbox.Request.PathScope` **有意不补**（Go 自己的 shell 工具也不传它）→ 登记为有意差异（D-J8-7） |

---

### 3.11 MCP 接入（`mos4j-tool` 的 `net.hegq.mos4j.mcp` 包，M0–M3 已落地）

> 完整口径见 [`MCP-STATUS.md`](MCP-STATUS.md)。**2026-11-08 起 MCP 已并入 `mos4j-tool`**，进默认 reactor（`./mvnw verify` 就带它）；官方 MCP SDK 在本模块是 **optional 依赖**，宿主要用 MCP 须自己显式声明官方 SDK 坐标。

| 能力 | API | 口径 |
|---|---|---|
| 接上服务器 | `McpKit.attach(registry, configs)` | 刻意**不**放进 SDK 门面：官方 SDK 的传递依赖（slf4j / reactor / json-schema-validator）不进六模块基线 |
| 编排 | `McpManager.start/reload/snapshot/close` | 两阶段换代（先并发取全量，再整体落盘）；失败隔离；scope 隔离（`mcp:<server>`）；失败保留上一代 |
| 曝光三档 | `McpExposure.DIRECT/DEFERRED/HIDDEN` | 逐工具覆盖：精确名 > `"*"` > 服务器默认。hidden = 模型看不见、也搜不到，但宿主仍可程序化调用 |
| 可见性槽 | `ToolRegistry.setVisibilitySlot(slot, filter)` | 具名槽：同名整层替换，多槽 **AND** 叠加，传 `null` 移除；过滤器必须纯判定 |
| scope 换代 | `ToolRegistry.replace(scope, tools, deferred)` | 先校验整批（重名 / deferred 含名单外工具 / schema 失败）再落盘；失败时注册表**保持原样** |
| 说明注入 | `Config.mcpInstructions(Supplier<String>)` | 每会话一次（`Session.claim`）；走 user 侧（写一条 user 事件），不进静态系统提示词；32KB 预算**整段**省略 |
| 快照 | `McpSnapshot` | 每台服务器：状态（CONNECTED / FAILED / DISABLED）+ 各档工具数 + 失败原因 |
| **认证** | `McpServerConfig.withTokenProvider(McpTokenProvider)` | 每次请求取一次、**不缓存**；空串不注入 `Authorization`；取凭据失败 = 认证类错误 |
| **认证分类** | `McpError.Kind.AUTH` / `McpError.auth(msg, cause)` | 401 / 403 归**认证类**（不是传输、不是协议） |
| **资源契约** | `McpClient.listResources() / listResourceTemplates() / readResource(uri)` | 官方 SDK 类型不外泄；二进制只给长度与类型 |
| **资源工具** | `McpResourceTools.LIST_NAME / TEMPLATES_NAME / READ_NAME` | 三个**共享**工具（`mcp_resource_list` / `mcp_resource_templates` / `mcp_resource_read`）；工具面**恒为 3**，显式 `server` 参数路由；不进内置 14 工具冻结清单 |
| **分节** | `McpKit.serversSection()` + `PromptSections` 的 `mcp_servers` | 中心顺序：技能清单 → **MCP 服务器摘要** → 工具指引 |
| **Describe 补充** | `McpKit.describeContributor()` | 工具层往装配层的槽写装配事实；该 SPI 下沉到 `net.hegq.mos4j.core`（零依赖），避免 tool → agent 与 agent → tool 成环 |
| **结果投影** | `McpCallResult.toModelText()` / `opaqueParts()` / `textContent()` | 文本直接给模型；非文本给**按类型分开**的占位诊断（image / audio / resource / resource_link / 未知）；原始 JSON 上限 2048；类型名与原始内容留在 `opaqueParts()` 里给程序化调用方 |
| **参数校验** | `McpToolAdapter` 本地校验参数是 JSON 对象 | 不是对象 → **协议层**错误（fail-closed）；上游没给结果 → 协议层错误（不当成空成功） |

**有意差异**：stdio 无工作目录（D-MCP-1）、公开名截断保证合法 UTF-8（D-MCP-2）、调用期超时暂归协议层（D-MCP-3）、跨 scope 同名直接报错（D-MCP-4）、会话级标记只在内存（D-MCP-5）。

---

### 3.12 policy 可见性 / 模型装饰器 / 模型目录 / Describe 与关闭（2026-11-07 已落地）

| 能力 | API | 口径 |
|---|---|---|
| policy 单一状态源 | `PolicySlot`（`install/policy/allows/check`） | **可见性与运行时守卫读同一份名单**——两份名单各写一遍就会出现「模型看得见却调不动」或「看不见却能调」 |
| 装配 policy | `Config.Builder.policy(ToolNamePolicy)` / `Mos4j.Builder.policy(...)` | `Agent.of` 把可见性槽装到注册表（槽名 `policy`）；宿主自带 `GuardBus` 时复用同一条总线 |
| 热替换 policy | `Agent.setPolicy(ToolNamePolicy)` | 只换 policy 层，**不覆盖 MCP hidden 槽**（槽具名独立、AND 叠加） |
| 装饰器基类 | `ChatModelDecorator` + `delegate()` | **显式嵌套**，不引入隐式全局中间件链：组合顺序由调用方决定 |
| 日志装饰器 | `LoggingChatModel(model, sink)` + `CallRecord` | 成功或失败**统一上报一次**；回调自身抛错**继续传播**（不让观测影响主流程） |
| 超时装饰器 | `TimeoutChatModel(model, duration)` | 协作式取消；**外部取消优先于超时**；外部已取消时**不调用模型**；超时抛 `ModelTimeoutException` |
| 降级装饰器 | `FallbackChatModel(primary, backup)` | 只在主模型**运行时失败**时降级；`CancellationException` **不降级**（取消不是失败）；能力集取并集 |
| 缓存装饰器 | `CachingChatModel(model, ttl)` | **只缓存非流式**成功结果；`ttl <= 0` 不过期；取消检查**先于**缓存命中；失败 / 空响应不缓存；命中返回同一个 `Response` 对象；这是**本地**缓存，与服务端 prompt cache 不是一回事（对齐 Go `llm.WithCache`，键口径见 SI-50） |
| 模型目录 | `ModelOption`（`net.hegq.mos4j.model.catalog`）/ `Config.Builder.models(...)` / `defaultModelId(...)` | 保序；重复 ID 与未知 ID **fail-closed**；切换**下一轮生效**；**凭据只留在模型对象里**，不进快照与公开描述 |
| 运行期切换 | `Agent.setModel(id)` / `modelId()` / `models()` | 不重建 Agent |
| Describe 补充者 | `DescribeContributor.contribute(facts, caveats)` | 贡献者失败转成 caveat，**不让整个快照不可用** |
| 关闭 | `Config.Builder.closer(AutoCloseable)` + `Agent.close()` | `Agent implements AutoCloseable`；**幂等**；逐个关；一个关不掉**不阻止**关别的；最后抛包装后的 `IllegalStateException` |

**明确不做**：Windows 后端；Starter 接 MCP；模型目录的凭据持久化。

---

### 3.12.1 技能（skill）的 CLI 入口与并发口径（2026-11-07 已落地）

| 能力 | API | 口径 |
|---|---|---|
| CLI 技能目录 | `mos4j --skills DIR`（**可重复**） | 目录里放 `SKILL.md`；转给 `Mos4j.Builder.skills(Path...)`；技能清单进**系统提示词**（会话日志不记 system prompt）；Go 的 CLI 没有这个开关 → 属自创参数面（SI-38） |
| 并发 reload | `SkillCatalog.reload()` | 换代是**原子**的（`AtomicReference` 换快照）：并发 reload + 并发读不会看到半套状态（重名 / 半截清单 / 空快照） |

---

### 3.13 对外版本号（发布收口，2026-11-07 已落地）

| 能力 | API | 口径 |
|---|---|---|
| 版本号真源 | 父 POM 的 `<version>`（`net.hegq:mos4j:0.1.0-SNAPSHOT`） | **只有这一处**是真源；改版本号只改它 + `Version.VERSION` |
| 对外投影 | `net.hegq.mos4j.core.Version.VERSION` / `Version.version()` | 纯常量，**不读** `META-INF/maven/…/pom.properties`（开发期与发布期取值必须一致）；不含构建时间戳（可复现构建） |
| 门面出口 | `Mos4j.version()` | 宿主写日志 / 贴 issue / 报服务端的统一出口，不要各处拼字符串 |
| CLI | `mos4j --version` | 打印 `mos4j <version>`，退出码 0 |
| 机械门 | `VersionTest`（3 条：格式 / 与父 POM 对齐 / 访问器一致） | 改了 POM 忘了改常量 → 发布前变红 |

---

### 3.14 Go 差异补齐批次（#5 / #7 / #9，2026-11-07 已落地）

> 背景：2026-11-07 的源码级初查识别出 10 处 Go↔Java 差异，用户选定**只补这三项**。Go 原测试名对位见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.8。

#### 3.14.1 上下文文件装配（#7）

| 能力 | API | 口径 |
|---|---|---|
| 装配入口 | `Mos4j.Builder.contextFiles(Path dir)` | 目录**不存在或不是目录** → 装配期失败（fail-closed，宿主配置错了不是可降级故障） |
| 候选名 | `contextFiles(List<String> candidates, Path dir)` | 缺省与 Go 同名单同顺序：`AGENTS.override.md` > `AGENTS.md` > `AGENTS.MD` > `CLAUDE.md` > `CLAUDE.MD`（同目录取第一个命中） |
| 读取器 | `contextFileReader(ContextFileReader reader)` | 缺省读本地文件系统；虚拟文件系统宿主注入自己的读取器（`readIfExists` / `realPath` / `parentOf` / `join` / `isDirectory`） |
| 字节预算 | `contextFileByteBudget(int bytes)` | 缺省 32KB；**负数 = 不限**；裁剪优先落在行边界；预算用完 → 后续文件不加载 + warning |
| 递归层数 | `contextFileMaxDepth(int depth)` | 缺省 **0**（只看起始目录本身）；负数 = 不限（走到顶） |
| 全局目录 | `contextFileGlobalDirs(List<String> dirs)` | 缺省 `~/.mos`（`~` 自动展开）；给空列表 = 不读全局目录 |
| 进提示词 | 装配期把合并正文追加到**系统提示词**末尾 | 包装成 `<project_instructions path="...">` 块，块前有标题「项目约定与指引（来自以下上下文文件）：」；**不进会话 JSONL 日志** |
| 降级留痕 | `Describe()` 的 features / caveats | 文件数与字节数进 features；读失败 / 裁剪 / 丢弃 / 预算不足进 caveats（**不静默丢内容**） |
| Starter | `mos4j.context-files.{dir,candidates,byte-budget,max-depth,global-dirs}` | 相对路径按 `mos4j.workspace` 解析（不是进程 CWD）；**不配就不装**（自动扫工作区会让每次启动的提示词都可能不一样） |

#### 3.14.2 运行期根目录管理（#9）

| 能力 | API | 口径 |
|---|---|---|
| 加根 | `Agent.addRoot(String id, Path dir)` | 读写都放行；**立即生效**（边界每次调用现判）；挂载名重复 → 报错（不静默覆盖） |
| 只读根 | `Agent.addReadRoot(String id, Path dir)` | 能读不能写（`read` 放行，`write` / `edit` 拒绝） |
| 只写根 | `Agent.addWriteRoot(String id, Path dir)` | 能写不能读 |
| 移除根 | `Agent.removeRoot(String id)` | 收窄边界；**锚点根不可移除**；移除不存在的根 → 报错（不假装成功） |
| 查看 | `Agent.roots()` | 当前全部根的快照（不可变） |
| 未装根集合 | `Agent.addRoot(...)` / `roots()` | **明确报错**（`UnsupportedOperationException`）——不假装能管根 |
| 边界一致 | 同一份 `RootSet` 同时进文件工具与 shell 沙箱 | 会话里加的根，**命令也跟着能写**（不出现「工具能写、命令写不了」的错位）；**只读根不进 shell 的写范围**（写根只取 `writable` 的根，对齐 Go 的 `RootsFor(OpWrite)`） |

> **与 Go 的形状差异**：Go 是 `AddRoot(dir)`（按目录路径），Java 是 `addRoot(id, dir)`（按**挂载名**，与 `RootSet` 既有语义一致）——登记为 SI-42。

#### 3.14.3 压缩三开关（#5）

| 能力 | API | 口径 |
|---|---|---|
| 显式关闭 | `Mos4j.Builder.disableCompaction(boolean)` | 与已装的 `compaction(...)` **同时出现 → 装配期报错**（Go 是「显式关闭优先」，Java 是 fail-fast → SI-39） |
| 摘要超时 | `Mos4j.Builder.summaryTimeout(Duration)` | 缺省 3 分钟；`Duration.ZERO` = 不限；**负值 → 拒绝**（Go 用负数表示不限 → SI-40）；超时经虚拟线程隔离，超时后取消摘要任务 |
| 失败策略 | `compactionFailurePolicy(CompactionFailurePolicy)` / `onCompactionFailure(Consumer<Exception>)` | 缺省 `FAIL_CLOSED`（本轮失败）；`SKIP_AND_CONTINUE` → 记提示、跳过压缩、本轮继续（`turn/end.reason="completed"`） |
| 不写失败事件 | — | 冻结字段清单里没有 `failed` 字段 → 压缩失败**不写** `compaction` 事件（半截事件会破坏投影）；失败原因只经回调交给宿主 |

### 3.15 Go 差异补齐批次（#1 / #2 / #3 / #4 / #6 / #8 / #10 / #11 / #12 / #13，2026-11-08 已落地）

> 背景：2026-11-07 的源码级初查 + 同日逐行复核共识别出 13 处 Go↔Java 差异，全部在本批次补齐。逐条差异语义见 [`GO-DIFF.md`](GO-DIFF.md)，自创口径见 [`SELF-INVENTED.md`](SELF-INVENTED.md)（SI-43…SI-51）。

#### 3.15.1 子代理（#1）

| 能力 | API | 口径 |
|---|---|---|
| 装配入口 | `Mos4j.Builder.subAgents(Path... dirs)` / `subAgentDirs(List<String> dirs)` | 目录**不存在或不是目录** → 装配期失败；目录列表本身就是**授权范围**（框架不自行扫描文件系统） |
| 读取器 | `subAgentReader(SubAgentReader reader)` | 读文件与列目录都走注入的读取器（虚拟文件系统宿主用） |
| 定义文件 | 固定 `AGENT.md` | frontmatter 字段：`name` / `description` / `model` / `tools`；名字缺失 → 回退目录名；**描述缺失 → 定义不安装** |
| 清单预算 | `subAgentMaxCatalogBytes(int)` | 缺省 8KB；超预算**先丢描述（留名字）再丢条目**，并如实写明省略数量 |
| 超时 | `subAgentTimeout(Duration)` | 缺省 5 分钟；`Duration.ZERO` = 不限；**负值拒绝** |
| 运行器接缝 | `subAgentRunner(SubAgentRunner runner)` | 宿主可注入自定义 runner（测试与离线场景用） |
| 工具名 | `subagent__<name>` | 名字长度上限 64；**默认独占**（不声明 `concurrencySafe=true`） |
| 工具参数 | 只有必填 `task` + 可选 `context` | 参数 schema 不带 `title` / `$defs`（与既有工具面同一约定） |
| 工具面 | 声明白名单 ∩ **父当前可见工具集** | 父可见集是安全上限（deny 优先）；恒定排除 `subagent__*` 与 `tool_search`；子代理深度固定为 0 |
| 模型覆盖 | frontmatter 的 `model` | 覆盖模型不在宿主模型目录 → **装配期硬失败**；未覆盖 → 继承父模型 |
| 子会话 | `SubAgentSessionFactory.open(String id)` | 独立会话（不与父会话混用）；SDK 缺省工厂用实际 `JsonlPersistence` |
| 清单位置 | 系统提示词 `subagents` 段（中心顺序） | 装配期渲染一次即固定（保 prompt cache）；**子代理正文调用时现读**（改了 `AGENT.md` 无需重扫） |
| 回执 | 固定 `{status, summary, findings, error}` | `status` ∈ `completed` / `error` / `timeout`；非 JSON 最终回答**整段降级为 `summary`**；非法回执 → `status=error` 的普通工具结果 |
| 失败语义 | 子代理失败 / 超时 / 坏回执 | 全部转成**普通工具结果**，不让父轮次直接失败（父模型看见的是一次失败的调用，不是崩溃） |
| `ToolContext` | `ToolContext(turn, sessionId, cancellation)` | 工具执行上下文放 `mos4j-core`（不让 tool 层反向依赖 session 层）；`Tool.execute(String, ToolContext)` 默认回退旧 `execute(String)` |

#### 3.15.2 续跑（#2）

| 能力 | API | 口径 |
|---|---|---|
| 入口 | `Mos4j.resume(String sessionId, ResumeOptions)` / `Agent.resume(Session, ResumeOptions)` | **宿主显式指示后才执行**；不自动拾取挂起轮次（那等于替宿主决定副作用要不要重做） |
| 选项 | `ResumeOptions.latest()` / `ofTurn(int)` / `withCalls(String...)` | 沿用**原轮次号**（审批决定的复用键是 `(轮次, 调用 id)`）；不指定轮次 → 最近一轮 |
| 无可续 | `NothingToResumeException`（checked） | 抛异常且**那一轮保持挂起**（不写 `turn/end`：收尾等于把「还欠一次调用」的事实抹掉） |
| 载荷 | 从日志**原样重放** | 不重新构造参数（重放 = 可对拍） |
| 执行 | 走**普通 ExecPath 安全链** | 审批 / 守卫 / 钩子 / 投影 / 审计一个不少；回填**真实** `tool/result` |
| 循环 | 与普通轮**共享同一个 ReAct 决策循环** | 续跑只在前段补齐悬空调用，不另造绕过安全链的平行链 |

#### 3.15.3 审批先验（#3）

| 能力 | API | 口径 |
|---|---|---|
| SPI | `ApprovalPrior.lookup(int turn, String toolName, String callId)` | core 的**只读**接口；当前轮次由 `ExecPath.Options.turn` **外部绑定**（`ApprovalRequest` 没有 `turn` 字段） |
| 实现 | `SessionApprovalPrior(Session)` | `mos4j-session` 提供（读日志，不写） |
| 复用条件 | 同轮 + 同 call id + **同工具名** + 调用**仍悬空** | 已完成的调用**不**复用旧批准（否则一次批准被放大成无限次） |
| 可复用的决定 | `APPROVED` / `REJECTED` | `CANCELLED` / `UNAVAILABLE` **不可**复用（不是真决定） |
| 审计 | 复用时**签发新审批 id**，旧 id 写进新 `approval/asked.reason` | `approval/decided` 仍只写 `outcome`；**不新增 `reusedFrom` 字段**（冻结字段清单不能动，SI-46） |
| 通道检查 | 未装审批通道时即使命中 prior 也 **fail-closed** | 不因为「以前批过」而绕过通道 |
| 装配 | `ExecPath.Options.withPrior(int turn, ApprovalPrior prior)` | 普通轮与续跑轮**都装** prior（prior 不是续跑特例） |

#### 3.15.4 会话派生读（#4）

| 能力 | API | 口径 |
|---|---|---|
| 待答审批 | `Session.pendingApprovals()` | 按 **approval id** 配对 `approval/asked` / `approval/decided` |
| 审批结果 | `Session.approvalOutcome(String id)` | 返 `ApprovalOutcome`（`approved` / `rejected` / `cancelled` / `unavailable`） |
| 同轮决定 | `Session.approvalDecision(int turn, String callId)` | 先验复用的匹配键 |
| 中断轮次 | `Session.interruptedTurn()` | `turn/start` 有、`turn/end` 无或 `interrupted` 的最后一轮 |
| 悬空调用 | `Session.hangingCalls()` → `List<HangingCall>` | `record HangingCall(int turn, String callId, String toolName, String args, long seq)`；按 **tool call id** 配对 |
| 纯读 | 五个方法都不写日志、不改状态、不推进序号、不新增事件类型 | **空 call id 无法配对，不算悬空**；同一 call id 只要已有一次 result，后续同 id call 不再算悬空 |
| 与投影的区别 | 投影给孤儿调用合成**失败回执**（给人看） | 派生读报的是**日志事实**（宿主据此决定要不要续跑）——两者不可互相替代 |

#### 3.15.5 协议注册表（#6）

| 能力 | API | 口径 |
|---|---|---|
| 注册 | `ModelRegistry.register(String name, Factory factory)` | 工厂签名 `ChatModel create(Map<String, String> config) throws Exception`；同名注册**覆盖** |
| 创建 | `ModelRegistry.create(String name, Map<String, String> config)` | 未知名字 → **fail-closed** 并列出已注册名字 |
| 名字列表 | `ModelRegistry.names()` | 排序且不可变；另有 `contains(String)` / `size()` |
| 边界 | 只做「名字 → 工厂」映射，**生产裸 `ChatModel`** | 横切能力（日志 / 超时 / 回退 / 缓存）仍由调用方用装饰器**显式组合**，不引入隐式全局顺序；包位置 `net.hegq.mos4j.model.catalog`（与 `ModelOption` / `SwitchableChatModel` 同包） |

#### 3.15.6 可注入提示词构建器（#8）

| 能力 | API | 口径 |
|---|---|---|
| 注入 | `Mos4j.Builder.prompt(PromptSections.Builder)` / `Config.Builder.prompt(...)` | 与 `systemPrompt(...)` **互斥**：同时给 → fail-closed；**空白整串按未提供处理** |
| 框架装配 | 在宿主 Builder 的**副本**上添加 `context` / `skills` / `subagents` 分节 | 宿主持有的那份**保持原样**；宿主已放同名段 → **明确报错**（不静默覆盖、不重复添加） |
| 中心顺序 | `base` → `environment` → `context` → `skills` → `mcp_servers` → `subagents` → `instructions` → `tools` | `PromptSections.Builder.has(String)` 让宿主能问「这段有没有被占」 |
| 老路径 | `PromptSections.insert(rendered, name, content)` | 整串提示词路径继续可用（技能 / 子代理清单按中心顺序插入） |

#### 3.15.7 取消原因（#11）

| 能力 | API | 口径 |
|---|---|---|
| 带因取消 | `CancellationToken.cancel(Throwable reason)` | **首次**取消携带的原因生效（先到先得），后续取消不覆盖 |
| 读原因 | `CancellationToken.cause()` | 未取消或无原因 → `null`（「没被取消」不是「被取消了」） |
| 无因取消 | `CancellationToken.cancel()` | 保留原语义（`cause()` 仍为 `null`） |
| 解释 | `throwIfCancelled()` / `turn/end.error` / driver 抛出的异常 | 都解释具体原因；**不新增冻结事件字段**（原因只进已有的 `error` 字段） |
| 传播 | `TimeoutChatModel` | 外部取消原因向内传播；超时取消不带外部原因 |

#### 3.15.8 运行期可调参数（#12）

| 能力 | API | 口径 |
|---|---|---|
| 并发上限 | `Agent.setConcurrency(int)` / `Agent.concurrency()` | 每轮开始时现取；仍受**显式 opt-in** 约束 |
| 决策轮数上限 | `Agent.setMaxDecisionRounds(int)` / `Agent.maxDecisionRounds()` | 驱动**每轮开始时**读取当前轮数上限 |
| 参数级规则 | `Agent.setRules(ParamPolicy)` / `Agent.rules()` | 装配期必须先装过 `ParamPolicy`（`Config.Builder.paramPolicy(...)`），否则 **fail-closed**；工具执行前读取当前值 |
| 槽位隔离 | 名字级 `PolicySlot` 与参数级 `ParamPolicySlot` **状态独立** | 热替换参数规则不动可见性名单 |

#### 3.15.9 本地缓存装饰器（#13）

| 能力 | API | 口径 |
|---|---|---|
| 装配 | `new CachingChatModel(ChatModel delegate, Duration ttl)` | 只缓存**非流式**成功结果；**流式调用不缓存**；与服务端 prompt cache **不是一回事** |
| 过期 | `ttl <= 0` | 表示**不过期** |
| 命中 | 返回**同一个 `Response` 对象** | 缓存响应按**共享只读对象**处理 |
| 取消 | 取消检查**先于**缓存命中 | 已取消的调用不返回缓存 |
| 不缓存 | 失败 / 空响应 | 都不进缓存 |
| 缓存键 | 消息正文、reasoning、tool call、工具清单、schema、采样参数、response format、modalities | schema map 键**递归排序**后取 SHA-256（顺序不同 → 不同键） |
| 维护 | `size()` / `clear()` | 当前**没有** per-key single-flight（同键并发会各调一次下游） |

#### 3.15.10 示例程序集（#10）

| 项 | 口径 |
|---|---|
| 位置 | `examples/`（8 个示例模块 + 共享 `examples/testkit`） |
| 构建 | 默认**不进 reactor**：`./mvnw -Pexamples verify`；MCP 示例再叠 `-Pexamples,mcp`（局部 profile） |
| 依赖 | 每个示例**只声明自己真的 import 到的模块**（不靠聚合层或传递依赖「恰好能编译」）；`testkit` 仅 **test scope** |
| 用例 | 脚本化模型替身（`StubModel`）真跑装配、工具链与循环；**不碰网络**（示例需要真 API Key，CI 跑不了） |
| 目录 | `minimal` / `model` / `chat` / `agents` / `skills` / `subagent` / `recovery` / `mcp`，每个目录一个 README |

**发布清单**见 [`docs/release.md`](../release.md)；**发布说明草稿**见 [`CHANGELOG.md`](../../CHANGELOG.md)。

---

### 3.16 多模态内容块与 thinking 回传（2026-11-08 二次复核修复）

> 背景：`ContentPart` 的 `audio` / `file` 与 `Message.reasoningContent` 此前只有**字段声明**没有出站映射，
> 由出站报文抓包实测发现，已按 Go 口径补齐。守护用例均**断言出站报文**，不断言内部对象。

#### 3.16.1 `reasoning_content` 回传（#15，仅 OpenAI 兼容协议）

| 项 | 口径 |
|---|---|
| 映射源 | `Message.reasoningContent`（投影消息字段，一直存在） → 请求 DTO 的 `reasoning_content` |
| 生效条件 | **只对 `role=assistant` 且非空**；其余情况不输出该键（`NON_NULL`） |
| 为什么必须有 | DeepSeek / Qwen thinking 模式**要求**回传上一轮思维链，不回传是第二轮 **400**，不是尺寸小点小号选项 |
| 范围 | **只 OpenAI 兼容协议**；Anthropic 侧 Go 也不回传 thinking 块（不算缺口） |
| Go 对照 | `llm/openai/openai.go` 的 `injectReasoningContent`（字节级注入） |
| 守护 | `OpenAiChatModelTest.reasoningContent_isEchoedOnAssistantMessages` / `reasoningContent_absentWhenEmptyOrNonAssistant` |

#### 3.16.2 `ContentPart` 的四类型 × 三协议映射矩阵

| type | OpenAI | Anthropic | Gemini |
|---|---|---|---|
| `text` | `{"type":"text","text":...}` | `{"type":"text","text":...}` | `{"text":...}` |
| `image` | `image_url`（URL 优先，否则 `data:\u003cmime\u003e;base64,\u003cdata\u003e`，mime 缺省 `image/png`） | `image`（URL 源或 base64 源，mime 缺省 `image/png`） | 有 URL → `fileData`（MIME **原样**），否则 `inlineData`（mime 缺省 `image/png`） |
| `audio` | `input_audio`（`data` + `format`；format 按 mime 映射：`mpeg/mp3`→`mp3`、`wav/x-wav`→`wav`、`ogg`→`ogg`、`aac`→`aac`，未知→`mp3`） | **不发块**（Go 的注释写「被忽略」；代码实际落入 `default` 发了个空白 text 块，不照抄） | 有 URL → `fileData`（MIME **原样**），否则 `inlineData`（mime 缺省 `image/png`） |
| `file` | **`image_url`**（同 `mediaURL` 口径，对齐 Go `toOpenAIContent` 的 file→image_url） | `{"type":"document","source":{"type":"base64","media_type":\u003cmime\u003e,"data":\u003cdata\u003e}}`（mime **原样**，**不**补 `image/png`） | **只** `fileData`（`{"mimeType":...,"fileUri":...}`） |
| 其他 | **抛 `IllegalArgumentException`**（SI-53） | **不发块**（静默跳过，同其他无对应块的类型） | **抛 `IllegalArgumentException`**（SI-53） |

- 不变式：**绝不发空壳块**（只有 `type` 没有内容的块）。修复前 `file` 出站正是 `{"type":"file"}`。
- Go 对照：`toOpenAIContent` / `anthropicContent` / `geminiParts`（三者对未知类型的处理都是当 `text` 兜底，此处**有意收紧**，见 SI-53）。
- 守护用例：`OpenAiChatModelTest` 6 条（含 `audioFormat_mapsMimeToWireFormat`）、`L1AnthropicScenariosTest` 2 条、`L1GeminiScenariosTest` 5 条（含 `fileData` 与 `inlineData` 的 MIME 缺省分野）。



---

### 3.17 wire 层：协议无关的 SSE 分帧与流终止判据（2026-11-18 已落地）

> **地位**：`mos4j-model` 内新增 `net.hegq.mos4j.model.wire` 子包——协议无关的「线机械」层，
> 服务两个场景：mos4j 自己（协议 → 统一模型投影）与 eucp-ai 网关（厂商协议原样透传）。
> 依赖纪律不变（Jackson / RE2J / JDK）；不引厂商 SDK。

#### 3.17.1 分帧：`SseEvent` + `SseEventReader`

| 项 | 口径 |
|---|---|
| `SseEvent` | record：`event()` / `id()` / `data()` |
| 派发条件 | 空行派发；**只有 data 缓冲非空才派发**（注释 + 空行 = 无事发生；空 data 事件照发，保留旧「空 data 行 = 关流」语义） |
| 字段 | `event:` / `id:` 归位；`id` 跨事件保留（lastEventId）；多行 data 用 `\n` 连接；`: 注释`跳过；字段值去一个前导空格；BOM 剔除；`retry:`/未知字段忽略 |
| 有意偏差 | EOF 时**宽松派发**未完成事件（对齐旧行级实现的即时处理）；「流算不算完整」由终止规则判定，不进分帧层 |
| 守护 | `SseEventReaderTest` 11 条 + fuzz 扩展（截断流与随机垃圾不抛异常） |

#### 3.17.2 终止：`StreamTermination<T>` + 三协议 Rules

```java
// net.hegq.mos4j.model.wire —— 每条流一个实例，非线程安全
public interface StreamTermination<T> {
    default boolean observeRaw(String data);  // 原始层哨兵（如 [DONE] / 空 data 行）→ 返回 true 停止读取
    void observe(T event);                    // 事件层证据
    boolean isFinal();                        // 事件层终止事件已到（如 message_stop）→ 停止读取
    boolean streamIncomplete();               // 流读完判定：true = 截断（机制 M5）
}
```

| 协议 | Rules 类 | observeRaw | isFinal | streamIncomplete |
|---|---|---|---|---|
| OpenAI | `OpenAiStreamRules` | `[DONE]` / 空 data 行 → true | 恒 false（停止发生在原始层） | 无 finish_reason 且未收到哨兵 |
| Anthropic | `AnthropicStreamRules` | 空 data 行 → true | `message_stop` | 无 stop_reason 且未收到哨兵；另有 `isErrorEvent` / `errorMessage`（流中 error 事件 = 服务端失败，非截断） |
| Gemini | `GeminiStreamRules.termination(boolean requireFinish)` | 空 data 跳过 | 恒 false（无哨兵，读到 EOF；usageMetadata 在 finishReason 之后，提前停会丢） | `requireFinish && 无 finishReason`（逃生阀，缺省 true） |

- 口径不变（机制 M5）：**结束标记或结束原因任一到即完整；两者皆无才是截断；结束原因为空 ≠ 截断**。
- 三个 `*ChatModel` 已全部改走 `SseEventReader` + Rules（**M5 行为零变化**，L1 全量回归绿）；终止判据只有这一份，不允許消费方各自抄。

#### 3.17.3 eucp-ai 消费面与边界

| 项 | 口径 |
|---|---|
| 稳定 wire API | `net.hegq.mos4j.model.transport.*` + `net.hegq.mos4j.model.wire.*` + `protocol.<方言>.<kind>` 的 Config/Rules（例：`net.hegq.mos4j.model.protocol.openai.chat.OpenAiConfig` / `…OpenAiStreamRules`） |
| 明确不含 | `ChatModel`、统一模型投影、协议 DTO 的内部解析细节 |
| 厂商 SDK | **不引**（openai-java / anthropic-java / google-genai）：chat 线协议稳定且实现成本已付；引 SDK 破 I7 依赖纪律并带 Jackson 2 vs 3 冲突。Spring AI 定位为可选适配器之一，不进内核 |
| 拆分触发条件 | eucp-ai 要求不拖 model 层 / 出现第二个非 mos4j 消费方 / wire 需独立发版节奏 → 再拆 `mos4j-wire` 模块 |

### 3.18 模型族扩展：公共底座 + kind 谱系 + 方言约定（2026-11-19 已落地底座）

> **地位**：`mos4j-model` 根包 = **跨模型种类的公共底座**；每个模型种类自己一族（`model.<kind>`）；
> 协议层按**方言**分（`protocol.<方言>.<kind>`），**不按平台分**。
> 本节先落地底座四件套（`Artifact` / `TaskHandle` / `TaskStatus` / `TaskPoller`）与口径，种类族按批次推进（T25–T29）。

#### 3.18.1 公共底座清单（根包成员）

| 类 | 定位 | 跨种类用途 |
|---|---|---|
| `Artifact` | 产物值类型（sealed 接口） | 图像 / 音频 / 视频 / 文档等二进制产物的统一表示 |
| `TaskHandle` / `TaskStatus` | 异步任务句柄与状态 | 「提交 → 轮询 → 取产物」生命周期的统一表示 |
| `TaskPoller` | 刷新动作接口 | 协议层实现轮询；底座只定义「刷新一次」这个原子动作 |
| `Usage` / `UsageDetails` / `InputTokenDetails` / `OutputTokenDetails` / `UsageJson` | token 计量 | chat 与 embedding 等共用（口径 M6 不变） |
| `StatusError` | 协议错误 | 所有 kind 的 HTTP 失败统一表达（带状态码，可被重试策略读） |
| `ModelTimeoutException` | 契约异常 | 宿主 catch 的超时（对任何 kind 皆适用） |

> 归位口径：**跨 kind 的留根级，chat 专属的随 chat 族下沉**（chat 契约异常 `EmptyResponseException` /
> `StreamTruncatedException` / `StreamAbortedException` 属后者——**已落地，见 §3.19**）。

#### 3.18.2 `Artifact`：二进制产物

| 项 | 口径 |
|---|---|
| 形态 | `Artifact.InMemory`（字节在内存，构造时防御性复制）/ `Artifact.OnDisk`（已落盘，只带引用）——**用类型区分，不靠空值**（避免「既无内容也无位置」的半成品状态） |
| 字段 | `mediaType`（必填）/ `fileName`（可空）/ `size()`；`InMemory.content()` 取字节，`OnDisk.location()` 取位置 |
| 相等性 | `InMemory` 按**内容**比（record 默认按数组引用比，会把同一产物说成两个东西）；`OnDisk` 按元信息比 |
| 不变式 | 缺媒体类型 / 负大小 / 空位置 / 空字节 → 构造即拒（fail-closed） |
| 禁则 | **二进制不得进 `Message.content`**：上下文会爆（每轮回灌都带着整段二进制）/ 审计记不下（日志只能记引用）/ 可能带凭据（产物跟着 prompt 去别的厂商） |
| 写盘 | 产物不自己写文件（写盘是宿主的权限边界）；`Artifact.onDisk(Path, mediaType)` 只 stat 大小 |

#### 3.18.3 `TaskHandle` / `TaskStatus` / `TaskPoller`

| 项 | 口径 |
|---|---|
| 状态 | `PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELED` / `UNKNOWN`；`UNKNOWN` = 取值不认识，**不猜**（猜成功会拿空产物当结果，猜失败会误杀仍在跑的任务）；`UNKNOWN` **不算终态** |
| 句柄 | `taskId` + `status` + `artifacts`（不可变副本）+ `errorMessage`；**失败必带原因**（没原因的失败，宿主只能透传「任务失败了」，用户无从判断是配额、参数还是服务端问题），成功不得带原因 |
| 刷新 | `TaskPoller.refresh(TaskHandle)` 由协议层实现（轮询端点与状态映射各家私有）；**底座不提供退避循环**——轮询节奏与超时是宿主策略（与取消语义一致：框架不替宿主做决定） |

#### 3.18.4 kind 谱系与生命周期

生命周期五型：一次 JSON / 一次二进制 / SSE 单向流 / WebSocket 双向 / 异步提交→轮询→取产物。

| kind | 形状 | 生命周期 | 落点 |
|---|---|---|---|
| chat | 文本 / 多模态对话 | 一次 JSON 或 SSE | `model.chat`（T26 下沉）+ `protocol.<方言>.chat` |
| 视觉理解（VLM） | 图片入、文本出 | 一次 JSON | **复用 chat**（`ContentPart.image*` + `MULTIMODAL`），不另立 kind |
| 对话式 omni（内联音频） | 文本 + 音频块 | 一次 JSON | **复用 chat**（`Request.modalities` + `Message.contentParts`） |
| embedding | 文本 → 向量 | 一次 JSON | `model.embedding` |
| rerank | 文本 + 候选 → 排序 | 一次 JSON | `model.rerank` |
| 文件 / 文档解析 | 文件 → 结构化块 | 一次 JSON 或**异步任务** | `model.file` |
| TTS | 文本 → 音频 | 一次二进制 | `model.speech`（输出 `Artifact`） |
| ASR | 音频 → 文本 | 一次 JSON 或 SSE | `model.asr`（输入二进制） |
| 图像生成 | 提示 → 图片 | 一次二进制或**异步任务** | `model.image`（产物 `Artifact`） |
| 视频生成 | 提示 → 视频 | **异步任务** | `model.video`（长时任务） |
| Realtime | 双向实时 | **WebSocket** | `model.realtime`（独立传输，不套一次调用型） |

#### 3.18.5 方言优先（协议层怎么分）

| 项 | 口径 |
|---|---|
| 目录 | `protocol.<方言>.<kind>`——**第一维度是方言（wire family），不是平台** |
| 为什么 | 兼容性是**方言级**的：OpenAI 方言一份覆盖 DeepSeek / Kimi / Qwen 兼容模式 / 豆包 / 智谱 / SiliconFlow / OpenRouter / vLLM / Ollama / 百炼兼容模式。按平台分 = 同一套线格式写 10+ 份适配器 |
| 决策树 | ① 有现成方言（OpenAI / Anthropic / Gemini 兼容）→ **用它**，平台差异走 Config；② 是「某方言 + 私有扩展」→ 仍用该方言 + 扩展钩子；③ 真独有（ASR 静音断句 / 文件解析块结构）→ 才开**平台原生方言包**（如 `protocol.bailian.asr`） |
| 平台差异三收口 | ① `Config`（baseUrl / apiKey / 鉴权方式 / extra headers / extra body）；② **方言扩展点（SPI）**（请求体扩展 + 响应投影钩子）——**不允许方言包长出 `if (provider.equals(...))`**；③ 能力位由方言给缺省、Config 覆盖 |
| 已知不等价 | usage 字段名 / `finish_reason` 取值 / 思维链字段名 / 并行工具调用 / 多模态字段名——方言内留归一钩子，逐平台登记偏差（登记范式照 `docs/contract/GO-DIFF.md`） |
| kind 与方言正交 | kind = 做什么，方言 = 怎么说；两者组合成目录 `protocol.<方言>.<kind>` |

#### 3.18.6 批次计划（依赖顺序，每批独立可验证）

| 批 | 内容 | 破坏性 |
|---|---|---|
| T25（本批） | 公共底座四件套 + kind 谱系与方言口径 | 无（纯新增） |
| T26（**已落地**） | chat 族下沉 `model.chat`（chat 契约异常随族） | 大但机械（消费方 import 批量替换） |
| T27（**已落地**） | 协议层方言化：`protocol/openai` → `protocol/openai/chat` 等 | 中（协议层内部 + 测试） |
| T28（**已落地**） | kind 谱系的类型化身（`ModelKind`）+ `catalog` 按 kind 键化；装饰器/重试保持 chat 壳（口径见 §3.21.4） | 中（`ModelOption` 泛型化 + 消费方 import） |
| T29+ | 按 kind 逐族落地：embedding / rerank → 文件 / TTS / ASR → 图像 / 视频（异步）→ Realtime | 按需 |

### 3.19 chat 族下沉 `net.hegq.mos4j.model.chat`（2026-11-19 已落地）

> **动因**：建设初始不怕破坏——公共契约尚未固化时把 chat 家族一次整理到稳定层级，
> 免得 chat 长期留在根级成为「特例根」，后续每个 kind 族都要跟这个例外搏斗。
> **破坏性变更**：14 个类型的包位置变了（全限定名 + import），**行为零变化**。

#### 3.19.1 搬家清单

| 新位置（`net.hegq.mos4j.model.chat`） | 内容 |
|---|---|
| `ChatModel` / `Request` / `Response` / `Message` / `Role` | 对话契约 |
| `ContentPart` / `Tool` / `ToolCall` / `Capability` | 多模态与工具调用（`Capability` 只描述 **chat 协议**能力位——TTS / OCR / REALTIME 不塞进来） |
| `StreamChunk` / `StreamEmitter` | 流式出口 |
| `EmptyResponseException` / `StreamTruncatedException` / `StreamAbortedException` | chat 专属契约异常 |

| 留在根级（跨 kind 底座） | 内容 |
|---|---|
| `Artifact` / `TaskHandle` / `TaskStatus` / `TaskPoller` | 产物与异步任务（§3.18） |
| `Usage` / `UsageDetails` / `InputTokenDetails` / `OutputTokenDetails` / `UsageJson` | token 计量（chat 与 embedding 共用，口径 M6 不变） |
| `StatusError` / `ModelTimeoutException` | 协议错误与契约异常（宿主 catch 的对象，跨 kind 皆适用） |

#### 3.19.2 影响面

| 项 | 口径 |
|---|---|
| 消费方 | `mos4j-model` 内的 `protocol/*` / `decorate` / `retry` / `catalog`；`mos4j-session`（`Event` / `Projection` / `Transcript` / `Session` / `context/*`）、`mos4j-tool`（`ToolRegistry`）、`mos4j-agent`（`Agent` / `Config` / `ModelCatalog` / `sdk.Mos4j`）、`mos4j-spring-boot-starter`（`Mos4jAutoConfiguration`）——全部改 import |
| 测试 | 三个 L1 语料场景测试随族下沉到 `net.hegq.mos4j.model.chat`（它们是 chat 场景回归）；根包测试只留底座（`ArtifactTest` / `TaskHandleTest` / `UsageJsonTest` / `L1CorpusIntegrityTest` / `SseParserFuzzTest`） |
| 全限定名 | 消费方里的 `net.hegq.mos4j.model.Request` 等写法一并改写（0 处遗留） |
| 行为 | **零变化**——纯搬家，用例数不变（model 141 / 全仓 972，0 失败 / 7 跳过） |
| 依赖方向 | `chat → 根级`（chat 依赖 `Usage` 等底座）；`protocol/*` → `chat` + 根级；`decorate` / `retry` / `catalog` → `chat`；**根级不依赖 chat** |

### 3.20 协议层方言化：`protocol.<方言>.<kind>`（2026-11-19 已落地）

> **动因**：协议层的第一维度从「平台」换成「**方言（wire family）**」——兼容性是**方言级**的：
> OpenAI 方言一份覆盖 DeepSeek / Kimi / Qwen 兼容模式 / 豆包 / 智谱 / SiliconFlow / OpenRouter /
> vLLM / Ollama / 百炼兼容模式；按平台分 = 同一套线格式写 10+ 份适配器。
> 包结构现在就把「方言 × kind」矩阵立住：**后续加 kind 只加一格，不动已有格子**。

#### 3.20.1 包结构

| 方言 | kind | 内容 |
|---|---|---|
| `protocol.openai` | `chat` | `OpenAiChatModel` / `OpenAiConfig` / `OpenAiDtos` / `OpenAiStreamDtos` / `OpenAiStreamRules` |
| `protocol.anthropic` | `chat` | `AnthropicChatModel` / `AnthropicConfig` / `AnthropicDtos` / `AnthropicStreamDtos` / `AnthropicStreamRules` |
| `protocol.gemini` | `chat` | `GeminiChatModel` / `GeminiConfig` / `GeminiDtos` / `GeminiStreamRules` |

- **破坏性变更**：包路径从 `protocol.<方言>` 变为 `protocol.<方言>.chat`（全仓 import + 测试包同步改写，**0 处遗留**）；**行为零变化**。
- **方言级共享层**（连接配置 / 鉴权 / extra body / 扩展钩子）**待第二个 kind 落地时抽取**——届时有两个实例才能定形，现在抽就是猜。

#### 3.20.2 eucp-ai 消费面（路径已变）

| 项 | 口径 |
|---|---|
| 稳定 wire API | `net.hegq.mos4j.model.transport.*` + `net.hegq.mos4j.model.wire.*` + `protocol.<方言>.<kind>` 的 Config/Rules（将来按需加带 catch-all 的无损 DTO） |
| 明确不含 | `net.hegq.mos4j.model.chat.*`（统一模型投影）、协议 DTO 的内部解析细节 |
| 破坏性提示 | eucp-ai 侧 import 需跟着改（`protocol.openai.X` → `protocol.openai.chat.X`）；**wire 层路径不变**（`wire.SseEventReader` / `wire.StreamTermination`） |

### 3.21 kind 谱系的类型化身 + 目录按 kind 键化（2026-11-19 已落地）

> **动因**：§3.18 的 kind 谱系之前只是文字。目录与注册表都硬绑 `ChatModel`，宿主无法回答
> 「这一项是什么 kind」。本批把 kind 落成类型，并让目录项同时报 kind 与句柄。

#### 3.21.1 `ModelKind`（根包 `net.hegq.mos4j.model`）

| 取值 | 含义 |
|---|---|
| `CHAT` | 对话补全（含视觉理解、工具调用、流式） |
| `EMBEDDING` | 文本嵌入（文本 → 向量） |
| `RERANK` | 重排序（query × documents → 分数） |
| `IMAGE` / `VIDEO` | 图像 / 视频生成（通常异步任务，产出产物引用） |
| `SPEECH` / `ASR` | 文本→语音（TTS）/ 语音→文本 |
| `FILE` | 文件解析（PDF / 文档 → 结构化文本或产物引用） |
| `REALTIME` | 实时双工（WebSocket：音频 / 文本双向流） |

- **与 `Capability` 分工**：`Capability` 是 **chat 协议内部**的能力位；`ModelKind` 是**跨族**的种类。TTS / ASR / REALTIME 不塞进 chat 的 `Capability`。
- **与方言正交**：kind = 做什么，方言 = 怎么说；组合成 `protocol.<方言>.<kind>`。

#### 3.21.2 `ModelOption<T>` 泛型化

| 项 | 口径 |
|---|---|
| 形状 | `record ModelOption<T>(ModelKind kind, String id, String label, T model)` |
| 工厂 | `ModelOption.chat(id, model)` / `ModelOption.chat(id, label, model)`——将来 embedding / rerank 各加一个工厂 |
| 校验 | kind 非空（`llm: 模型目录里有项没有 kind`）、ID 非空、句柄非空；标签空 = 用 ID |
| 旧形状 | `ModelOption(String id, String label, ChatModel chat)` 与 `chat()` 访问器**已删除**（破坏性）；改用工厂 + `model()` |

#### 3.21.3 agent `ModelCatalog` 按 kind 校验

- 类型改为 `List<ModelOption<ChatModel>>`；装配期发现 `kind != CHAT` 直接报错：`mos4j: 模型目录里的 X 不是 chat 模型（kind=Y）`——目录的「下一轮切换」语义只对 chat 成立，标错 kind 的项不静默忽略。

#### 3.21.4 横切策略的边界（本批不改代码，只定口径）

| 层 | 现状 | 为什么 |
|---|---|---|
| 重试机制 | `RetryPolicy` 只吃 `Predicate<Throwable>` 分类器与退避参数，**已经不认 chat**；`RetryExecutors` 只管睡眠 | 机制层本就 kind 无关，无需下沉 |
| 装饰器壳 | `ChatModelDecorator` / `RetryingChatModel` / `Caching` / `Logging` / `Timeout` / `Fallback` 仍锁 `ChatModel` | 各 kind 的调用签名不同，**没有共同超类型可装饰**；硬抽成通用装饰器只能靠反射或 `Object`，比「按形状复制」更贵、更不类型安全 |
| 提供方注册表 | `catalog.ModelRegistry` 仍产出 `ChatModel` | 按 kind 键化推迟到**第一个非 chat kind 落地**（届时按 `(kind, provider)` 注册），现在改就是猜共享面 |

#### 3.21.5 验证

- 「mos4j-model」20 类 / **148 用例**（新增 `ModelOptionTest` 7 条）；`mos4j-agent` **207**（新增 kind 校验 1 条）；全仓 **980 用例**（core 20 / model 148 / runtime 99（7 跳过）/ tool 359 / session 147 / agent 207），**0 失败 / 0 错误**；`./mvnw verify` 绿。

---

## 4. 关键文案表（照抄 Go，语义等价的小差异须 PR 说明）

| 场景 | 文案 |
|---|---|
| 载荷冻结违约 | `扩展点试图改写工具调用载荷（载荷冻结契约，调用已拒绝）` |
| 守卫拒绝 | `工具调用被安全策略拒绝: {reason}` |
| 钩子拒绝 | `工具调用被扩展点拒绝: {reason}` |
| ask 且无审批通道 | `工具调用需要人工确认，但未装配审批通道（fail-closed）: {reason}` |
| 审批人工拒绝 | `用户已拒绝此操作，该操作保持拒绝状态。请停下，向用户说明情况；不要尝试用其他命令或方式达成同一目的。若确有必要，请先向用户说明并获得同意，然后原样重新发起这次调用（会再次请求批准）。拒绝时给出的理由：{reason}` |
| 审批无人应答 | `人工确认无人应答（fail-closed）: {reason}` |
| 投影合成中断回执 | `（{toolName} 调用在执行期间被中断，没有结果；如仍需要请重新调用）` |
| 截断标记（J4） | `…（已截断：共 {total} 行，仅保留前 {head} 行）` / `…（已截断：共 {total} 行，仅保留后 {tail} 行）` / `…（已截断：共 {total} 行，仅保留前 {head} 行与后 {tail} 行）` |
| 落盘提示（J4） | `[完整输出已落盘] 路径：{path}` |
| 落盘失败（J4） | `[落盘失败：完整输出未能保存] {reason}` |
| 压缩失败（J4） | `压缩失败：摘要生成异常（历史未被遮蔽）` |
| 写前观察（write） | `write 拒绝："{path}" 已存在但未被读取过。先 read 读取当前内容，再决定是否覆盖` / `… 自读取后已被删除` / `… 自读取后已被修改。请重新 read 获取最新内容后再写入` |
| 写前观察（edit） | `edit 拒绝：编辑前必须先 read 读取 {path} 的当前内容` |
| 截断工具调用 | `模型输出被截断（finish_reason={reason}），此工具调用的参数可能不完整，已拒绝执行。请重新发起该调用` |
| shell 退出码标记 | 输出末尾 `[exit code: N]`（非零退出是正常结果，isError=false——M10） |
| 二进制内容拒绝 | 读：`拒绝处理二进制文件（内容含 NUL 字节）: {path}`；写 / 编辑：`拒绝处理二进制内容（含 NUL 字节）: {path}` |
| edit 多处命中 | `edit 失败：第 {n} 处编辑的 old_string 在 {path} 中出现 {m} 次，无法确定改哪一处。请提供更长的上下文（每处都要唯一）` |
| edit 参数形状（B13-3） | `edits` 收数组 / 单个对象 / JSON 字符串三种形状；缺 `edits` → `edit 失败：缺少参数 edits`；`edits` 非法 JSON → `edit 失败：edits 不是合法的 JSON: {reason}`；old==new → `edit 失败：第 {n} 处编辑的 old_string 与 new_string 相同，无需编辑`；未命中 → `edit 失败：第 {n} 处编辑的 old_string 在 {path} 中没有找到`；重叠 → `edit 失败：第 {n} 处编辑与第 {m} 处重叠，请合并成一条 edit` |
| edit 落盘（B13-3） | 成功回执 `已编辑 {path}（替换 {n} 处）`；全部按原文定位、从后往前替换，失败时不留半改状态 |
| grep glob 过滤（B13-2） | `glob` 可选；非法模式 → `grep 失败：glob 模式非法: {reason}`；匹配口径与 Go 同款（先相对路径、再文件名） |
| glob / ls limit（B13-2） | glob 默认 1000 / 上限 10000；ls 默认 500 / 上限 5000；截断附注 `（已达上限 {n} 个匹配，可能还有更多。请缩小范围或提高 limit）` / `（已达上限 {n} 条，可能还有更多。请换更深的子目录或提高 limit）` |
| shell description（B13-2） | 可选；回执前置 `说明：{description}`，退出码标记仍在末尾 |
| skill 加载（J7） | 回执 `{"name":...,"dir":...,"content":...}`（JSON 对象，键名字母序）；缺参数 → `skill 失败：缺少参数 name`；未知 → `技能 "{name}" 不在当前技能目录里（目录可能已更新；可用技能见系统提示词的 available_skills 清单）` |
| skill 契约拒绝（J7） | 仅用户可调用 → `技能 "{name}" 仅限用户显式调用（disable-model-invocation），模型不可加载`；审计写失败 → `记技能激活事件失败: {reason}`；正文损坏 → `技能 "{name}" 的文件已损坏（缺少 frontmatter）`；正文超预算 → `技能 "{name}" 正文超预算（{n} 字节，上限 {max}）` |
| 技能清单降级（J7） | 只列名 → `（清单预算受限：此处只列技能名，描述略）`；部分条目 → `（另有 {n} 个技能因清单预算未列出）`；最小形态 → `（技能清单过长，未列出）`；预算连标签都装不下 → 不注入清单 |
| 技能诊断与通知（J7） | 重名 → `技能 "{name}" 重名：保留 {前者目录}…`；allowed-tools → `技能 "{name}" 声明了 allowed-tools，但该字段当前不生效（PI 有字段无实现、DSH 无此字段；技能级工具收窄不自创）`；运行期求交 → `技能声明的工具当前不可用: {names}（allowed-tools 不收窄工具面，仅提示；若为 deferred 工具可用 tool_search 激活）`；重扫通知 → `技能目录已更新…以下是目前可加载的技能（替换此前的清单）：` |
| tool_search 参数名（B13-2） | 模型可见参数名是 `limit`（不再是 `topK`） |
| grep 非法正则（J5） | `grep 失败：正则表达式非法（{reason}）: {pattern}`（不静默当字面量） |
| grep 无命中 / 二进制（J5） | `没有匹配` / 附注 `（已跳过 N 个二进制文件）` / 截断附注 `（已达上限 N 条，可能还有更多命中。请缩小搜索范围或提高 limit）` |
| glob / ls 边界（J5） | glob：`glob 失败：模式非法 "{pattern}"` / `匹配范围超过 20000 个文件…`；ls：`ls 失败：目录条目超过 5000…`；空目录 `（空目录）` |
| grant_access（J5） | 批准 `已开放 "{dir}" 为根，挂载名 "{mount}"。…`；拒绝 `申请未通过：宿主拒绝开放 "{dir}"。不要尝试用其他路径或方式访问同一目录；如确有必要，请向用户说明后再重新申请`；回调异常 `申请未通过（审批通道异常，fail-closed）: {reason}` |
| tool_search / activate（J5） | 搜索空结果 `没有匹配的工具`；activate `已激活（下一轮生效）: …` / `未登记的工具名（已忽略）: …` |
| web_search（J5） | 内容头 `【外部内容不可信】…（提示注入警告，与 web_fetch 共用冻结文案）`；参数错 `web_search 失败: queries 不能为空` / `…最多 4 条，收到 N 条`；后端失败 `web_search 失败: {reason}`（工具错误，对齐 Go）；部分查询失败 → `note` = `以下查询失败：[…]`，`content` 带 `（以下查询失败：[…]）`；全部失败 → `web_search 失败: 全部查询都失败（{q}）: {reason}` |
| web_fetch（J5） | url 空 `url 不能为空`（**无** `web_fetch 失败: ` 前缀，对齐 Go）；后端未装配 `web_fetch 未装配抓取后端（宿主注入 Fetcher 后才可用）`（**执行期**检查，不是构造期；`WebFetchTool((Fetcher) null)` 合法）；后端失败统一 `web_fetch 失败: {reason}`（含 `web: ` 前缀的详尽原因：URL 校验 / 解析失败 / 非公网地址 / 跨源重定向 / 跳数上限 / 无 Content-Type / 非文本类型 / chunked 长度非法 / 非 HTTP 响应）；截断附注 `\n…[内容过长已截断，完整上限 N 字符]` |

「不许绕」半句（`不要尝试用其他命令或方式达成同一目的`）是实测教训：缺了它模型会拆解命令绕过规则——不是措辞偏好。

---

## 5. 六处 Java 差异的对位契约

| Go | Java 对位 | 降级声明 | 落点 |
|---|---|---|---|
| `context.Context` 取消 | `CancellationToken`（Mos4J 自有） | 取消是协作式的；每个可阻塞点必须显式响应（清单随 `mos4j-core` 走） | `mos4j-core`（模型 / HTTP 层已生效；driver → planner → agent 已贯通，见 `J6-STATUS.md`） |
| `syscall.Kill(-pgid)` | `ProcessHandle::descendants` + 逐个 destroy | **尽力而为、非原子**；写进 Javadoc 与 `Describe()` | `mos4j-runtime` 的 `DescendantsKiller.description()` → `SandboxBoundary.caveats()` → `Describe()` |
| 版本令牌 `dev:ino:size:ctime:...` | `dev:ino:size:mtimeNs` | **不含 ctime**：JDK 的 `unix:` 视图其实能读到 `ctime`，但冻结口径不收（跨平台语义不统一，有的文件系统读操作也会更新 ctime）；代价：只改元数据、内容不变的改动检测不到。CAS 防丢失更新仍有效但非原子（M7） | `mos4j-runtime` 的 `VersionToken` → `FileSystemBoundary.caveats()` → `Describe()` |
| 反射 Schema（Go struct tag） | 注解 `@ToolParam` + 反射 | 反射失败 fail-closed：注册失败报错，不许静默空 schema | `mos4j-tool` 的 `SchemaGenerator` / `ToolRegistry` |
| 三协议裁剪 DTO | 每协议独立 DTO（互不同形） | 拦「日志与协议同形」被拆散（边界检查 C3） | `mos4j-model/protocol/{openai,anthropic,gemini}`（Anthropic / Gemini 属 J6） |
| 正则 | RE2J | 禁 `java.util.regex`（CI 门 C6） | `mos4j-tool` |
