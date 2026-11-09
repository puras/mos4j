# G1–G6 评审：问题清单与裁决结果

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> **状态：Q1–Q8 已由用户全部接受建议，并于 2026-11-07 实施完毕。**
> 本页保留「事实 → 选项 → 代价」的推理链（不删），并逐条补上**裁决**与**落地位置**；
> 机械证据与最终基线见每条的「裁决与实施」栏。
>
> 配套 [`REVIEW-SHEET.md`](REVIEW-SHEET.md)（**最终可签版**：含签字指引、稳定编号 I/M/R/SI/B/G、§7.1 签字进度表、§7.3 未评审条目栏）。这份清单只收**机器判不了、需要人拍板**的条目；
> 每条给出：事实与出处、两种口径的代价、我的倾向、以及「不决定会怎样」。
>
> 预填依据（2026-11-07，本轮真实核对）：
> - 引用存在性：评审表内 **34 条 `类.方法` 方法引用 + 17 条【代码】路径全部存在**（脚本核对，见 §8；另有 `*.java` 文件名引用与 `ToolCall.args` 字段引用，非方法引用）；
> - 机械门（实施后的最终基线）：默认 reactor **567 通过 / 0 失败 / 7 跳过**；`-Pstarter` **589 / 0 / 7**（**这是 Q1–Q8 批次的当时基线**；最新基线：默认 **633** / starter **655** / MCP **704**，见 `docs/mos4j-tasks.md` §14）；
>   12 模块 `dependency:analyze` 全 `No dependency problems found`；C6 扫描（`src/main` + `src/test`）spring=0、regex=0；
> - Go 侧实读（J8 批次期间完成，本轮沿用、未重读）：`sandbox/seatbelt_darwin.go`、`sandbox/pathscope.go`、`sandbox/seatbelt_darwin_test.go`、
>   `sandbox/scope_dynamic_darwin_test.go`、`llm/model.go`、`llm/gemini/gemini.go`（`require_finish`）。

---

## Q1　SDK 门面要不要给宿主一个「关掉路径强制」的出口？

| 项 | 内容 |
|---|---|
| **事实** | `Mos4j.Builder.fileTools()` 在 `PathScopes.supported()` 为真时**缺省装** `SeatbeltScope`（写根 = 工作区）。本机宿主策略不允许 `sandbox_apply` → 每条 shell 命令以 **exit 71** 失败（fail-closed 生效，见 `J8-STATUS.md` §5 探针输出）。Go 侧的 `tool/builtin/shell.go` 在没有 `sandbox.Runner` 时返回 `NotConfiguredError`——**宿主本来就可以选择不装**。 |
| **选项 A** | 保持现状（缺省装，无开关）。与 Go「有 runner 就装」一致；但**宿主没有退路**——即使这台机器根本不支持沙箱，也只能换机器。 |
| **选项 B** | 门面加显式出口：`pathScope(PathScope)` / `noPathScope()`，缺省仍装。Go 侧等价能力 = 「不传 runner」。**我的倾向**。 |
| **影响面** | `mos4j-sdk` 门面 API（新增 1–2 个 builder 方法）；Starter 相应属性；不改契约清单、不改事件、不改模块边界。 |
| **不决定的后果** | 在受限宿主上，SDK 用户只能换机器或自己绕开门面（自建 Agent）。 |
| **裁决与实施** | **接受 B（已实施）**：`Mos4j.Builder.pathScope(PathScope)` / `noPathScope()` / `pathScopeProbe(PathScopeProbe)`；`noPathScope()` 是显式关闭，Describe caveat 写明「宿主显式关闭（Mos4j.builder().noPathScope()）」；starter 对应属性 `mos4j.path-scope=disabled`；CLI 对应 `--no-path-scope`。守护测试：`Mos4jTest.noPathScopeIsDeclaredInTheSnapshot`、`Mos4jStarterKnobsTest.pathScopeDisabledIsHonoredAndDeclared`。 |

## Q2　Go 的动态 Scope：补还是不补？（本轮读 Go 源码后**拆成两件事**）

