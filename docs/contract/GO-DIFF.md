# Go 参考实现（MoS）↔ Java（MoS4J）差异登记表

> 状态：**2026-11-09 第五轮复核版**（第三轮 13 项差异全部补齐后，第四轮补入 web_search / web_fetch / Anthropic 能力位 3 项，第五轮把 web_fetch 整套重做并新增 HTML 实体两处有意偏离）。
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
| #15 | 请求侧 `reasoning_content` 回传（DeepSeek / Qwen thinking 模式多轮） | 能力缺口 | ✅ 已对齐（2026-11-08 二次复核发现并修复） |
| #16 | `ContentPart` 的 audio / file 三协议映射 | 能力缺口 | ✅ 已对齐（file 一个有意差异见 SI-53） |
| #17 | `web_search` 的参数与回执形状 | 语义差异 | ✅ 已对齐（多查询 `queries[]` + 结构化回执） |
| #18 | `web_fetch` 的分层与抓取后端可注入性 | 能力缺口 | ✅ 已对齐（全防线下沉 `PinnedConnector`；裸 Socket 真钉住；回执 6 键固定；非 2xx 是结果） |
| #19 | Anthropic 能力位多报 `STRUCTURED_OUTPUT` | 能力位失实 | ✅ 已对齐（删 `STRUCTURED_OUTPUT`；`REASONING` 保留为**欠报**对位） |
| #20 | Go 的十六进制实体解码事实上从不生效（`ParseInt("x41")` 必失败） | Go 实现瑕疵 | ⚠️ Java 按意图解（见 §3 #20） |
| #21 | Go 的代理对码点解码静默变成 U+FFFD | Go 实现口径 | ⚠️ Java 原样保留实体（见 §3 #21） |
| #22 | Go 的「内嵌 IPv4 的 IPv6 也按 IPv4 判」事实上是死代码（`embeddedIPv4` 返回 16 字节 → `isPublicIPv4` 首条 `v4[0]==0` 必中） | Go 实现瑕疵 | ⚠️ Java 对齐**可观测行为**（一律拒），不对齐注释意图（见 §3 #22） |

> **编号说明**：#1–#10 是 2026-11-07 第一轮初查的编号；#11–#13 是同日逐行复核新增；#14 已在 SI-15 登记。
> **#15 / #16 是 2026-11-08 第三次复核（出站报文抓包）发现的真缺口**：两者都是「声明了但没做到」，
> 且有实际后果（thinking 模式多轮 400；多模态内容静默丢弃），因此归为能力缺口而非语义差异。
> **#20 / #21 是 2026-11-09 运行 Go 参考实现实测得到的**：两条都是 Go 侧的实体解码瑕疵（不是 Java 缺能力），
> Java 有意偏离并给出理由，因此状态标 ⚠️ 而非 ✅。
> **本轮结论**：13 项差异的生产代码与定向用例全部落地；剩余工作是文档登记与人工签字，不是能力缺口。
> **2026-11-08 二次复核补记**：#15 / #16 两项新增缺口已修复（出站报文抓包验证），详见 §3 末尾。

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

### #15 请求侧 `reasoning_content` 回传

- **Go 证据**：`llm/openai/openai.go:87`（`doChat` 里 `body = injectReasoningContent(body, req.Messages)`）、同文件 `injectReasoningContent`（只对 assistant 消息且理由非空时逐条注入，其余字节不动）。
- **Go 语义**：DeepSeek / Qwen 等 thinking 模式的**服务端要求**把上一轮的思维链原样回传；不回传不是「少一个可选字段」，是第二轮直接 **400**。
- **Java 落点**：`OpenAiDtos.MessageParam` 新增 `reasoning_content` 字段；`OpenAiChatModel.toMessageParam` 通过 `reasoningContentOf(...)` 只在 **role=assistant 且内容非空**时赋值（其余情况留 null，`NON_NULL` 不输出）。Go 因为生成的请求结构体没这个字段只能序列化后做字节级注入；Java 的请求 DTO 是自己写的，多一个字段就能得到同样报文。
- **修复前的实际后果**（2026-11-08 抓包实测）：assistant 消息带 `reasoningContent` 时，出站报文里根本没有 `reasoning_content`；DeepSeek-R1 / Qwen thinking 模式多轮对话必挂。
- **范围界定**：**只改 OpenAI 兼容协议**。Anthropic 侧 Go 也不回传 thinking 块（`anthropicAssistantContent` 只产出 text + tool_use），所以不算缺口、不擅改。
- **守护用例**：`OpenAiChatModelTest.reasoningContent_isEchoedOnAssistantMessages` / `reasoningContent_absentWhenEmptyOrNonAssistant`（均断言出站报文）。

