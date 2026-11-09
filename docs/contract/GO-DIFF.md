# Go 参考实现（MoS）↔ Java（MoS4J）差异登记表

> 状态：**2026-11-08 实现轮版**（第三轮：13 项差异全部补齐）。
> 口径：**语义重写**——判定的是「语义是否等价」，不是「是否逐行同形」。
> 标注约定：`✅ 已对齐` / `⚠️ 语义差异（有意）` / `❌ 能力缺口（未实现）`。
> 关联文档：[`SELF-INVENTED.md`](SELF-INVENTED.md)（Java 侧有意差异）、
> [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md)（Go 原测试名对位）、
> [`REVIEW-SHEET.md`](REVIEW-SHEET.md)（逐行签字表）。

---

## 1. 本轮复核读了什么

| Go 文件 | 复核目的 | 结论 |
|---|---|---|
| `agent/driver/driver.go` | `LoopDriver` / `RunContext` 的**全部字段** / `ResumeOptions` / 取消原因机制 | 发现 **2 处新缺口**（`PriorApproval`、`Resume` 字段与取消原因）；其余字段 Java 侧一一对应 → #2 / #3 / #11 已补齐 |
| `session/derive.go` | 派生读 API（`PendingApprovals` / `ApprovalOutcome` / `ApprovalDecision` / `InterruptedTurn` / `HangingCalls`） | 确认 **5 个方法 Java 侧全缺**（差异 #4）→ 已补齐 |
| `session/state.go` | 会话状态机 | 与 Java `mos4j-session` 语义一致（消息是投影、孤儿调用合成回执、JSONL 尾半行可丢）→ ✅ |
| `llm/registry.go` | 协议注册表 | 确认 Java 侧**无** `Registry` / `Factory` / `Config`（差异 #6）→ 已补齐 |
| `llm/wrap.go` | 装饰器清单 | Java 有 Logging / Timeout / Fallback；**缺 `WithCache`**（差异 #13）→ 已补齐 |
| `agent/prompt/prompt.go` | 提示词分节装配器 | Java `PromptSections` 覆盖 add/set/complete/clone/render，但**不能注入**（差异 #8）→ 已补齐；Java 的 `complete` 是「换某段内容」，Go 的 `Complete` 是「整段替换整个提示词」→ 登记为有意差异（8c） |
| `mos.go` | 根包 `Config` 全部字段 + `Agent` 全部公开方法 | 发现 **3 处新缺口**（运行期可调并发 / 轮数上限 / 参数级规则，#12）；压缩缺省口径差异（SI-15）已登记 |
| `subagent.go` / `resume.go` / `prior.go` | 子代理 / 续跑 / 审批先验的语义边界 | 三项均**未实现**（#1 / #2 / #3）→ 已补齐，语义差异登记为 SI-43…SI-46 |
| `defaults.go` | 缺省常量（并发 4 / 窗口 128K / 预留 16K / 保留 20K / 截断 32KB / 摘要 3m） | 并发 4 ✅、摘要 3m ✅、截断 ✅（Go 缺省也不装）；**压缩缺省不同**（见 §4 的 14） |
| `filesystem/roots.go` | `RootSet` 的读/写两套根集合与 `RootsFor(Op)` | Java 的 `Root(readable, writable)` 与之同语义 ✅；发现并修正一处写范围错位（见 §4 的 9c） |
| `sandbox/pathscope.go` / `sandbox/scope_dynamic_test.go` | scope 的读写分开（写根来自 `RootsFor(OpWrite)`） | Java 修正后同口径 ✅（见 §4 的 9c） |
| `examples/` | 示例程序集（7 个目录） | Java 侧已补 7 类对应示例（#10）→ 已对齐 |

---

## 2. 汇总