| 项 | 内容 |
|---|---|
| **事实（2a：真缺口）** | Go 的 `sandbox.Local` 有 `Scope func() *PathScope`（**每次调用现取**），注释原话：「典型用法：宿主的文件 jail 与 shell 沙箱**共用同一份根集合**（用户在会话中授权新目录时，两层同时放宽，不会出现『工具能读、命令写不了』的错位）」。Java 侧 `LocalRunner.pathScope` 是**装配期静态值** → 会话里 `grant_access` 授权新目录后，**文件工具能写、shell 写不了**。Java 侧的数据源是现成的：`RootSet` 已经是活根集合（加根对下一次调用立即生效）。 |
| **事实（2b：不是真缺口）** | Go 的**请求期** `Request.PathScope`（只能收窄）是执行器 API 层面的能力，但 **Go 自己的 shell 工具不传**（`tool/builtin/shell.go` 只传 `Command`/`Timeout`）——它只给「直接调 Runner 的宿主」用。 |
| **选项 A** | 补 **2a**：`LocalRunner` 加 `pathScopeSupplier`（每次调用现取）+ 按平台现造 scope 的工厂（`PathScopes.factory()`）。**我的倾向**。2b 暂不补，登记为有意差异。 |
| **选项 B** | 只补 2a 的 SDK 接线，不动 sandbox API | 会把「怎么造 scope」泄漏到门面（平台分支上浮）——不推荐 |
| **选项 C** | 都不补，登记为永久缺口 | 「工具能写、shell 写不了」的错位留在生产路径上 |
| **影响面** | `mos4j-sandbox`（`LocalRunner` + `PathScopes` 工厂）与 `mos4j-sdk` 接线；不改契约清单、不改事件、不改模块边界。约 1 人日。 |
| **不决定的后果** | 宿主每次授权目录都要自己把写根同步给 shell，一致性靠人工。 |
| **裁决与实施** | **接受 A′（已实施）**：动态写根已补——`LocalRunner.Builder.pathScopeSupplier(Supplier<List<Path>>)` + `PathScopeFactory`（按平台现造 scope），SDK 把 `RootSet` 的活根接进 supplier；**请求期 `RunRequest.pathScope` 不补**（Go 自己的 shell 工具也不传），登记为有意差异（见 `SELF-INVENTED.md`）。守护测试：`PathScopeRuntimeTest.dynamicWriteRootsAreFetchedOnEveryCall`、`DynamicPathScopeTest.aNewlyGrantedRootReachesTheNextShellCall`。 |

## Q3　Linux 后端「代码在位但默认不装配」——确认这个收窄？

| 项 | 内容 |
|---|---|
| **事实** | Go 的口径是「有后端就强制」。我们的 Landlock 后端代码在位，但**从未在 Linux 上跑过**（本机是 Darwin）。用户口径：**「先过 Mac 的，Linux 和 Windows 暂时不验证」**。因此 `PathScopes.supported()` 只认 macOS，Linux 上 SDK **不装**路径强制（行为与 J8 之前一致，无回归）。 |
| **选项 A** | 确认收窄（**我的倾向**）：未验证的隔离面不默认顶到生产路径。 |
| **选项 B** | 恢复「有后端就强制」：Linux 上缺省装未验证的 Landlock——一旦 FFM 绑定有 bug，Linux 宿主的 shell 会全废且无告警。 |
| **影响面** | 只影响 `PathScopes.supported()` 一行；已在 `J8-STATUS.md` D-J8-4 登记。 |
| **不决定的后果** | 评审表这一格没法签字（机械结论与语义结论不一致）。 |
| **裁决与实施** | **接受 A（维持现状，本轮不改行为）**：Linux Landlock 后端代码保留，但 `PathScopes.supported()` 只认已验证的 macOS Seatbelt → SDK 在 Linux 上默认不装配。已补明确文档（`J8-STATUS.md` 与本表 Q3）与守护测试（`PathScopeRuntimeTest.factoryBuildsTheLandlockBackend` 仅在 Linux 上执行）。Linux 真机验证后再改 `supported()`（一行）。 |

## Q4　协议逃生阀在 SDK 门面上不可达：补两个开关？