### #16 `ContentPart` 的 audio / file 三协议映射

- **Go 证据**：`llm/openai/openai.go` 的 `toOpenAIContent`（`audio` → `input_audio`；`file` → `image_url` + `mediaURL`，包含 `audioFormat` 的 MIME→格式映射）；`llm/anthropic/anthropic.go` 的 `anthropicContent`（`file` → `document` 块，`NewAnthropicDocumentSourceBase64`；audio 无对应块类型）；`llm/gemini/gemini.go` 的 `geminiParts`（`image` / `audio` 有 URL 走 `fileData` 否则 `inlineData`；`file` 只走 `fileData`）。
- **Go 语义**：四种类型 `text` / `image` / `audio` / `file` 在三个协议上各有真实落地形状；`mediaURL` 口径 = **URL 优先**，否则 `data:\u003cmime\u003e;base64,\u003cdata\u003e`，mime 缺省 `image/png`。
- **Java 落点**：
  - **OpenAI**：`image` 与 `file` 都走 `image_url`（`imagePart` + `mediaUrl`，URL 优先、data URL 兜底、mime 缺省 `image/png`）；`audio` 走 `input_audio`，`audioFormat` 改成 Go 的 switch 口径（`audio/mpeg`→`mp3` 等，未知→`mp3`）。**删掉了原来的 `default → Map.of("type", part.type())` 空壳分支**。
  - **Anthropic**：`file` → `{"type":"document","source":{"type":"base64","media_type":\u003cmime\u003e,"data":\u003cdata\u003e}}`；`media_type` 原样用宿主给的 MIME（**不**像 image 那样缺省 `image/png`，对齐 Go）；`audio` **不发块**。
    **注**：Go 的注释写「audio 暂不支持被忽略」，但代码里 audio 落在 `default: // text` 分支，实际会发出一个**空白 text 块**（注释与实现不一致，Go 的 `TestAnthropicContent` 也没覆盖 audio）。Java 按**注释的意图**实现（不发块），不照抄这个代码副作用。
  - **Gemini**：`GeminiDtos.Part` 新增 `fileData` 字段与 `FileData` record + `ofFileData(...)` 工厂；`image` / `audio` 有 URL 走 `fileData`（MIME **原样**，对齐 Go 的 `MIMEType: p.MIME`）、否则 `inlineData`（mime 缺省 `image/png`，对齐 Go 的 `mimeOr`）；`file` 只走 `fileData`；**不再静默丢弃**。
- **修复前的实际后果**（2026-11-08 抓包实测）：`file` 块出站是 `{"type":"file"}`（url / data 全丢）；Gemini 的 `audio` / `file` 被直接丢掉——宿主以为发了，其实没发。
- **有意差异（SI-53）**：未知内容块类型 Java **报错**，Go 是 `default` 分支当 `text` 默默兜底（Anthropic 的 audio 也因此会发一个空 text 块）。理由：把未知块当文本发，等于把「一个视频块」当「空字符串文本」送上去，模型看到的请求与宿主想的不一样，且没有任何错误线索。
- **守护用例**：OpenAI 4 条（file data URL / file URL 优先 / `audio/mpeg`→`mp3` / 未知类型报错）；Anthropic 2 条（document 块逐字段、audio 不发空壳块）；Gemini 5 条（audio inlineData / audio fileData / file fileData / `fileData` 与 `inlineData` 的 MIME 缺省分野 / 未知类型报错）。

### #17 `web_search` 的参数与回执形状

