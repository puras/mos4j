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

**ToolCall**（`net.hegq.mos4j.llm.model`）：

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

完整基线见 `fieldnames.json`（**80 条 tag / 71 个唯一字段名**，sha256 `3d35911d810641c25b8ac0da032ac6f8c1d80dd90c240a4aafe5e95d4714912c`；J7 并入 `skill`/`name`/`description`/`dir`/`missingAllowed` 5 条）。

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

## 3. Guard / Hook 契约（`mos4j-hook`，J2 已落地）

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

### 3.2 执行编排顺序（`mos4j-execpath`，与 `INVARIANTS.md` I5 一致）

`工具存在性 → 守卫（deny/cancel 短路，ask 记下）→ 钩子链 → 载荷冻结检查 → 审批 → 执行（执行前再查冻结）→ 回填（按调用序）→ 执行后钩子（只能加重）`

守卫排在钩子前面：安全判定不应发生在扩展代码之后。审批排在冻结检查之后：不为反正会被拒的调用打扰人。守卫判定的仍然就是最终载荷——任何改写都被冻结检查拒掉，能执行下去的载荷必然就是守卫看到的那一份（机制 M1）。

### 3.3 名字级 / 参数级策略（`mos4j-policy`）

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
// ---- mos4j-fs：jail + 写前观察门 ----
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

// ---- mos4j-sandbox：进程隔离 ----
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
// ---- mos4j-context：输出限流 ----
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

// ---- mos4j-context：压缩 ----
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

// ---- mos4j-context：提示词分节 + 上下文文件 + 任务状态 ----
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

