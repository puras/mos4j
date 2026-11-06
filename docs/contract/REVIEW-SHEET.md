# 人工语义评审表（G1–G6）

> **这份文件是给人用的**：机械门全绿（`mvn verify`、依赖审计、边界门、字段名守护）只能证明「代码没崩、契约没漂移」，**证明不了语义与参考实现对齐**。最后这一层只能由人做，本表把这件事摊平成可逐行签字的清单。
>
> **怎么用**：从上到下逐行判定，每行都要留痕。
> - ☐ 通过：证据看过了，语义与契约一致。
> - ☐ 不通过：**必须写明违反了契约里的哪一句**（照抄那一句），否则这条不算评审过。
> - ☐ 需讨论：写明争议点与两种口径的代价，留给维护者拍板。
>
> **证据口径**：
> - 【测试】= 可运行的守护测试（`类.方法`），评审时应能跑通该条；
> - 【代码】= 实现落点文件（路径相对仓库根）；
> - 【门】= 机械门（CI 步骤 / 守护测试）；
> - 【语料】= 冻结报文或冻结清单。
>
> **相关文件**：[`INVARIANTS.md`](INVARIANTS.md)（D1 契约）、[`MECHANISMS.md`](MECHANISMS.md)（D2 契约）、[`API.md`](API.md)（D6 接口与文案）、[`INVARIANT-TEST-MAP.md`](INVARIANT-TEST-MAP.md)（无空项门）、[`SELF-INVENTED.md`](SELF-INVENTED.md)（Java 侧自创登记）、[`J6-STATUS.md`](J6-STATUS.md)（实现口径与诚实边界）。
>
> **状态**：未评审（`docs/mos4j-tasks.md` 的 G0 清单里「D1/D2/D6 三份文档定稿并评审通过」仍为未勾）。

---

## 1. 七条不变式（I1–I7）

### I1 fail-closed（缺省拒绝）

| 项 | 内容 |
|---|---|
| 契约 | 任何安全/治理能力未装配时走拒绝或显式缺失路径，绝不静默放行；能力位的缺省值是「关」 |
| 违反后果 | 忘装审批通道 → ask 类调用被静默放行（最高危事故形态）；忘装沙箱 → shell 裸奔在宿主权限上 |
| 【代码】 | `mos4j-execpath/.../ExecPath.java`（preflight 拒绝分支）、`mos4j-hook/.../GuardBus.java`、`mos4j-driver/.../ReActDriver.java`（`ERR_TOOLS_UNSUPPORTED`）、`mos4j-tool/.../builtin/ShellTool.java` |
| 【测试】 | `ExecPathSecurityTest.l3_4_askWithoutChannelIsDenied`、`SecurityFlowTest.missingApprovalChannelFailsClosed`、`GuardBusTest.guardExceptionIsDenied`、`GuardBusTest.nullDecisionIsDenied`、`ShellToolTest.shellToolRequiresARunner`、`DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled` |
| 【门】 | 无独立机械门（本条靠上述测试 + `Describe` 快照） |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I2 日志即事实（append-only）

| 项 | 内容 |
|---|---|
| 契约 | 事件日志是唯一事实记录，只追加、不改写、不删除；崩溃留下的「洞」由投影自愈，日志层不修复 |
| 违反后果 | 「当时发生了什么」无从追查；审计失去凭据；投影自愈与审计的边界被打破 |
| 【代码】 | `mos4j-session/.../Session.java`（`append` 唯一写入口，`seq` 统一分配）、`mos4j-session/.../JsonlPersistence.java`（只 append）、`mos4j-session/.../Projection.java`（自愈在投影层） |
| 【测试】 | `L2ScenariosTest.logIsNotRewritable`、`SessionTest.eventsReturnsDefensiveCopy`、`ProjectionTest`（L2-5/6/7/8）、`JsonlTruncationFuzzTest`（尾部半行可丢） |
| 【门】 | 【语料】`fieldnames.json` 冻结清单 + `FieldNameContractTest` |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I3 审计不变式（错误只可加重不可减轻）