| 项 | 内容 |
|---|---|
| **事实** | Go：`llm.Config["require_finish"]="false"`（Gemini 逃生阀，给不报 `finishReason` 的网关）、`Config["max_tokens"]`（Anthropic 必填字段，宿主可指定）。Java：`GeminiChatModel` 有 `requireFinish` **构造参数**、`ChatRequest.maxTokens()`（Anthropic 有缺省兜底 `DEFAULT_MAX_TOKENS`）——**都在 LLM 层，但 `Mos4j.Builder` 没有对应入口**，用门面就够不着。 |
| **选项 A** | 门面补 `requireFinish(boolean)`（Gemini 生效）与 `maxTokens(Integer)`。**我的倾向**（否则 Go 宿主能做的事，Java 宿主用门面做不了）。 |
| **选项 B** | 登记为门面的缺省差异：门面只给「常用面」，逃生阀留给自建 Agent。 |
| **影响面** | `mos4j-sdk` 门面 + `mos4j-spring-boot-starter` 属性；不改协议行为本身。 |
| **不决定的后果** | 遇到不报 `finishReason` 的网关，SDK 用户只能退回自建 Agent。 |
| **裁决与实施** | **接受 A（两个旋钮都补，已实施）**：①`Mos4j.Builder.requireFinish(Boolean)` → `Mos4j.defaultModel(..., requireFinish)`；**只对 Gemini 生效**，用在其他协议或宿主自带模型上 **fail-fast**（不静默忽略），校验点在 `Mos4j.defaultModel(...)`（唯一真源，starter 也走它）。②`Mos4j.Builder.maxTokens(Integer)` → `Config.defaultMaxTokens` → `ReActPlanner` → `Request.maxTokens`（模型级缺省，不改 `Request` record 结构）。守护测试：`Mos4jProtocolKnobsTest`（5 条）、`ReActDriverTest.theConfiguredMaxTokensReachesEveryRequest` / `withoutTheSettingMaxTokensStaysUnset`、`Mos4jStarterKnobsTest.maxTokensReachesTheWire`。 |

## Q5　受限主机上要不要**提前**告诉用户「shell 会失败」？

| 项 | 内容 |
|---|---|
| **事实** | 现在是**运行期**才发现：命令 exit 71，stderr 里是 `sandbox-exec: sandbox_apply: Operation not permitted`。缺省装配后这会让宿主「装上就坏」，而错误信息在命令输出里、要等模型调 shell 才看得见。 |
| **选项 A** | 启动时做一次能力探测（跑 `sandbox-exec -p '(version 1)(allow default)' true`），不可用就在**装配期**明确报错/提示（fail-closed 提前到装配期）。**我的倾向**：提示 + 明确错误，而不是让第一次 shell 调用去撞。 |
| **选项 B** | 保持运行期暴露（与 Go 完全一致：Go 也是运行期 `ErrPathScopeUnsupported` / apply 失败）。 |
| **影响面** | `PathScopes` / SDK 装配期一次探测（多几毫秒）；不影响命令路径。 |
| **不决定的后果** | 用户第一次遇到的是「模型说命令失败了」，而不是「这台机器不支持沙箱」。 |
| **裁决与实施** | **接受 B（已实施）**：装配期探测——`PathScopes.probe()` 真跑一次 `sandbox-exec -p "(version 1) (allow default)" /usr/bin/true`（退出码 0=可用、71=apply 被拒、65=profile 解析失败），不通则在**装配期**抛 `IllegalStateException`，文案给出两条出路（换机器 / `noPathScope()`）并声明「不会退化成没隔离的假装隔离」。探针**可注入**（`Mos4j.Builder.pathScopeProbe(...)`、starter 的 `PathScopeProbe` Bean），测试与受管宿主据此避免真实 apply 被拒造成的假失败。守护测试：`Mos4jTest.anUnavailablePathScopeFailsFastAtAssembly` / `anAvailableProbeInstallsThePathScope`、`PathScopeRuntimeTest.probeResultIsSelfConsistent` / `probeDistinguishesApplyDenialFromMissingBackend`。 |

## Q6　口径澄清：I6「零开销降级」与「缺省装配路径强制」