| # | 差异 | 类型 | 状态 |
|---|---|---|---|
| #1 | SubAgent 子代理 | 能力缺口 | ✅ 已对齐（形状差登记为 SI-43 / SI-44） |
| #2 | Resume 续跑 | 能力缺口 | ✅ 已对齐（形状差登记为 SI-45） |
| #3 | PriorApproval 审批先验 | 能力缺口 | ✅ 已对齐（字段差登记为 SI-46） |
| #4 | 会话派生读（5 个方法） | 能力缺口 | ✅ 已对齐（登记为 SI-47） |
| #5 | 压缩三开关（失败策略 / 摘要超时 / 显式关闭） | 语义差异 | ✅ 已对齐（口径差登记为 SI-39 / SI-40） |
| #6 | LLM 协议注册表 | 能力缺口 | ✅ 已对齐（配置形状登记为 SI-48） |
| #7 | 上下文文件装配入口 | 语义差异 | ✅ 已对齐（形状差登记为 SI-41） |
| #8 | 宿主可注入提示词构建器 | 能力缺口 | ✅ 已对齐（入口口径登记为 SI-49） |
| #9 | 运行期根目录管理 | 语义差异 | ✅ 已对齐（形状差登记为 SI-42） |
| #10 | examples 示例程序集 | 能力缺口 | ✅ 已对齐（交付形状登记为 SI-51） |
| #11 | 取消原因（`WithCancelCause` / `CancelCause`） | 能力缺口 | ✅ 已对齐（口径登记为 B21） |
| #12 | 运行期可调参数（`SetConcurrency` / `SetMaxDecisionRounds` / `SetRules`） | 能力缺口 | ✅ 已对齐（口径登记为 B22） |
| #13 | `llm.WithCache` 语义缓存装饰器 | 能力缺口 | ✅ 已对齐（缓存键口径登记为 SI-50） |
| #14 | 压缩缺省口径（Go 缺省 128K 自动压 vs Java 不配就不压） | 语义差异 | ⚠️ 有意（SI-15） |

> **编号说明**：#1–#10 是 2026-11-07 第一轮初查的编号；#11–#13 是同日逐行复核新增；#14 已在 SI-15 登记。
> **本轮结论**：13 项差异的生产代码与定向用例全部落地；剩余工作是文档登记与人工签字，不是能力缺口。

---

## 3. 已补齐的差异明细（✅ 已对齐）

### #1 SubAgent 子代理

- **Go 证据**：`subagent.go`（`SubAgentConfig` / `runSubagent` / `subagentSystemPrompt` / `subagentReceiptInstruction`）、`agent/subagent/*`。
- **Go 语义**：子代理 = 一个普通工具。清单（name / description）进**静态**系统提示词；每个子代理注册成 `subagent__<name>` 工具，模型点名调用；子代理跑在**自己的会话**里、用**收窄过的工具面**（声明白名单 ∩ 父可见工具集，deny 优先）；只回一段固定结构的 JSON 回执；给了目录但没装出子代理不算失败（降级说明进 `Describe()`）。
- **Java 落点**：
  - `mos4j-tool/.../subagent/`：`SubAgentCatalog`（装配期扫描、坏定义按目录隔离、同名先到先得并 warning）、`SubAgentDefinition` / `SubAgentFrontmatter`（`AGENT.md` 的 `name` / `description` / `model` / `tools`）、`SubAgentTool`（工具名 `subagent__<name>`、参数只有必填 `task` + 可选 `context`、独占不声明 `concurrencySafe`）、`SubAgentTools`（白名单 ∩ 父可见集，恒定排除 `subagent__*` 与 `tool_search`）、`SubAgentReceipt`（固定 JSON 回执 + 非 JSON 降级）。
  - `mos4j-agent/.../agent/subagent/`：`SubAgentSessionFactory`（独立子会话的宿主接缝）+ `DefaultSubAgentRunner`。
  - `mos4j-core`：`ToolContext`（轮次 / session id / 取消令牌；`Tool.execute(String, ToolContext)` 默认回退到旧 `execute(String)`，不破坏既有工具）。
  - SDK 入口：`Mos4j.Builder.subAgents(Path...)` / `subAgentDirs(List<String>)` / `subAgentReader(...)` / `subAgentMaxCatalogBytes(int)` / `subAgentTimeout(Duration)` / `subAgentRunner(...)`。