- **Go 证据**：`tool/web/web.go` 的 `searchParams`（`Queries []string` / `Query string` / `Limit int`）、`normalizeQueries`、`Execute` 的返回 map、`renderSearch`；`tool/web/provider.go` 的 `Searcher.Search(ctx, queries []string, limit int)`、`SearchResult{Summary, Note, Sources, Truncated}`、`Source{URL, Title, Snippet, PublishedAt}`、`MultiSearcher`（并行 + URL 去重）。
- **Go 语义**：`queries[]` 是首选形状（1–4 条并行），`query` 是单条容错；`limit` 缺省/上限都是 `DefaultMaxResults = 8`；回执是**结构化 map**（`queries` / `summary` / `note` / `sources` / `count` / `truncated` / `content`）；provider 失败是 **error**（`web_search 失败: %w`），但**部分查询失败不算全失败**（有结果就用结果，失败条数进 `note`；全部失败才 error）。
- **Java 落点**：`SearchProvider.search(List<String>, int)` 返回 `Outcome(summary, note, sources, truncated)`；新增 `SearchProvider.Single` + `WebSearchTool.of(...)`（虚拟线程并行、URL 去重、部分失败进 note、全部失败抛 `ToolException`）；`WebSearchTool.Params(queries, query, limit)`，`MAX_QUERIES=4` / `MAX_RESULTS=8`；回执同形 map（`ExecPath.render` 序列化成 JSON 文本回填，与 Go `json.Marshal` 同效果）。
- **破坏性变更**：`SearchProvider` 从单查询改成多查询（宿主实现要改签名；只有单查询的厂商 API 用 `Single` + `of()` 装配）。已获用户批准。
- **修复前的实际后果**：Java 只收 `query`（模型发 `queries[]` 直接参数校验失败），`limit` 缺省 5 / 上限 10（与 Go 的 8 不同），回执是纯文本（宿主拿不到 `count` / `truncated` / `note`）。
- **守护用例**：`WebToolsTest` 9 条（结构化回执逐字段 / 多查询去重合并 / `query` 单条容错 / 空查询与超 4 条报错 / `limit` 缺省与上限 8 / provider 失败是工具错误 / 部分失败进 note / 全部失败是错误）。

### #18 `web_fetch` 的分层与抓取后端可注入性

- **Go 证据**：`tool/web/provider.go` 的 `Fetcher`（`Name()` + `Fetch(ctx, url) (FetchResult, error)`）；`tool/web/fetch.go` 的 `HTTPFetcher`（缺省实现，带 SSRF 五防线 + 内容归类 + HTML 清洗）；`tool/web/web.go` 的 `Fetch.Fetcher` 字段 + `Tools(searcher, fetcher)`（nil → 不给工具）。
- **Go 分层（关键）**：工具层 `Fetch.Execute` 只做「参数校验 + 字符截断 + 回执整形」，**不解析 DNS、不跟重定向、不做地址判定**；五条防线、内容归类、HTML 转文本全在 `HTTPFetcher.Fetch` 里。所以「换掉实现 = 换掉防线」成立。
- **Go 可测性**：`HTTPFetcher` 有两个注入点——`Resolver`（`LookupIP`）与 `Dial`（`DialContext`）；测试用「解析器谎报公网地址 + 拨号器指向本地监听」组合（`fetch_test.go` 的 `testFetcher`/`dialTo`），于是传输层行为（chunked、体积上限、重定向链、404）都能真跑。
- **Java 落点**：
  - `Fetcher`（`name()` + `fetch(String, FetchLimits) → FetchResult`）；
  - `FetchLimits` record（字节 / 字符 / 超时 / 跳数 / UA，`defaults()` + `normalized()`）；
  - `FetchResult` 顶层 record（url / statusCode / title / contentType / content / truncated）；
  - `PinnedConnector implements Fetcher`（`name() = "http"`），构造器 `()` 与 `(Resolver, Dialer)`；
  - `Resolver` / `Dialer` 两个函数接口（对齐 Go 的两个注入点）；
  - `WebFetchTool` 构造器 `()` / `(Fetcher)` / `(Fetcher, FetchLimits)` / `(Fetcher, FetchLimits, int)`；`Fetcher` 允许为 `null`（对齐 Go 的 `Fetcher` 字段可为 nil），未装配在**执行期** fail-closed。