| 项 | 内容 |
|---|---|
| **事实** | I6 的契约是「**可选能力未启用时**零额外开销」。缺省装配后，每条 shell 命令多一次 profile 生成 + 一个 `sandbox-exec` 进程——这是**能力启用**的必要开销，不是「未启用却常驻」的开销。 |
| **建议** | 不违反 I6，但**在 I6 的判定栏里写清这个口径**（避免评审时被理解成「零开销被破坏了」）。**我的倾向**：按此口径通过。 |
| **不决定的后果** | 评审时这条可能被误判成不通过。 |
| **裁决与实施** | **接受（口径已写入评审表）**：I6「零开销降级」指**可选能力未启用时**零额外开销；缺省装配路径强制属于**能力启用**的必要开销（每条命令一次 profile 生成 + 一个 `sandbox-exec` 进程），不违反 I6。评审表 I6 判定栏已写明该口径。 |

## Q7　口径澄清：M9「路径双校验」与 J8「路径强制」不是一回事

| 项 | 内容 |
|---|---|
| **事实** | M9 = **文件层**（`mos4j-fs`）：Lexical + Realpath 两层，**读也走 Realpath**。J8 的路径强制 = **进程层**（`mos4j-sandbox`）：只收窄**写**，读不限制（读由文件 jail 管）。两者作用面不同、强度不同。 |
| **建议** | 评审时按各自口径分别判定，别用 M9 的结论给 J8 背书，也别用 J8 的结论否定 M9。**我的倾向**：分别通过。 |
| **不决定的后果** | 容易在评审纪要里写出「路径已双校验」这种把两件事混成一件的结论。 |
| **裁决与实施** | **接受（分别判定）**：M9 = 文件层双校验（Lexical + Realpath，读也走 Realpath）；J8 路径强制 = 进程层（只收窄写）。两者各自判定，互不背书。评审表已分栏写明。 |

## Q8　确认 J7 的有意差异：`allowed-tools` 只诊断不生效

| 项 | 内容 |
|---|---|
| **事实** | Go 的技能 `allowed-tools` 会**真正收窄**可用工具；Java 侧只做求交 + 写 `skill/allowed-tools-mismatch` 事件，**不改变执行工具面**（J7 的决策：避免造第二套安全真相）。 |
| **选项 A** | 确认接受（**我的倾向**，已登记为 J7 有意差异）。 |
| **选项 B** | 改成真收窄 → 要动 `ExecPath.Security` 与工具面，装配期要多一道闸。 |
| **影响面** | 若选 B：J7 契约变更（字段/事件可能受影响），需重新冻结清单。 |
| **不决定的后果** | 「技能声明的 allowed-tools 不生效」这件事在评审表里没有结论。 |
| **裁决与实施** | **接受 A（维持现状，本轮不改行为）**：`allowed-tools` 只做求交诊断 + 写 `skill/allowed-tools-mismatch` 事件，不收窄执行工具面（避免造第二套安全真相）。已补文档与测试口径（`SELF-INVENTED.md` 的 J7 有意差异、`INVARIANT-TESTS.md` 的对应条目）。真收窄另立发布后任务。 |

---

# 详细说明与建议（2026-11-07 补充）

> 本节把 Q1–Q8 的事实链、可选方案（带 API 草案）、影响面、工作量与推荐结论写细，供拍板。
> 事实来源：Java 侧 `Mos4j.java` / `PathScopes.java` / `PathScope.java` / `LocalRunner.java` / `RunRequest.java` /
> `ShellTool.java` / `SandboxBoundary.java` / `RootSet.java` / `Request.java` / `GeminiChatModel.java` /
> `AnthropicChatModel.java` / `ReActPlanner.java` / `Mos4jTest.java`；
> Go 侧 `sandbox/pathscope.go` / `sandbox/sandbox.go` / `sandbox/run_unix.go` / `sandbox/seatbelt_darwin.go` / `tool/builtin/shell.go`。

## Q1（细化）SDK 门面要不要给宿主「关掉路径强制」的出口

### 事实链

- 缺省装配点：`Mos4j.Builder.fileTools()` → `if (PathScopes.supported()) runnerBuilder.pathScope(PathScopes.derive(anchor));`
  —— `supported()` = macOS 且 `/usr/bin/sandbox-exec` 可执行（本机为 true）。