| 项 | 内容 |
|---|---|
| 契约 | 后置钩子可把成功改成失败，不能把失败洗成成功；错误一旦发生就是事实 |
| 违反后果 | 模型以为操作成功 → 基于错误状态继续决策；审计看到的是被美化过的历史 |
| 【代码】 | `mos4j-execpath/.../ExecPath.java`（finalize 单向判定）、`mos4j-hook/.../PostHook.java`（接口层面没有「清除错误」的能力） |
| 【测试】 | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess`、`ExecPathSecurityTest.l3_8_postHookCanWorsenASuccess` |
| 【门】 | — |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I4 上下文是稀缺资源

| 项 | 内容 |
|---|---|
| 契约 | 上下文按预算管理；压缩按预算触发；治理性元数据走预算制；投影给模型的历史永远 ≤ 预算 |
| 违反后果 | 长会话撑爆窗口 → 服务商拒收或费用失控；治理事件挤占业务上下文 |
| 【代码】 | `mos4j-context/.../CompactionService.java`、`CompactionPolicy.java`、`CutPointFinder.java`、`mos4j-session/.../Session.java`（`contextTokens()` 两段式估算） |
| 【测试】 | `CompactionBudgetTest`（7 条）、`CompactionCutPointTest`（3 条）、`L2ScenariosTest.approvalEventsAreAuditOnly`、`SessionApprovalAuditTest.approvalEventsDoNotEnterTheProjection`、`ContextFlowTest.compactionRunsBeforeTheModelCall`、`ContextFlowTest.compactionFailureClosesTheTurn` |
| 【门】 | — |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I5 Hook / Guard 双轨制

| 项 | 内容 |
|---|---|
| 契约 | 守卫是安全边界、钩子是扩展点，结构上不可互替；**钩子无法绕过守卫**（类型层面的结构性保证，不靠纪律） |
| 违反后果 | 钩子能改载荷 → 守卫判定失效；钩子能绕守卫 → 「安全策略」沦为可协商的建议 |
| 【代码】 | `mos4j-hook/.../GuardBus.java` / `HookBus.java`（分型）、`mos4j-execpath/.../ExecPath.java`（顺序：guard → hook 链 → 冻结检查 → 审批 → 执行 → post hook） |
| 【测试】 | `DualTrackStructureTest.guardAndHookEventsAreDistinctTypes`、`ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks`、`ExecPathSecurityTest.l3_9_guardRunsExactlyOncePerCall` |
| 【门】 | — |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I6 零开销降级

| 项 | 内容 |
|---|---|
| 契约 | 可选能力未启用时零额外开销：不多一次拷贝、不多一层包装、不因「可能有」而常驻对象 |
| 违反后果 | 所有用户为少数人用的能力付常数开销；热路径被无谓分配拖慢 |
| 【代码】 | `mos4j-context/.../ToolOutputLimiter.java`（未装配不截断）、`mos4j-session/.../Session.java`、`mos4j-tool/.../ToolRegistry.java`（schema 注册期缓存） |
| 【测试】 | `ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled`、`ExecPathSecurityTest.emptyBusesBehaveLikeNotInstalled`、`DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled`、`ContextFlowTest.withoutLimiterTheOutputIsUntouched`、`ExecPathTest.registryListIsZeroReflectionAndStableOrder`（工具清单零反射且顺序稳定） |
| 【门】 | — |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I7 依赖纪律

| 项 | 内容 |
|---|---|
| 契约 | SDK 模块运行时依赖只有 Jackson 家族（databind/core/annotations/dataformat-yaml）/ RE2J / SnakeYAML 系（仅由 YAML 模块传递引入）/ JDK；Spring 只允许出现在按需的 starter |
| 违反后果 | 非 Spring 宿主被迫拖入 Spring；依赖树膨胀到无法审计 |
| 【代码】 | 父 `pom.xml`（enforcer `bannedDependencies`）、`mos4j-sdk/pom.xml`（门面聚合依赖的显式豁免 + 理由） |
| 【测试】 | `BoundaryRulesTest`（7 条：反向依赖 / pom 依赖方向 / 协议层不依赖日志层 / 共享值类型唯一定义 / 投影类身份 / 词汇表只在 core） |
| 【门】 | 【门】CI C2a（enforcer）+ C2b（`dependency:analyze`，12 模块）+ C3（边界）+ C6（源码扫描 `java.util.regex` = 0、Spring = 0） |
| **判定** | ☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

---

## 2. 十条机制（M1–M10）

| 机制 | 契约要点 | Java 落点 | 证据（类.方法） | 判定 |
|---|---|---|---|---|
| M1 载荷冻结 | 钩子不得改写工具名/参数/CallID；改写即违约拒绝执行 | `mos4j-execpath/.../ExecPath.java`（preflight + 执行前二次校验）；`ToolCall.args` 保持原始 JSON 文本 | `ExecPathSecurityTest.l3_3_rewritingNameIsFrozenViolation` / `l3_3_rewritingArgsIsFrozenViolation` / `l3_3_rewritingCallIdIsFrozenViolation` / `l3_3_frozenToolIsNotExecuted` | ☐通过 ☐不通过 ☐需讨论 |
| M2 回填序=调用序 | 结果与入参严格同序；并发只发生在 dispatch 阶段 | `mos4j-execpath/.../ExecPath.java`（按调用索引的槽位数组） | `ExecPathTest.resultsFollowCallOrderNotCompletionOrder` | ☐通过 ☐不通过 ☐需讨论 |
| M3 错误只可加重 | post hook 可把成功改失败，不能把失败洗成成功 | `mos4j-execpath/.../ExecPath.java`（finalize 单向 if）+ `mos4j-hook/.../PostHook.java`（接口无「清除错误」能力） | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` / `l3_8_postHookCanWorsenASuccess` | ☐通过 ☐不通过 ☐需讨论 |
| M4 投影自愈 | 孤儿 tool/call 由投影补合成中断回执；日志不改写 | `mos4j-session/.../Projection.java`（`INTERRUPTED_RECEIPT_TEMPLATE`） | `ProjectionTest`（L2-5/6/7/8） | ☐通过 ☐不通过 ☐需讨论 |
| M5 流完整性 | 结束标记**或**结束原因任一到即完整；两者皆无才截断；结束原因为空 ≠ 截断 | `mos4j-llm/.../protocol/{openai,anthropic,gemini}` 各适配器 | 【语料】`llm/{openai,anthropic,gemini}/*.sse`；`L1OpenAiScenariosTest`（O5a/O5b）、`L1AnthropicScenariosTest`（a4/a5/a6）、`L1GeminiScenariosTest`（g5a/g5b） | ☐通过 ☐不通过 ☐需讨论 |
| M6 用量口径 | `freshInputTokens` 跨协议同口径；缓存读/写单列；`cacheHitRatio` 分母用去缓存口径 | `mos4j-llm/.../model/Usage.java`（`freshInput()`）+ 各协议适配器 | `UsageJsonTest` + `L1OpenAiScenariosTest`（O4）+ `L1AnthropicScenariosTest`（a7/a8/a9/a10）+ `L1GeminiScenariosTest`（g1/g_nonStream） | ☐通过 ☐不通过 ☐需讨论 |
| M7 写前观察 | 没见过就写→拒；见过但已改/已删→拒；新建免读；edit 必读 | `mos4j-fs/.../ObserveGate.java`（只给原因码+令牌）+ `mos4j-tool/.../FileOps.java`（文案） | `ObserveGateTest`（11 条）+ `FileToolsTest.l3_15_writeExistingFileWithoutReadIsDenied` / `l3_15_writeAfterReadIsAllowed` / `l3_15_writeAfterReadButChangedIsDenied` / `l3_16_editWithoutReadIsDenied` / `l3_17_newFileDoesNotRequireRead` | ☐通过 ☐不通过 ☐需讨论 |
| M8 压缩遮蔽与切割点 | 切割点永不落在 tool 结果上；摘要以 `[历史摘要]` 注入；摘要失败不遮蔽 | `mos4j-context/.../{CompactionService,CompactionPolicy,CutPointFinder,Transcript}.java` | `CompactionBudgetTest`（7）+ `CompactionCutPointTest`（3）+ `PreviousSummaryTest`（3）+ `ContextFlowTest.compactionRunsBeforeTheModelCall` / `compactionFailureClosesTheTurn` | ☐通过 ☐不通过 ☐需讨论 |
| M9 路径双校验 | Lexical + Realpath 两层；**读也走 Realpath**；符号链接深度上限 32 | `mos4j-fs/.../SandboxedFs.java`（逐段解析）+ `RootSet.java` | `SandboxedFsTest`（18 条）+ `RootSetTest`（8 条） | ☐通过 ☐不通过 ☐需讨论 |
| M10 退出码 + 不许绕 | 非零退出码是正常结果（附 `[exit code: N]`）；只有启动失败/超时才是错误；拒绝文案含「不许绕」 | `mos4j-sandbox/.../LocalRunner.java` + `net.hegq.mos4j.core.Texts`（文案唯一出处） | `ShellToolTest.l3_18_nonZeroExitIsNotAnError` / `l3_18_successExitIsAlsoMarked` / `l3_18_timeoutIsAnError` + `LocalRunnerTest.nonZeroExitCodeIsAResultNotAnError` + `ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence` | ☐通过 ☐不通过 ☐需讨论 |