- **防线分层（本轮重做）**：第一版把地址校验放在 `WebFetchTool` 里——注入后端就被工具层拦死（实测：`web_fetch 失败: 主机解析失败: x.dev`），「换实现 = 换防线」不成立。现在五条防线全部下沉到 `PinnedConnector`。
- **地址钉住必须裸 Socket**：`HttpURLConnection` / `HttpClient` 都无拨号钩子；`Proxy.Type.DIRECT` 会忽略代理地址、照常解析主机名 = **假钉住**。JDK 内唯一真钉法是裸 `Socket` + 手写 HTTP/1.1 GET（`PinnedConnector` 当前实现：状态行 / 头 / chunked / gzip / 字节上限 / TLS-SNI）。
- **回执 6 键固定都在**（Go map 字面量无 `omitempty`）：`url` / `status_code` / `title` / `content_type` / `truncated` / `content`；空标题发 `""`，`truncated` 发 `false`。上一轮的「空值省略」语义**作废**。
- **非 2xx 是结果不是错误**：404 带 `status_code=404` 正常返回（Go `fetch_test.go:212-213` 同断言）；上一轮旧实现抛 `"HTTP {status}"` 已废。
- **HTML 抓取侧清洗**：`HtmlText`（`title` / `toText` / `decodeEntities`）逐字移植 Go 的六条正则与十项实体表，正则用 RE2J（C6 禁 `java.util.regex`）。
- **破坏性变更（第二次）**：`Fetcher.fetch` 从 `fetch(FetchRequest)` 改成 `fetch(String, FetchLimits)`；删 `lastReadTruncated()`（`FetchResult.truncated` 已足够，Go 接口也只有两个方法）；删 `WebFetchTool.FetchRequest`、密封 `WebFetchTool.FetchResult`、`DEFAULT_MAX_BYTES` / `MAX_REDIRECTS` / `MAX_URL_LENGTH` / `DEFAULT_TIMEOUT`（全部归到 `FetchLimits` / `FetchResult` / `PinnedConnector`）。
- **错误文案与检查顺序逐字对齐 Go**：`url` 空 → `url 不能为空`（**无** `web_fetch 失败: ` 前缀）；后端未装配 → `web_fetch 未装配抓取后端（宿主注入 Fetcher 后才可用）`；两句都在**执行期**且 `url` 检查在前；只有后端抛错才包 `web_fetch 失败: ` 前缀。
- **守护用例**：`WebToolsTest` **22 条**（search 9 + fetch 13：回执 6 键全 / 404 是结果 / 空值不省略 / 字符与码点双截断 / 自定义后端可绕开公网校验 / 后端异常包成工具错误 / 四类私网拒绝 / URL 拒绝 / 两句执行期文案与优先级）；`PinnedConnectorTest` **9 条**（内容类型归类 / 同源 / URL 校验 / 公网地址完整清单 / 内嵌 IPv4 一律拒 / 上限归一化 / UA）；`PinnedConnectorTransportTest` 22 条（真 socket：出站报文 / chunked + trailer + 非法长度 / gzip / 头名大小写 / 字节与字符上限 / 404 / 三类内容类型守卫 / 同源与跨源重定向 / 跳数上限 / 缺 Location / 注入解析器仍过公网校验 / 解析失败 / 非 HTTP 响应）；`HtmlTextTest` 9 条。
- **公网判定清单逐字对齐 Go `TestIsPublicIP`**（2026-11-09 第五轮复核补）：Java 原来只覆盖 13 条私有地址 + 3 条公网地址，漏了 TEST-NET（`192.0.2/24` / `198.51.100/24` / `203.0.113/24`）、`0/8`、`224/4` 起整段、`255.255.255.255`，以及 IPv6 内嵌 IPv4 的四种变体——**实测确认 NAT64（`64:ff9b::7f00:1`）/ 6to4（`2002:7f00:1::`）/ Teredo（`2001::1:2:3:4`）当时全部被判为公网**，是可用的 SSRF 绕过。现按 Go 完整清单（23 private + 4 public）逐条断言。

### #20 Go 的十六进制实体解码事实上从不生效