- 装配期**不探测 apply 是否可用**，所以本机（宿主策略拒绝 `sandbox_apply`）照样装上 → 每条 shell 命令 exit 71。
- **退路其实存在，但不是「没有」**：`Builder.tools(ToolRegistry)` 覆盖缺省工具面 + `Builder.sandbox(SandboxBoundary)` 自报快照，
  宿主可自建 `LocalRunner.builder(anchor)`（不传 `pathScope`）→ 约 15–20 行样板；漏给 `sandbox(...)` 快照的话，
  Describe 会如实说「未装配命令沙箱」（不撒谎，但要看懂）。
- 现有 caveat 文案（`SandboxBoundary.caveats()`）：
  「路径强制=未装配（沙箱里的进程可以写 jail 之外的目录；文件工具仍受文件 jail 约束，但 shell 不受）；平台后端状态=…」
  —— **无法区分「平台没有后端」与「宿主显式关闭」**。

### 方案

| 方案 | 改动 | 代价 |
|---|---|---|
| **A 维持现状** | 无 | 受限主机上 SDK 用户的 shell 必坏；无自描述的关闭方式（只能写样板绕过） |
| **B 加显式出口（推荐）** | `Builder.pathScope(PathScope)`（显式指定）+ `Builder.noPathScope()`（显式关闭）；`LocalRunner` 记录「显式关闭」状态，`SandboxBoundary` caveat 改文案：「路径强制=未装配（**宿主显式关闭**）→ shell 可写 jail 之外」 | SDK 门面 +2 个方法；sandbox 快照多一个状态字段；starter 多 1 个属性。**不改契约清单、不改事件、不改模块边界** |
| **C 只加文档说明** | 只在 README 写「受限主机请自建工具面」 | 样板代码人人重写；错误信息仍只在模型回合里出现 |

### 建议：**B**。并与 Q5 合并实施（同一处代码：门面装配路径强制的那一段）。

## Q5（细化）路径强制装不上时：什么时候发现、用什么方式报

### 事实链

- 现状：**运行期**才发现。每条命令 exit 71，stderr 是 `sandbox-exec: sandbox_apply: Operation not permitted`。
  模型看到的是「命令失败了」，宿主看到的是第 N 轮的一次异常回执。
- 探测可行性（本机实测过）：`/usr/bin/sandbox-exec -p '(version 1) (allow default)' /usr/bin/true`
  → 正确 profile 走完解析、apply 被拒 → **exit 71**；写坏的 profile → **exit 65** + `unbound variable`。
  两者可区分，判据有效。开销 = 一次进程启动（毫秒级），适合放在装配期。
- **测试约束（本机必炸）**：`Mos4jTest` 等 10 条 SDK 门面测试在本机构配（`supported()==true`）。
  若「探测失败 → 装配期抛错」而不给注入点，**这批测试会全红**（本机 apply 被拒）。
  → 探测必须可注入/可绕过。

### 方案

| 方案 | 行为 | 与既有口径的关系 |
|---|---|---|
| **A 运行期失败（现状）** | 每条命令 exit 71 | 错误发现得晚；但**与 Go 完全一致**（Go 也是运行期 `ErrPathScopeUnsupported` / apply 失败） |
| **B 装配期探测 + 失败即报错（推荐，与 Q1 合并）** | 装配期跑一次探测：可用 → 装；不可用 → **抛错**，错误信息给两条出路：①换一台允许应用沙箱的 macOS；②显式 `noPathScope()`（此时 Describe 声明「宿主显式关闭 → shell 不受路径强制」）。探测可注入（`Builder.pathScopeProbe(...)`），测试用它返回「可用」或显式 `noPathScope()` | **正好命中我们的既有约束**：「不支持/装配失败必须 fail-closed 明确报错，绝不退化成『没隔离的假装隔离』」。探测失败仍**装**路径强制（只是环境不支持）→ 抛错是 fail-closed |
| **C 探测失败 → 不装 + caveat** | 静默降级 | **违反既有约束**（退化成没隔离）；不建议 |

### 建议：**B**。代价：sandbox 加一个探测（约 30 行）+ 门面装配期调用 + SDK 测试改注入点（约 10 处 builder 调用）。

## Q2（细化）Go 的动态 Scope 缺口：拆成两件事看

### 事实链（本轮读 Go 源码得出，与原登记的 D-J8-6 需要修正）