- **有意差异**：子代理集**装配期固定**（不做运行期热加载）+ 清单预算降级（SI-43）；回执结构固定化与非 JSON 降级（SI-44）。
- **守护用例**：`SubAgentCatalogTest`（12）、`SubAgentToolsTest`（12）、`SubAgentToolTest`（11）、`SubAgentFlowTest`（7）、`Mos4jTest`（子代理相关 4）。

### #2 Resume 续跑

- **Go 证据**：`resume.go`（`Agent.Resume(ctx, sess, ResumeOptions)`）、`agent/driver/react/resume.go`（驱动侧实现）、`agent/driver/driver.go`（`RunContext.Resume`）。
- **Go 语义**：宿主**显式**指示后，驱动沿用 `Resume.Turn` 那一轮，把该轮的悬空调用接着走完（重走审批 → 执行 → 回填**真实** `tool/result`），再继续问模型 → 本轮正常收尾；沿用原轮次号是关键（审批决定复用键是 `(Turn, CallID)`）；没有可续的东西 → 报 `ErrNothingToResume`，**那一轮保持挂起**（不收尾）；续跑不是特权路径（照常走 execpath，shell 副作用可能重复）。
- **Java 落点**：`ResumeOptions`（`latest()` / `ofTurn(int)` / `withCalls(...)`）、`NothingToResumeException`（checked）、`LoopDriver.resume(RunContext, Session, ResumeOptions, CancellationToken)`、`Agent.resume(...)`、`Mos4j.resume(sessionId, ...)`；续跑走**同一个 ReAct 决策循环**（不另造绕过审批 / 守卫 / 投影 / 审计的平行链），载荷从日志**原样重放**。
- **有意差异**：无可续使用**专用 checked 异常**（编译期强制处理，SI-45）。
- **守护用例**：`ResumeTest`（9 条）。

### #3 PriorApproval 审批先验

- **Go 证据**：`prior.go`（`approvalPrior(sess)`）、`agent/driver/driver.go`（`RunContext.PriorApproval`）。
- **Go 语义**：同一轮、同一次调用上**已经有人批过**，**且那次调用仍悬空**（没有 `tool/result`）→ 复用决定，不再问人。两个条件缺一不可：已跑完的调用**不**借用决定（否则一次批准会被放大成无限次）；复用时走**新 id**，载荷带 `reusedFrom` 指回被复用的那次决定（审计看得出「这次没重新问人」）。
- **Java 落点**：`ApprovalPrior`（core 的只读 SPI）+ `SessionApprovalPrior`（session 侧实现）+ `ApprovalOutcome.reusable()`（只有 `APPROVED` / `REJECTED` 是真决定）；当前轮次由 `ExecPath.Options.turn` **外部绑定**（`ApprovalRequest` 没有 `turn` 字段）；命中 prior 仍写**完整审计对**；**未装配审批通道时即使命中 prior 也 fail-closed**。
- **有意差异**：**不新增 `reusedFrom` 字段**（冻结字段清单不能动）——复用时签发新审批 id，旧 id 写进新 `approval/asked.reason`（SI-46）。
- **守护用例**：`SessionApprovalPriorTest`（9 条）、`ApprovalPriorTest`（6 条）。

### #4 会话派生读（5 个方法）