- **Go 证据**：`tool/web/fetch.go` 的 `htmlHexEnt` 回调——正则 `&#x…;` / `&#X…;`（十六进制数字组）匹配后，回调里 `strconv.ParseInt(strings.TrimSuffix(strings.TrimPrefix(strings.TrimPrefix(e, "&"), "#"), ";"), 16, 32)` 得到的字符串是 `"x41"`（**`x` 没被去掉**），`ParseInt` 必然报错 → 原样返回。
- **实测证据**（`.j7log2/htmlref/edge.go`，go1.27.0 darwin/arm64）：`&#x41;` → `"&#x41;"`、`&#x1F600;` → `"&#x1F600;"`、`&#xFFFD;` → `"&#xFFFD;"`；十进制都正常（`&#128512;` → `😀`）。
- **Go 语义**：正则写了十六进制分支、意图明显是「也解十六进制」，但实现在去前缀时漏了一个字符。
- **Java 落点**：`HtmlText.decodeNumeric` 按**意图**解十六进制（`&#x41;` → `A`、`&#X41;` → `A`、`&#x1F600;` → `😀`）。
- **为什么偏离**：把 `&#x27;` 这类实体原样发给模型是确定的噪声；且「正则写了分支却不生效」是明显的实现瑕疵而非设计选择。与 Go 注释与实现冲突时跟随意图的既有惯例（同 SI-53 的 Anthropic audio）一致。
- **守护用例**：`HtmlTextTest.hexEntitiesAreDecoded`（三个十六进制形）与 `decodesInTwoPasses`（`&amp;#x41;` → `A`，注明此处与 Go 不同）。

### #21 Go 的代理对码点解码会静默变成 U+FFFD

- **Go 证据**：`htmlNumEnt` 回调 `return string(rune(n))`——`n` 在 `(0, 0x10FFFF]` 内就放行，但 `rune` 落在代理区 `0xD800–0xDFFF` 时 Go 的 `string(rune)` 会产出替换字符。
- **实测证据**（同上）：`&#55296;`（`0xD800`）→ `"\uFFFD"`；`&#57343;`（`0xDFFF`）→ `"\uFFFD"`。
- **Java 落点**：`HtmlText.parseCodePoint` 拒代理区，实体**原样保留**（`&#xD800;` 不变）。
- **为什么偏离**：U+FFFD 是「已经坏了，但假装读到了字符」；保留实体至少提示「这里有个我们没解的东西」，也避免产出非法 UTF-16 序列。方向是更保守，不是更激进。
- **守护用例**：`HtmlTextTest.surrogateCodePointsKeepTheEntity`（`&#xD800;` / `&#55296;` / `&#xDFFF;` 均原样）。

### #22 Go 的「内嵌 IPv4 的 IPv6 也按 IPv4 判」事实上是死代码

- **Go 注释的意图**（`tool/web/fetch.go` 的 `isPublicIPv6`）：「IPv6 里的内嵌 IPv4（IPv4-mapped / NAT64 / 6to4 / Teredo）也按 IPv4 判」——四个分支各自抽出内嵌 IPv4 再调用 `isPublicIPv4`。
- **Go 实际行为**：`embeddedIPv4` 返回 `net.IPv4(...)`，那是 **16 字节**形式（前面 12 字节是 `00…00 ff ff`），交给 `isPublicIPv4` 后第一条 `v4[0] == 0` 必然命中，于是**四个分支永远返回 false**。
- **实测证据**（`.j7log2/goreal/`：直接复制 Go 的 `fetch.go` 改包名后调用）：
  - `64:ff9b::808:808`（NAT64 内嵌 **8.8.8.8**，公网）→ `public=false`；
  - `2002:808:808::`（6to4 内嵌 8.8.8.8）→ `public=false`；
  - `embeddedIPv4(64:ff9b::808:808, 96)` 返回 `[0 0 0 0 0 0 0 0 0 0 255 255 8 8 8 8]`（`len=16`，`String()` 显示 `"8.8.8.8"`），`isPublicIPv4(embedded) = false`。
  - 只有 IPv4-mapped（`::ffff:8.8.8.8`）例外——它被 `ip.To4()` 先折成 4 字节，根本不走内嵌分支。
- **可观测结论**：Go 对 NAT64 / 6to4 / Teredo **一律拒**，与内嵌什么地址无关。
- **Java 落点**：`PublicAddresses.isEmbeddedV4Form` 只判前缀、**不抽内嵌地址再判**，直接返回「不是公网」。
- **为什么对齐行为而不对齐意图**：按意图判内嵌地址会**放行 Go 拒掉的地址**（如 `64:ff9b::808:808`）——安全边界只能收紧不能放松，尤其在 SSRF 防线上。「判得比参考实现更宽」需要单独的安全评审，不属 Bug 修复范围。
- **代价**：只跑 NAT64 的纯 IPv6 网络枠不到网页（与 Go 同样的局限，属设计取舍而非回归）。
- **与 #20 / #21 的区别**：#20 / #21 是 Java 故意偏离（Go 的行为会产出无意义结果）；本条是 Java **对齐** Go 的可观测结果，只是不跟随它注释里的意图。
- **守护用例**：`PinnedConnectorTest.publicAddressClassification`（Go 完整清单 23 private + 4 public）与 `embeddedIpv4VariantsAreRejectedRegardlessOfInnerAddress`（含内嵌公网地址的 `64:ff9b::808:808` / `2002:808:808::` 也拒）。