---

## 3. 三协议口径对照（M5 / M6 的协议差异，逐协议判定）

> 这是最容易「以为一样其实不一样」的地方：同一个 `Usage` / 同一个流结束判定，三个协议的口径不同。

| 项 | OpenAI | Anthropic | Gemini | 判定 |
|---|---|---|---|---|
| 流结束标记 | `[DONE]` | `message_stop` | `finishReason`（更严） | ☐一致 ☐有异议 |
| 结束原因 | `finish_reason` | `stop_reason`（`message_delta`） | `finishReason` | ☐一致 ☐有异议 |
| 截断判定 | 两者皆无 → `StreamTruncatedException` | 两者皆无 → 同 | 读完仍无 `finishReason` 且 `require_finish=true` → 同；开关可关（逃生阀） | ☐一致 ☐有异议 |
| 空结束原因 | 合法（不等于截断） | 合法（A5 语料） | — | ☐一致 ☐有异议 |
| 输入 token 口径 | `prompt_tokens` **含**缓存命中 | `input_tokens` **不含**缓存 | `promptTokenCount` **含**缓存命中 | ☐一致 ☐有异议 |
| `freshInput` | `prompt − cached` | `= input` | `prompt − cached` | ☐一致 ☐有异议 |
| 总计口径 | 输入 + 输出 | 输入 + 缓存写 + 缓存读 + 输出 | 输入 + 输出（含缓存） | ☐一致 ☐有异议 |
| 鉴权 | `Authorization: Bearer` | `x-api-key` + `anthropic-version: 2023-06-01` | `x-goog-api-key`（密钥不进 URL） | ☐一致 ☐有异议 |
| 工具调用形状 | `tool_calls`（args 增量拼接） | `tool_use` 块（身份字段是 `id`） | `functionCall`（args 是完整对象；同 id 跨 chunk 合并） | ☐一致 ☐有异议 |
| 工具回执形状 | `role=tool` 消息 | `tool_result` 块 | `functionResponse` | ☐一致 ☐有异议 |
| 证据 | `L1OpenAiScenariosTest`(5) | `L1AnthropicScenariosTest`(12) | `L1GeminiScenariosTest`(6) | 【语料】`llm/*/*.sse` |