- **Go 证据**：`session/derive.go`。
- **Go 语义**（**纯读**：不写日志、不改状态、不推进序号；派生键全部用事件里已有的字段，不新增事件类型）：

  | Go 方法 | 语义 | Java 落点 |
  |---|---|---|
  | `PendingApprovals()` | 问了但还没答的审批（按 id 配对 `approval/asked` / `approval/decided`） | `Session.pendingApprovals()` |
  | `ApprovalOutcome(id)` | 某次审批的结果（`approved` / `rejected` / `cancelled` / `unavailable`） | `Session.approvalOutcome(id)` |
  | `ApprovalDecision(turn, callID)` | 同轮同调用上**最后一次**审批决定（先验复用的匹配键） | `Session.approvalDecision(turn, callId)` |
  | `InterruptedTurn()` | 最后一个被中断的轮次号（`turn/start` 有、`turn/end` 无或 `interrupted`） | `Session.interruptedTurn()` |
  | `HangingCalls()` | 声明了但没有结果的调用（`tool/call` 有、`tool/result` 无，按 CallID 配对） | `Session.hangingCalls()`（返回 `HangingCall`） |

- **注意**：Java 的 `Projection` 会给孤儿调用合成**失败回执**（给人看的投影），Go 的 `HangingCalls()` 报告的是**日志事实**（宿主据此决定要不要续跑）——两者不是一回事，不能互相替代。Java 侧把这条纪律固化为「派生读是**纯读**」（SI-47）。
- **守护用例**：`SessionDerivationTest`（8 条）。

### #6 LLM 协议注册表

- **Go 证据**：`llm/registry.go`。
- **Go 语义**：`Registry` 按名注册 `Factory`（`func(Config) (ChatModel, error)`）、按 `Config`（`map[string]string`，键名约定 `api_key` / `base_url` / `model` / `version`）创建模型；`Names()` 返回已注册名（排序）；未知名 → 报错并列出已注册名。注册表只做「名字 → 工厂」映射，**不感知协议差异**；横切能力（日志 / 重试 / 超时）由 `llm/wrap.go` 的装饰器叠加。
- **Java 落点**：`ModelRegistry`（`mos4j-model`）：`register(String, Factory)` / `create(String, Map<String,String>)` / `names()`（排序不可变）/ `contains` / `size`；同名注册覆盖；未知名字 fail-closed 并列出已注册名；注册表**只做映射**，生产裸 `ChatModel`。
- **有意差异**：配置形状为 `Map<String, String>`（SI-48）。
- **守护用例**：`ModelRegistryTest`（8 条）。

### #8 宿主可注入提示词构建器

- **Go 证据**：`agent/prompt/prompt.go`、`mos.go`（`Config.Prompt *prompt.Builder`，与 `SystemPrompt` **二选一**，两个都给 = 装配错误）。
- **Go 语义**：`Section{Name, Order, Text}` + 中心顺序常量（`OrderIdentity` / `OrderPersona` / `OrderPolicy` / `OrderProjectContext` / `OrderSkills` / `OrderMCPServers` / `OrderSubAgents` / `OrderToolBase` / `OrderRuntime` / `OrderSuffix`），同序按名字典序；`Add` 重名报错、`Set` 覆写、`Complete` **整段替换整个提示词**（其余分节让位，多智能体隔离用）、`Clone` 派生副本；`Default()` 给出「一句身份 + 两条固定约定」。
- **Java 落点**：`Config.prompt(PromptSections.Builder)` + `Mos4j.Builder.prompt(...)`（**显式注入**）；与 `systemPrompt(...)` 互斥（同时给 fail-closed，空白整串按未提供处理）；框架在宿主 Builder 的**副本**上添加 `context` / `skills` / `subagents` 分节（宿主那份保持原样；宿主已放同名段 → **明确报错**）；老整串路径继续可用（`PromptSections.insert(...)`）。
- **有意差异**：Java 的 `complete` 仍是「换某段内容」，不是 Go 的「整段替换整个提示词」（8c）；中心顺序是**固定名列表**而非数值 `Order`（8d）。
- **守护用例**：`PromptBuilderTest`（6 条）、`PromptSectionsTest`（16 条）。

### #10 examples 示例程序集