### #19 Anthropic 能力位多报 `STRUCTURED_OUTPUT`

- **Go 证据**：`llm/anthropic/anthropic.go` 的 `Capabilities()` = `CapFunctionCalling | CapStreaming | CapMultimodal`（无 `CapStructuredOutput`、无 `CapReasoning`）；Anthropic 协议本身没有 `response_format`（`grep ResponseFormat llm/anthropic/*.go` = 0 命中）。
- **Go 语义**：三项能力位。
- **Java 落点**：`AnthropicChatModel.capabilities()` 删掉 `STRUCTURED_OUTPUT`，保留 `FUNCTION_CALLING` / `STREAMING` / `MULTIMODAL` / `REASONING`。
- **有意保留 `REASONING`**：Java 侧真的实现了（`reasoningEffort` → `thinking.budget_tokens`，以及 `thinking` 块与 `thinking_delta` 两个入站通道）；Go 不声明是 **Go 的欠报**，不是我们多报。方向相反，登记在本条而非 §4。
- **修复前的实际后果**：能力位是对外公开的（`Describe` 快照、宿主据此决策），多报 `STRUCTURED_OUTPUT` 会让宿主对 Anthropic 发 `responseFormat` 然后被无声忽略；SDK 内无 fail-closed 消费方，所以不会引发运行时错误。
- **守护用例**：`L1AnthropicScenariosTest.a_capabilitiesHaveNoStructuredOutput`（断言 FC/STREAMING/MULTIMODAL 在、`STRUCTURED_OUTPUT` 不在）。
- **未处理（同行缺口）**：Gemini 反向缺 `REASONING`（Go `Capabilities()` 声明了）与 Gemini `responseFormat` 未实现——本轮暂缓（无 Gemini 接口环境验证），登记待办。

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
| 16a | 未知内容块类型的处理 | `toOpenAIContent` / `anthropicContent` / `geminiParts` 的 `default` 分支当 `text` 兜底（gemini 是静默跳过） | **报错**（`IllegalArgumentException`）：把未知块当文本发，模型看到的请求与宿主想的不一样，且无任何错误线索 | SI-53 |
| 16b | Anthropic 的 `audio` 块 | 注释写「暂不支持被忽略」，但代码落在 `default: // text` → 实际发出**空白 text 块**（注释与实现不一致） | **不发块**（按注释意图）：空文本块对模型是噪声 | SI-53 |
| 16c | OpenAI 的 `audio` 只给 URL（无 base64） | 发出 `{"data":"","format":"mp3"}`（`input_audio` 只读 `p.Data`） | **报错**：`input_audio` 本来就不接受 URL | SI-53 |
| 16d | `audioFormat` 对带参数 MIME 的处理 | 不剥离参数（`audio/wav; codecs=1` → `default` → `mp3`） | **先剥离 `;` 参数再映射**（→ `wav`）；不论哪种，未知 MIME 都落 `mp3` | SI-53 |

> 「有意」的含义：**语义等价**，差别在**装配形状与失败时机**；跨语言回放时注意调用形状不同。

---

## 5. 维护规则

1. 本表只登记**语义级**差异；命名 / 包结构 / 语言惯用法差异不登记。
2. 每条差异必须给出 **Go 证据位置**（文件 + 符号），便于回查只读仓库。
3. 差异被实现后，把该行从 §3 移到 §2 并标注登记号（SI / B）。
4. 新增条目必须同时在 [`REVIEW-SHEET.md`](REVIEW-SHEET.md) 的 §5 有一条对应的 B 类判定项。
5. Go 仓库只读：核对时只 `cat` / `sed`，不写入。
6. 差异**实现完成 ≠ 已验收**：机器用例通过只代表实现自洽，跨语言回放与人工逐行签字仍是独立的门。