**驱动接线**（`mos4j-driver`）：

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
    List<SearchResult> search(String query, int limit) throws Exception;
    record SearchResult(String title, String url, String snippet) {
    }
}
public final class WebSearchTool implements Tool {            // 工具名 web_search
    public static final String UNTRUSTED_NOTE = "【外部内容不可信】…"; // 与 web_fetch 共用
    public WebSearchTool(SearchProvider provider);
}
public final class WebFetchTool implements Tool {             // 工具名 web_fetch
    public static final int DEFAULT_MAX_BYTES = 512 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    public static final int MAX_REDIRECTS = 3;
    public WebFetchTool();
    public WebFetchTool(PinnedConnector connector, int maxBytes);
    public sealed interface FetchResult {
        record Body(String text, String contentType, boolean truncated) implements FetchResult {
        }
        record Redirect(int status, String location) implements FetchResult {
        }
    }
}
public final class PublicAddresses {                          // SSRF 防线一：公网判定（纯逻辑）
    public static boolean allPublic(List<InetAddress> addresses); // 任一非公网 = 整体拒绝
    public static boolean isPublic(InetAddress address);
}
public class PinnedConnector {                                // SSRF 防线二：地址钉住
    public WebFetchTool.FetchResult fetch(URI uri, List<InetAddress> resolved,
                                          Duration timeout, int maxBytes) throws IOException;
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

**SSRF 四条硬防线**（`web_fetch`，每条有绕过测试）：

1. **公网地址校验**：DNS 解析后逐地址检查（回环 / 内网 / 链路本地 / CGNAT / ULA / IPv4 映射伪装全部拒绝）——校验「解析结果」不是「URL 文本」。
2. **地址钉住**：连接 socket 直绑校验过的地址（`Proxy(DIRECT, InetSocketAddress(resolved))`），不做第二次 DNS 解析（防 rebinding）。
3. **只跟同源重定向**：重定向目标主机必须与原主机一致；重定向目标逐跳重新过防线一。
4. **体积与超时上限 + 只解 UTF-8**：响应体 512KB 硬上限（截断注明）、20s 超时、非文本 Content-Type 响亮失败。

**web_search 的非 2xx 是结果不是错误**：回执明说「搜索失败 + 原因」，模型可换词重试，不中断回合。

### 3.7 三协议 + 硬化 + 门面（J6 已落地）

```java
// ---- mos4j-llm：Anthropic 协议（T6.1/T6.2）----
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

// ---- mos4j-llm：Gemini 协议（T6.3）----
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

// ---- mos4j-llm：重试（T6.5，装饰器，默认不装配即透传）----
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

// ---- mos4j-execpath：取消贯通（T6.4）----
public final class ExecPath {
    // 新增重载：取消后未开始的调用回填「调用已取消，未执行」（错误结果，保持回填序与配对）；
    // 正在执行的不中断（协作式取消，诚实声明）；token=null 零开销。
    public static List<Outcome> executeBatch(ToolRegistry registry, List<ToolCall> calls,
            Options options, Security security, CancellationToken token);
}

// ---- mos4j-driver：取消贯通到驱动/决策器/智能体（加固批次）----
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

// ---- mos4j-sdk：门面（T6.10）----
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
        public Mos4j build() throws IOException;

    // 缺省模型装配口径公开：starter 与门面共用一份「协议 → 环境变量 → 默认地址」真相
    public static ChatModel defaultModel(Protocol protocol, String apiKey, String baseUrl,
                                         String model, Map<String, String> env);
}

// ---- mos4j-sdk：CLI（T6.9，单轮最小形态）----
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

> Go 的 `agent/skill` 的 Java 语义重写。**不新增模块**：落在 `mos4j-tool`（`tool/skill` 包）/ `mos4j-core` / `mos4j-session` / `mos4j-driver` / `mos4j-sdk`。
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

// ---- mos4j-driver 门面（未装配技能目录时全部降级，不需空判断）----
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
| web_search（J5） | 内容头 `【外部内容不可信】…（提示注入警告，与 web_fetch 共用冻结文案）`；失败 `搜索失败（服务返回非成功状态或网络异常）…`（是结果不是错误） |
| web_fetch（J5） | SSRF 拒绝 `web_fetch 失败："{host}" 解析出的地址不是公网地址，已拒绝（内网 / 回环 / 链路本地地址不可访问）`；跨源重定向 `…重定向跨源（{a} → {b}），已拒绝`；非文本 `…非文本内容类型（{type}），拒绝解码…`；截断附注 `（内容超过 N 字节上限，已截断）` |

「不许绕」半句（`不要尝试用其他命令或方式达成同一目的`）是实测教训：缺了它模型会拆解命令绕过规则——不是措辞偏好。

---

## 5. 六处 Java 差异的对位契约

| Go | Java 对位 | 降级声明 | 落点 |
|---|---|---|---|
| `context.Context` 取消 | `CancellationToken`（Mos4J 自有） | 取消是协作式的；每个可阻塞点必须显式响应（清单随 `mos4j-core` 走） | `mos4j-core`（模型 / HTTP 层已生效；driver → planner → agent 已贯通，见 `J6-STATUS.md`） |
| `syscall.Kill(-pgid)` | `ProcessHandle::descendants` + 逐个 destroy | **尽力而为、非原子**；写进 Javadoc 与 `Describe()` | `mos4j-sandbox` 的 `DescendantsKiller.description()` → `SandboxBoundary.caveats()` → `Describe()` |
| 版本令牌 `dev:ino:size:ctime:...` | `dev:ino:size:mtimeNs` | **不含 ctime**：JDK 的 `unix:` 视图其实能读到 `ctime`，但冻结口径不收（跨平台语义不统一，有的文件系统读操作也会更新 ctime）；代价：只改元数据、内容不变的改动检测不到。CAS 防丢失更新仍有效但非原子（M7） | `mos4j-fs` 的 `VersionToken` → `FileSystemBoundary.caveats()` → `Describe()` |
| 反射 Schema（Go struct tag） | 注解 `@ToolParam` + 反射 | 反射失败 fail-closed：注册失败报错，不许静默空 schema | `mos4j-tool` 的 `SchemaGenerator` / `ToolRegistry` |
| 三协议裁剪 DTO | 每协议独立 DTO（互不同形） | 拦「日志与协议同形」被拆散（边界检查 C3） | `mos4j-llm/protocol/{openai,anthropic,gemini}`（Anthropic / Gemini 属 J6） |
| 正则 | RE2J | 禁 `java.util.regex`（CI 门 C6） | `mos4j-policy` |