---

## 4. Java 侧自创机制（`SELF-INVENTED.md` SI-1 … SI-29）

> Go 侧没有对应物、Java 侧自创的机制**必须逐条评审**（PR 模板要求登记）。评审重点：**代价是否被诚实声明**。

| 编号 | 机制 | 核心代价 | 判定 |
|---|---|---|---|
| SI-1 | `CancellationToken` 取消传播 | 协作式：每个可阻塞点必须显式响应，无编译期保证（漏检不报错） | ☐接受 ☐需改 ☐需讨论 |
| SI-2 | 进程组击杀降级 | 尽力而为、非原子（不等价于 Go） | ☐接受 ☐需改 ☐需讨论 |
| SI-3 | 版本令牌降级 | `dev:ino:size:mtimeNs`，不含 ctime → 只改元数据的改动检测不到 | ☐接受 ☐需改 ☐需讨论 |
| SI-4 | 注解+反射 Schema | 反射失败即注册失败（fail-closed），不静默空 schema | ☐接受 ☐需改 ☐需讨论 |
| SI-5 | 审批审计接口下沉 core | 多一个共享词汇表文件（core 只放接口与值对象） | ☐接受 ☐需改 ☐需讨论 |
| SI-6 | 安全编排顺序写死 | 审批排在冻结检查之后（不为必然被拒的调用打扰人） | ☐接受 ☐需改 ☐需讨论 |
| SI-7 | 审批四档中的 `unavailable` | 多一档结局（与人工拒绝对模型说的话不同） | ☐接受 ☐需改 ☐需讨论 |
| SI-8 | `RootSet` 活根集合与挂载名语义 | 路径解析多一层「首段匹配」 | ☐接受 ☐需改 ☐需讨论 |
| SI-9 | 逐段符号链接解析 | 代码量比一次求值大 | ☐接受 ☐需改 ☐需讨论 |
| SI-10 | 观察门只给事实、文案在工具层 | 多一次映射 | ☐接受 ☐需改 ☐需讨论 |
| SI-11 | `PathScope` 只能收窄 + 三平台后端（J8） | 非 macOS/Linux 平台直接不支持（`UnsupportedOperationException`）；**缺省装配**下宿主不允许应用时 shell 命令直接失败 | ☐接受 ☐需改 ☐需讨论 |
| SI-12 | 文本工具约定（唯一命中 / 行尾归一 / BOM 保留 / 拒二进制） | 模型想批量改多处得多调几次 | ☐接受 ☐需改 ☐需讨论 |
| SI-13 | 输出限流与截断标记口径 | 截断改内容（不超限时逐字原样返回） | ☐接受 ☐需改 ☐需讨论 |
| SI-14 | 截断落盘逃生通道 | 多一个目录要宿主准备 | ☐接受 ☐需改 ☐需讨论 |
| SI-15 | 两段式 token 估算 + 「不配置就不压」 | 估算是启发式，不是分词 | ☐接受 ☐需改 ☐需讨论 |
| SI-16 | 摘要输入的冻结格式与旧摘要折叠 | 格式一旦冻结就难改 | ☐接受 ☐需改 ☐需讨论 |
| SI-17 | 审计门白名单是封闭集合 | 写文件/跑命令的结果要进状态得宿主显式授权 | ☐接受 ☐需改 ☐需讨论 |
| SI-18 | 上下文文件发现规则 | 父目录与真实路径要宿主提供 | ☐接受 ☐需改 ☐需讨论 |
| SI-19 | glob 语义按常见 glob 实现（RE2J） | 不支持 POSIX 字符类（`[:alpha:]`） | ☐接受 ☐需改 ☐需讨论 |
| SI-20 | grant_access 三态与「模型只有申请能力」 | 模型无法自行扩大访问范围（设计不是缺陷） | ☐接受 ☐需改 ☐需讨论 |
| SI-21 | 工具面延后加载（BM25 + deferred） | 多一轮交互才能用到工具 | ☐接受 ☐需改 ☐需讨论 |
| SI-22 | SSRF 防线的钉住与传输边界 | 内网资源永远抓不到（设计不是缺陷） | ☐接受 ☐需改 ☐需讨论 |
| SI-23 | 重试装饰器 | 装配后调用次数不再是「一次输入一次调用」 | ☐接受 ☐需改 ☐需讨论 |
| SI-24 | ExecPath 取消语义（协作式） | 取消是信号不是强杀，长任务停止依赖工具自身响应 | ☐接受 ☐需改 ☐需讨论 |
| SI-25 | SDK 门面与 CLI | 门面的缺省面（缺省工具）是宿主能力的决定 | ☐接受 ☐需改 ☐需讨论 |
| SI-26 | 工具面与 Go 的形状差异 | `grant_access` / deferred 两步与 Go 不可直接回放（有意差异 B13-4 / B13-5） | ☐接受 ☐需改 ☐需讨论 |
| SI-27 | 技能审计接口下沉 core + 工具侧审计槽（J7） | 多一个 core 接口；工具靠「换槽」跳转，不知道会话是什么 | ☐接受 ☐需改 ☐需讨论 |
| SI-28 | 提示词字符串层插入（`PromptSections.insert`） | 靠标题文本识别插点（宿主自定义同名标题会干扰） | ☐接受 ☐需改 ☐需讨论 |
| SI-29 | 技能目录重扫口径 + 相对路径锚 workspace（J7） | 宿主必须自己决定何时重扫、自己把 notice 补给上下文 | ☐接受 ☐需改 ☐需讨论 |
| SI-30 | Linux Landlock 用「重新执行自己的 java 启动器」+ argv 载荷（J8） | 每条命令多起一个 JVM（启动开销）；写根极多会撞 `ARG_MAX`；拿不到 `java.class.path` 时 fail-closed | ☐接受 ☐需改 ☐需讨论 |