- **Go 证据**：`examples/{agents,chat,mcp,minimal,recovery,skills,subagent}`。
- **Java 落点**：`examples/` 下 7 个对应示例模块（`minimal` / `chat` / `agents` / `skills` / `subagent` / `recovery` / `mcp`）+ 共享 `examples/testkit`（脚本化模型替身）；每个示例一个 README。
- **有意差异**：示例**不进默认 reactor**（独立 `examples` profile，MCP 示例再叠 examples 侧的局部 `mcp` profile，即 `-Pexamples,mcp`）；用例用脚本化替身模型，**不碰网络**（SI-51）。
- **守护用例**：examples 用例共 28 条 + `StubModelTest`（5 条）。

### #11 取消原因

- **Go 证据**：`agent/driver/driver.go` 的 `WithCancelCause(ctx, cause)` / `CancelCause(ctx)`。
- **Go 语义**：取消原因解析优先级 = ① 宿主自己用 `context.WithCancelCause` 取消时的 `context.Cause(ctx)`（更具体，优先）→ ② `WithCancelCause` 挂的兜底原因 → ③ `context.Canceled`（普通取消）；ctx 未取消时返回 `nil`（「没被取消」不是「被取消了」）；`Agent.RunWithCause(ctx, sess, input, cause)` 是快捷写法；`turn/end` 的错误详情与返回错误都会用它解释。
- **Java 落点**：`CancellationToken.cancel(Throwable reason)` / `cause()`；**首次取消携带的原因生效**（先到先得），后续取消不覆盖；未取消或无原因时 `cause()` 返回 `null`；`throwIfCancelled()`、`turn/end.error` 与 driver 抛出的异常都解释具体原因；**不新增冻结事件字段**（原因只进已有的 `error` 字段）；`TimeoutChatModel` 传播外部取消原因。
- **守护用例**：`CancellationTokenCauseTest`（8 条）。

### #12 运行期可调参数

- **Go 证据**：`mos.go` 的 `Agent.SetConcurrency(n)` / `Agent.SetMaxDecisionRounds(n)` / `Agent.SetRules(rules []policy.Check)`。
- **Go 语义**：并发上限与决策轮数上限是**运行期可调**（`atomic` 存储，每轮开始时现取）；参数级规则（deny 档进守卫、ask 档进审批）可**热替换**。
- **Java 落点**：`RunContext.concurrency()` / `maxDecisionRounds()` 改为 `IntSupplier`（每轮现取）；`Agent.setConcurrency(int)` / `setMaxDecisionRounds(int)`；参数级规则走独立的 `ParamPolicySlot`（`Agent.setRules(ParamPolicy)`），**装配期必须先装过 `ParamPolicy`**，否则 fail-closed；名字级 `PolicySlot` 与参数级槽**状态独立**；并发仍受**显式 opt-in** 约束。
- **守护用例**：`RuntimeKnobsTest`（5 条）。

### #13 `llm.WithCache` 语义缓存

- **Go 证据**：`llm/wrap.go` 的 `WithCache(m, ttl)`（`cacheKey(req)` + TTL 缓存条目）。
- **Java 落点**：`CachingChatModel(ChatModel, Duration)`：只缓存**非流式**成功结果；`ttl <= 0` 不过期；取消检查**先于**缓存命中；失败 / 空响应不缓存；命中返回**同一个 `Response` 对象**；提供 `size()` / `clear()`。
- **有意差异**：缓存键覆盖**完整请求上下文**（消息、reasoning、tool call、工具清单、schema、采样参数、response format、modalities；schema map 键递归排序后取 SHA-256），比 Go 的 `cacheKey(req)` 更严（SI-50）；本机制是**本地**缓存，**与服务端 prompt cache 不是一回事**。

---

## 4. 语义差异明细（⚠️ 有意，Java 侧口径不同）