- Go 有**两个**独立机制：
  1. **`Local.Scope func() *PathScope`（动态来源，每次调用现取）**——注释原话：「典型用法：宿主的文件 jail 与 shell 沙箱**共用同一份根集合**（用户在会话中授权新目录时，两层同时放宽，不会出现『工具能读、命令写不了』的错位）」。
     **← 这才是 Java 侧的真实缺口**（D-J8-6）。
  2. **`Request.PathScope`（请求期范围）**——Go 的 `sandbox.Request` 有这个字段，但**Go 自己的 shell 工具不传**（`tool/builtin/shell.go` 只传 `Command` 与 `Timeout`）；
     它是给「直接调 Runner 的宿主」用的。语义：只能收窄（必须是默认范围的子集），放宽只能装配期声明。
- Java 侧现状：`LocalRunner.pathScope` 是**装配期静态值**（`final PathScope`），`RunRequest` 只有 `command/timeout/maxOutputBytes`。
- Java 侧文件 jail 已经是「活根集合」（`RootSet`：加根对下一次调用立即生效、锚点稳定、变更留痕），
  `RootSet.roots()` 能拿到当前全部根 → **接动态 scope 的数据源是现成的**。

### 方案

| 方案 | 改动 | 价值 |
|---|---|---|
| **A 补动态 scope（推荐）** | `LocalRunner.Builder.pathScopeSupplier(Supplier<List<Path>>)`：每次 `run()` 现取写根 → 用 `PathScopes.factory()` 现造 scope（macOS → `SeatbeltScope`）；静态 `pathScope` 保留为兜底（supplier 为 null 时用）。SDK 装配时把 `RootSet` 的活根接到 supplier → **文件工具与 shell 写根同源**（Go 的 `ScopeFunc` 语义） | 消除「工具能写、shell 写不了」的错位；与 Go 对齐 |
| **A' 只补 A，不补请求期字段** | 同上，但**不加** `RunRequest.pathScope` | Go 内部也没用请求期字段（只有直接调 Runner 的宿主用）→ 价值低，可登记为有意差异 |
| **B 都不补** | 无 | 错位保留：会话里 `grant_access` 授权的目录，shell 永远写不了 |

### 建议：**A'**（动态 scope 必补，请求期字段暂不补，登记差异）。约 1 人日。

## Q4（细化）协议旋钮的门面可达性：两个开关代价不同

### 事实链

- **`require_finish`（Gemini 逃生阀）**：Go 侧是 `Config["require_finish"]="false"`；Java 侧 `GeminiChatModel(config, boolean requireFinish)` **已有构造参数**，
  测试 `g5b_requireFinishFalse_allowsMissingFinish` 已覆盖。缺的只是**门面入口**：`Mos4j.defaultModel(...)` 走 `GeminiConfig.of(...)`，
  没有把 `requireFinish` 传下去。→ **纯门面改动**（sdk 一个 Builder 字段 + 一行透传），零协议层改动。
- **`max_tokens`（Anthropic 必填字段）**：Java 侧 `AnthropicChatModel` 用 `request.maxTokens()`，null → `DEFAULT_MAX_TOKENS=4096`。
  但 `Request.maxTokens` 是**每请求**字段，由 `ReActPlanner` 构造（`new Request(history, tools, null, null, ...)`，其余旋钮全 null）。
  → 门面要能设它，链路是 **sdk → Config → ReActPlanner → Request**，跨 3 个模块（driver 层要新增一个缺省注入点）。
  Go 侧对照：Go 的 `Config["max_tokens"]` 是**模型级**配置。

### 方案与建议

| 旋钮 | 建议 | 工作量 |
|---|---|---|
| `requireFinish` | **补**（门面加 `requireFinish(boolean)`，只对 Gemini 生效，其他协议忽略并写进 Describe 免得宿主以为生效） | 0.25 人日 |
| `maxTokens` | **补**，但按「模型级缺省」实现：`Config` 加 `defaultMaxTokens`，`ReActPlanner` 构造 Request 时用它填 `maxTokens`（null → 模型自己的 4096 兜底）。这样不用动 `Request` 结构 | 0.5 人日 |