---

## 5. 诚实边界（必须逐条判定「接受 / 需补 / 需人做」）

> 这些是**已知的没做的事**。不写进评审表就会变成「没人知道」的缺口。

| # | 边界 | 影响面 | 判定 |
|---|---|---|---|
| B1 | ~~**Go 参考仓库只读访问未获批**~~ → **已获批**（2026-11-06，`grant_access(read)` 成功），已完成首轮逐项对拍；**发现三处工具名与四处参数形状不一致**（详见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3） | 工具面与模型可见契约是否应对齐 Go | ☑已裁决（B13-1/2/3 实施，B13-4/5 记差异） ☐需人做 |
| B2 | ~~**T2.11 Go 侧原测试名仅登记 1 条**~~ → **已补齐**（137 个真实 Go 测试名逐条登记，机器复核全部存在） | 追溯矩阵完整度 | ☐接受 ☐需补 ☐需人做 |
| B3 | **macOS Seatbelt 无法实际应用**（`sandbox_apply: Operation not permitted`，退出码 71）→ 1 条用例跳过；**J8 后已缺省装配**：本机经 SDK 跑 shell 会直接失败（fail-closed 生效，探针实测；见 `J8-STATUS.md` §5） | 沙箱路径强制的真机效果未验证 | ☐接受 ☐需补 ☐需人做（换环境） |
| B4 | **SSRF 防线二/四（地址钉住、体积超时）是传输层实现**，真机 rebinding 对拍不可行 | 联网工具的传输层边界 | ☐接受 ☐需补 ☐需人做（换环境） |
| B5 | **审批阻塞迁虚拟线程未单独立项验证**（审批在 preflight 串行段，回调线程属宿主域） | 取消与审批的线程模型 | ☐接受 ☐需补 ☐需人做 |
| B6 | **CLI 是单轮形态**（无多轮 REPL / tty 审批 / 流式打点） | 交付形态的完整度 | ☐接受 ☐需补 ☐需人做（列入按需清单） |
| B7 | **T6.8 的基准是最小断言级**（无 JMH 微基准） | 性能结论的强度 | ☐接受 ☐需补 ☐需人做（引 JMH 会破依赖纪律） |
| B8 | **投影自愈 fuzz 未单独立项**（与 JSONL 截断 fuzz 高度重叠） | 随机化覆盖度 | ☐接受 ☐需补 ☐需人做 |
| B9 | **沙箱系统临时目录不可写** → 文件测试用模块内 `target/test-tmp`，JUnit `@TempDir` 不可用 | 测试环境约定（已在测试里遵守） | ☐接受 ☐需补 ☐需人做（换环境） |
| B10 | **工作区非 Git 仓库**；**Python 无 PyYAML**（CI YAML 仅人工检查） | 变更追溯与 CI 配置校验 | ☐接受 ☐需补 ☐需人做 |
| B11 | ~~**Spring Boot Starter 尚未实现**~~ → **已实现**（2026-11-06）：按需模块 `mos4j-spring-boot-starter`（不进默认 reactor，`-Pstarter` 构建），14 条测试全绿（含端到端真跑一轮） | 交付形态的可选外壳 | ☐接受 ☐需补 ☐需人做 |
| B12 | **G1–G6 人工语义评审未完成**（即本表） | 合入门 | ☐接受 ☐需补 ☐需人做 |
| B13 | ~~**工具名与参数形状与 Go 不一致**~~ → **已按裁决落地**（2026-11-06）：**B13-1** 文件工具模型可见名改为 `read`/`write`/`edit`；**B13-2** `grep.glob`、`glob.limit`（1000/10000）、`ls.limit`（500/5000）、`shell.description`、`tool_search` 参数名 `topK`→`limit`；**B13-3** `edit` 改 `edits[]`（数组/单对象/JSON 字符串三种形状、按原文定位、重叠报错、原子落盘）。**B13-4** `grant_access` 保留 `mount`+`dir`+`reason`、**B13-5** 保留 `tool_search`+`activate` 两步 = **有意差异**。清单由 `ToolContractTest` 机器守护 | 跨语言会话日志能否直接回放；模型可见契约 | ☑已裁决并落地 ☐需人做 |
| B14 | ~~**Go 的「技能（skill）」机制 Java 完全没有**~~ → **J7 已补齐**（2026-11-07）：`mos4j-tool/tool/skill` 全套（目录发现 / `SKILL.md` frontmatter / 清单字节预算多级降级 / 正文调用时现读 / 重扫原子快照 / `/name` 手势）+ `skill` 工具**条件注册**（13 → 14）+ 两个事件（`skill/activate`、`skill/allowed-tools-mismatch`）+ `SkillAudit`；**不新增模块**；字段清单升为 80/71 | 能力缺口已封；与 Go 的有意差异：不新增模块、`allowed-tools` 不生效 | ☑已落地 ☐需人做（G1–G6 评审） |
| B15 | ~~**Go 的 `http` 工具 Java 没有**~~ → **已撤销**（2026-11-06 当日更正）：`http` 是 `HTTPFetcher` 的实现标识，不是模型可见工具；`web.Tools()` 只产出 `web_search` + `web_fetch` | 无缺口 | ☑无需处理（事实更正） |
| B16 | **J8：Linux Landlock 后端未在 Linux 上跑过**（本机 Darwin；`LandlockScopeTest` 5 条在非 Linux 上跳过） + **Windows 后端完全没做**（且 Windows 上 shell 工具不可用：`/bin/sh` 与 `ulimit` 都是 Unix 假设） | 三平台能力矩阵中 Linux/Windows 两行不可验收 | ☐接受（登记为缺口） ☐需补（需 Linux/Windows 真机或 CI runner） ☐需人做 |