| # | 项 | Go 口径 | Java 口径 | 登记 |
|---|---|---|---|---|
| 14 | 压缩缺省 | `ContextWindow=0` → 缺省 **128K** → **自动装压缩器** | 未配 `CompactionPolicy` → **不装压缩**（永不触发） | SI-15 |
| 5a | 压缩开关冲突 | `DisableCompaction` 显式关闭**优先**（静默生效） | 与已装 `compaction(...)` 同时出现 → **装配期 fail-fast** | SI-39 |
| 5b | 摘要超时表达 | 负数 = 不限 | **负值拒绝**；`Duration.ZERO` = 不限 | SI-40 |
| 7a | 上下文文件装配 | `Config.Context *ContextConfig`（一份结构体） | `Mos4j.Builder` 六个方法 | SI-41 |
| 7b | 上下文预算表达 | 零值习惯 | `0` = 缺省 32KB、负数 = 不限 | SI-41 |
| 9a | 根管理入参 | `AddRoot(dir)`（按目录路径） | `addRoot(id, dir)`（按**挂载名**） | SI-42 |
| 9b | 根读写分离 | `AddReadRoot` / `AddWriteRoot` | 同语义（`Root.readable/writable`） | SI-42 |
| 9c | 只读根是否进 shell 写范围 | 写根 = `roots.RootsFor(filesystem.OpWrite)`（**只读根不在其中**） | **2026-11-07 修正**：写根只取 `writable` 的根；修正前 Java 把只读根也送进写范围（文件工具拒写、shell 却能写 → 两个边界对不上） | 修正后与 Go 对齐 |
| 1a | 子代理集的热加载 | Go 侧同样启动期扫一次 | Java 侧**不提供**运行期热加载（子代理是「角色」，热换 = 热换执行语义） | SI-43 |
| 1b | 子代理回执结构 | 提示词约定（模型自述） | 固定 `{status, summary, findings, error}` + 非 JSON 降级为 `summary` | SI-44 |
| 2a | 「无可续」的报错形态 | `ErrNothingToResume` 哨兵错误 | **checked** `NothingToResumeException`（编译期强制处理） | SI-45 |
| 3a | 先验复用的审计载荷 | 载荷带 `reusedFrom` | **不新增字段**：新审批 id + 旧 id 写进 `approval/asked.reason` | SI-46 |
| 4a | 派生读的副作用 | 纯读 | 纯读（**新增**纪律：Java 侧把「派生不得写事件」固化成可测口径） | SI-47 |
| 6a | 注册表配置形状 | `map[string]string` | `Map<String, String>`（同形） | SI-48 |
| 8a | 提示词双入口 | `SystemPrompt` 与 `Prompt` 二选一 | 同为互斥，但**空白整串按未提供处理**；框架在宿主 Builder **副本**上装配 | SI-49 |
| 8c | `complete` 语义 | **整段替换整个提示词** | 换某一段的内容 | 本表 8b |
| 8d | 分节顺序表达 | 数值 `Order` 常量 | 固定**中心顺序名列表**（`base` → `environment` → `context` → `skills` → `mcp_servers` → `subagents` → `instructions` → `tools`） | 本表 8b |
| 10a | 示例的交付形态 | Go 仓内 `examples/` | **独立模块 + 独立 profile**，不进默认 reactor；用例用脚本化替身模型（不碰网络） | SI-51 |
| 13a | 缓存键覆盖面 | `cacheKey(req)` | 覆盖完整请求上下文（schema 递归排序后哈希） | SI-50 |

> 「有意」的含义：**语义等价**，差别在**装配形状与失败时机**；跨语言回放时注意调用形状不同。

---

## 5. 维护规则

1. 本表只登记**语义级**差异；命名 / 包结构 / 语言惯用法差异不登记。
2. 每条差异必须给出 **Go 证据位置**（文件 + 符号），便于回查只读仓库。
3. 差异被实现后，把该行从 §3 移到 §2 并标注登记号（SI / B）。
4. 新增条目必须同时在 [`REVIEW-SHEET.md`](REVIEW-SHEET.md) 的 §5 有一条对应的 B 类判定项。
5. Go 仓库只读：核对时只 `cat` / `sed`，不写入。
6. 差异**实现完成 ≠ 已验收**：机器用例通过只代表实现自洽，跨语言回放与人工逐行签字仍是独立的门。