或者：**都不补**，登记为「门面只给常用面，逃生阀留给自建 Agent」——代价是 Go 宿主能做的事，Java 宿主用门面做不了。

## Q3 / Q8（细化）

- **Q3（Linux 默认不装配）**：建议**维持**。理由：Go 的口径是「有后端就强制」，我们把「有后端」收紧为「有**已验证**的后端」——
  这不是削弱保护，而是把「未验证的隔离面」从生产路径上撤下来。Linux 真机验证通过后，把 `PathScopes.supported()` 改成「macOS 或（Linux 且 Landlock 自检通过）」即可（一行）。工作量 0（等环境）。
- **Q8（`allowed-tools` 只诊断）**：建议**维持**。改成真收窄要动的东西：driver 每轮组装工具面时按「当前激活技能」过滤 +
  ExecPath 执行期白名单（现在 `ExecPath.Security` 是安全边界，技能级收窄是**第二套安全真相**——J7 已明确拒绝）+
  `skill/allowed-tools-mismatch` 事件语义要改（不再只是诊断）+ 可能要动冻结清单。
  若坚持要做，建议**列为发布后的可选能力**，不在本次评审里拍。

## 汇总：采纳建议后的工作量与顺序

| 序 | 项 | 涉及模块 | 估时 | 风险 | 状态 |
|---|---|---|---|---|---|
| 1 | **Q5 + Q1**（探测 + 显式出口 + caveat 区分） | sandbox / sdk / starter / cli | 1 人日 | 低（不改契约；SDK 测试需改注入点） | ✅ 已实施 |
| 2 | **Q2'**（动态 scope，对齐 Go `Local.Scope`） | sandbox / fs 接线 / sdk | 1 人日 | 中（写根动态变化 → profile 每次重生成；需覆盖「加根后 shell 能写」用例） | ✅ 已实施 |
| 3 | **Q4a**（`requireFinish` 门面开关） | sdk | 0.25 人日 | 低 | ✅ 已实施 |
| 4 | **Q4b**（`maxTokens` 缺省注入） | sdk / driver | 0.5 人日 | 中（动 driver 的 Request 构造点） | ✅ 已实施 |
| — | Q3 / Q8 | — | 0 | — | ✅ 维持现状（只补文档与测试口径） |

合计约 **2.75 人日**，全部**不改契约清单、不改事件名、不改 12 模块边界**（Q2 改 `RunRequest` 之外的 sandbox API，不进冻结清单）。

实施后的机械基线：默认 reactor **567 / 0 失败 / 7 跳过**（12 个测模块）；`-Pstarter` **589 / 0 / 7**（13 个测模块）；
12 模块 `dependency:analyze` 全绿；C6 扫描 spring=0、regex=0；裸 NUL 扫描 0。

## 如果不拍板，我的默认动作（请确认或改）

> **已拍板（2026-11-07，用户「全部接受建议」）并全部实施完毕**；下面保留当时的默认动作清单作为实施对照。

1. Q1 → 按 **B** 实施（门面加 `pathScope(...)` / `noPathScope()`，caveat 区分「显式关闭」）。
2. Q5 → 按 **B** 实施（装配期探测；不可用 → 抛错并给两条出路；探测可注入）。
3. Q2 → 按 **A'** 实施（动态 scope 必补；请求期 `RunRequest.pathScope` 暂不补，登记差异）。
4. Q4 → 按建议实施（`requireFinish` + `maxTokens` 缺省都补）。
5. Q3 → 维持现状（Linux 不默认装配，等真机验证）。
6. Q8 → 维持现状（`allowed-tools` 只诊断），若要真收窄另立任务。

---

## 附：预填里**没有**争议、直接建议通过的条目

以下条目的机械证据齐、语义与 Go 的既有对拍结论一致，本轮直接给「建议通过」：
I1 / I2 / I3 / I4 / I5 / I7、M1 / M2 / M3 / M4 / M6 / M7 / M8 / M10、
§3 协议口径除「Gemini 逃生阀可达性」外的各行、
SI-1…SI-30（除 SI-11 / SI-25 / SI-30 另有说明外）、
B2 / B4 / B6 / B7 / B8 / B9 / B11、
G1 / G2 / G4 / G5 / G6。