---

## 6. 里程碑门（G1–G6）判定

| 门 | 机械结论 | 人工结论 | 判定 |
|---|---|---|---|
| G0 契约冻结 | D1/D2/D6 三份文档已定稿；字段名清单冻结并有守护测试 | 文档是否与参考实现语义一致 | ☐通过 ☐不通过 ☐需讨论 |
| G1 最小垂直切片 | OpenAI L1 5/5 + L2 16/16 + 一个 turn 端到端 | turn 语义是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |
| G2 安全纵深 | L3 D1/D2/D3 全绿（16 条） | 拒绝/冻结/审批语义是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |
| G3 文件与沙箱 | D4 8/8；jail 双校验 + 观察门 + 沙箱 | 边界判定是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |
| G4 上下文工程 | 压缩遮蔽 / 切割点合法 / 逐字节一致 | 预算与切割语义是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |
| G5 工具全集与联网 | 十个内置工具行为一致；grep 用 RE2J；SSRF 判定层绕过测试齐 | 工具语义与 glob/grep 口径是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |
| G6 三协议 + 硬化 | L1 三协议全绿；取消/超时无泄漏；依赖审计 12 模块 | 协议口径（结束标记 / usage）是否与 Go 一致 | ☐通过 ☐不通过 ☐需讨论 |

---

## 7. 签署

| 项 | 内容 |
|---|---|
| 评审人 | ____________________ |
| 日期 | ____________________ |
| 结论 | ☐ 全部通过　☐ 有不通过项（列出：____________）　☐ 需维护者拍板（列出：____________） |
| 遗留处理 | ☐ 接受现有边界　☐ 列入下一里程碑　☐ 需授权（Go 仓库只读 / 其他） |
| 备注 | ________________________________________________ |

---

## 附：评审前建议跑一遍的命令

```bash
# 全量构建 + 全部测试（默认 reactor 基线 537 通过 / 6 跳过；含按需 starter 为 554/6）
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify

# 依赖纪律（12 模块）
./mvnw -B -am -pl mos4j-core,mos4j-llm,mos4j-fs,mos4j-sandbox,mos4j-tool,mos4j-session,\
mos4j-hook,mos4j-execpath,mos4j-context,mos4j-driver,mos4j-policy,mos4j-sdk \
dependency:analyze -Dmdep.analyze.failOnWarning=true

# 边界门（负向验证：临时违规 → 应变红）
./mvnw -B -pl mos4j-sdk -am test -Dtest=BoundaryRulesTest -Dsurefire.failIfNoSpecifiedTests=false

# 源码扫描（期望均为 0）
grep -rn "import java.util.regex" --include="*.java" mos4j-*/src/main | wc -l
grep -rn "import org.springframework" --include="*.java" mos4j-*/src/main | wc -l
```
