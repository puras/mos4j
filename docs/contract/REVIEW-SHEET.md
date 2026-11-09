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
> **相关文件**：[`INVARIANTS.md`](INVARIANTS.md)（D1 契约）、[`MECHANISMS.md`](MECHANISMS.md)（D2 契约）、[`API.md`](API.md)（D6 接口与文案）、[`INVARIANT-TEST-MAP.md`](INVARIANT-TEST-MAP.md)（无空项门）、[`SELF-INVENTED.md`](SELF-INVENTED.md)（Java 侧自创登记）、[`J6-STATUS.md`](J6-STATUS.md)（实现口径与诚实边界）、[`J7-STATUS.md`](J7-STATUS.md)、[`J8-STATUS.md`](J8-STATUS.md)。
>
> **状态**：**预填已完成；Q1–Q8 已由用户全部接受建议并实施完毕（2026-11-07）；Go↔Java 13 项差异已全部补齐（2026-11-08）；签字栏仍待人工逐行确认**
> （`docs/mos4j-tasks.md` 的 G0 清单里「D1/D2/D6 三份文档定稿并评审通过」仍为未勾——**机器预填与用户裁决都不等于逐行签字**，最后这一步只能由人做）。

## 签字指引（怎么签这份表）

- 本表共 **112 条**判定项（I 7 + M 10 + R 10 + SI 52 + B 26 + G 7）。其中 **4 条已由用户裁决并落地**（B1 / B13 / B14 / B15，标 `☑`），**108 条待逐行签字**。
- **每条末尾都有勾选框，勾一个就行**（不勾 = 未评审）：

  | 节 | 条目 | 勾选框含义 |
  |---|---|---|
  | §1 不变式 | I1–I7 | ☐ 通过　☐ 不通过　☐ 需讨论 |
  | §2 机制 | M1–M10 | ☐ 通过　☐ 不通过　☐ 需讨论 |
  | §3 三协议 | R1–R10 | ☐ 一致　☐ 有异议 |
  | §4 自创机制 | SI-1…SI-51 | ☐ 接受　☐ 需改　☐ 需讨论 |
  | §5 诚实边界 | B1–B25 | ☐ 接受　☐ 需补　☐ 需人做 |
  | §6 里程碑门 | G0–G6 | ☐ 通过　☐ 不通过　☐ 需讨论 |

- 判「**不通过**」时**必须照抄被违反的那一句契约**（写在行内的横线上），否则该条不算评审过。
- 每行开头的 `✅ 预填：建议通过` 只是**建议**（依据见 §8），**不构成签字**；签名之前，本表任何一行都不算「已评审」。
- 签完在 **§7** 填评审人 / 日期 / 结论；若有行没勾，**在 §7.1「未评审条目」里列出来**（别留空，留空 = 没说清楚）。
- 评审过程中若引出代码或文档修改，改完跑一遍 §附 的命令确认机械门仍绿，再回来签字。

>
> **预填说明（2026-11-07）**：每条判定栏里 `✅ 预填` 是**机器可核 + 我逐条读过实现**后给出的**建议**，**不等于评审通过**——签字栏（`☐ 通过 / ☐ 不通过 / ☐ 需讨论`）仍需人逐行确认。
> 需要人拍板的问题已单独收敛到 **[`REVIEW-QUESTIONS.md`](REVIEW-QUESTIONS.md)（Q1–Q8）**；预填依据见 §8。
>
> **本轮实施补记（2026-11-07）**：Q1（显式出口）、Q2′（动态写根）、Q4（`requireFinish` + `maxTokens`）、Q5（装配期探测）**已落地并有守护测试**；
> Q3（Linux 默认不装配）、Q8（`allowed-tools` 只诊断）**维持现状**，只补文档与测试口径。
> 实施**未改**契约清单、未改事件名、未改模块边界（当时是 12 模块，现在是 6 模块，见下）；当时的机械基线：默认 **567 / 0 / 7**、含 starter **589 / 0 / 7**。
>
> **Go 差异补齐补记（2026-11-08）**：13 项差异（#1…#13）**生产代码 + 定向用例全部落地**，文档登记完成
> （[`GO-DIFF.md`](GO-DIFF.md) / [`API.md`](API.md) §3.15 / [`SELF-INVENTED.md`](SELF-INVENTED.md) SI-43…SI-51 /
> [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.9 / [`INVARIANT-TEST-MAP.md`](INVARIANT-TEST-MAP.md) F-14…F-23）；
> 机械基线（六模块合并后，2026-11-08 实跑）：默认 **846 / 0 / 7**、含 starter **870 / 0 / 7**、`-Pexamples` **871 / 0 / 7**、`-Pexamples,mcp` **874 / 0 / 7**（示例自身 25 / 28 条）。

---

## 1. 七条不变式（I1–I7）

### I1 fail-closed（缺省拒绝）

| 项 | 内容 |
|---|---|
| 契约 | 任何安全/治理能力未装配时走拒绝或显式缺失路径，绝不静默放行；能力位的缺省值是「关」 |
| 违反后果 | 忘装审批通道 → ask 类调用被静默放行（最高危事故形态）；忘装沙箱 → shell 裸奔在宿主权限上 |
| 【代码】 | `mos4j-agent/.../ExecPath.java`（preflight 拒绝分支）、`mos4j-tool/.../GuardBus.java`、`mos4j-agent/.../ReActDriver.java`（`ERR_TOOLS_UNSUPPORTED`）、`mos4j-tool/.../builtin/ShellTool.java` |
| 【测试】 | `ExecPathSecurityTest.l3_4_askWithoutChannelIsDenied`、`SecurityFlowTest.missingApprovalChannelFailsClosed`、`GuardBusTest.guardExceptionIsDenied`、`GuardBusTest.nullDecisionIsDenied`、`ShellToolTest.shellToolRequiresARunner`、`DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled` |
| 【门】 | 无独立机械门（本条靠上述测试 + `Describe` 快照） |
| **判定** | ✅ 预填：建议通过 —— 6 条守护用例齐（`ExecPathSecurityTest` 25 条 / `SecurityFlowTest` 8 / `GuardBusTest` 7 / `ShellToolTest` 8）；J8 新增一条同族证据：路径强制装不上 → `LocalRunner` 返回 `notStarted("路径强制不可用: …")`，**命令一个字节都不跑**（探针实测）。<br>**Q5 实施后**：探测在**装配期**就报（`Mos4jTest.anUnavailablePathScopeFailsFastAtAssembly`）——不让模型在第 N 轮才说「命令失败了」。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I2 日志即事实（append-only）

| 项 | 内容 |
|---|---|
| 契约 | 事件日志是唯一事实记录，只追加、不改写、不删除；崩溃留下的「洞」由投影自愈，日志层不修复 |
| 违反后果 | 「当时发生了什么」无从追查；审计失去凭据；投影自愈与审计的边界被打破 |
| 【代码】 | `mos4j-session/.../Session.java`（`append` 唯一写入口，`seq` 统一分配）、`mos4j-session/.../JsonlPersistence.java`（只 append）、`mos4j-session/.../Projection.java`（自愈在投影层） |
| 【测试】 | `L2ScenariosTest.logIsNotRewritable`、`SessionTest.eventsReturnsDefensiveCopy`、`ProjectionTest`（L2-5/6/7/8）、`JsonlTruncationFuzzTest`（尾部半行可丢） |
| 【门】 | 【语料】`fieldnames.json` 冻结清单 + `FieldNameContractTest` |
| **判定** | ✅ 预填：建议通过 —— 冻结清单 **80 条 / 71 个唯一名称**（`3d35911d…`）有双层守护；`Session` 的 `append` 是唯一写入口、`events()` 返回防御性拷贝（`SessionTest` 7 条）；尾部半行可丢由 `JsonlTruncationFuzzTest` 覆盖。J7/J8 新增事件（`skill/activate`、`skill/allowed-tools-mismatch`）同样走 `append`，清单已升版复核。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I3 审计不变式（错误只可加重不可减轻）

| 项 | 内容 |
|---|---|
| 契约 | 后置钩子可把成功改成失败，不能把失败洗成成功；错误一旦发生就是事实 |
| 违反后果 | 模型以为操作成功 → 基于错误状态继续决策；审计看到的是被美化过的历史 |
| 【代码】 | `mos4j-agent/.../ExecPath.java`（finalize 单向判定）、`mos4j-tool/.../PostHook.java`（接口层面没有「清除错误」的能力） |
| 【测试】 | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess`、`ExecPathSecurityTest.l3_8_postHookCanWorsenASuccess` |
| 【门】 | — |
| **判定** | ✅ 预填：建议通过 —— 一对**对称**用例（洗白被拒 / 加重被允许），接口层无「清除错误」方法可依赖。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I4 上下文是稀缺资源

| 项 | 内容 |
|---|---|
| 契约 | 上下文按预算管理；压缩按预算触发；治理性元数据走预算制；投影给模型的历史永远 ≤ 预算 |
| 违反后果 | 长会话撑爆窗口 → 服务商拒收或费用失控；治理事件挤占业务上下文 |
| 【代码】 | `mos4j-session/.../CompactionService.java`、`CompactionPolicy.java`、`CutPointFinder.java`、`mos4j-session/.../Session.java`（`contextTokens()` 两段式估算） |
| 【测试】 | `CompactionBudgetTest`（7 条）、`CompactionCutPointTest`（3 条）、`L2ScenariosTest.approvalEventsAreAuditOnly`、`SessionApprovalAuditTest.approvalEventsDoNotEnterTheProjection`、`ContextFlowTest.compactionRunsBeforeTheModelCall`、`ContextFlowTest.compactionFailureClosesTheTurn` |
| 【门】 | — |
| **判定** | ✅ 预填：建议通过 —— 计数与实际一致（`CompactionBudgetTest` @Test=7、`CompactionCutPointTest` @Test=3、`SessionApprovalAuditTest` @Test=5、`ContextFlowTest` @Test=6）；治理事件只进审计不进投影有独立用例。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I5 Hook / Guard 双轨制

| 项 | 内容 |
|---|---|
| 契约 | 守卫是安全边界、钩子是扩展点，结构上不可互替；**钩子无法绕过守卫**（类型层面的结构性保证，不靠纪律） |
| 违反后果 | 钩子能改载荷 → 守卫判定失效；钩子能绕守卫 → 「安全策略」沦为可协商的建议 |
| 【代码】 | `mos4j-tool/.../GuardBus.java` / `HookBus.java`（分型）、`mos4j-agent/.../ExecPath.java`（顺序：guard → hook 链 → 冻结检查 → 审批 → 执行 → post hook） |
| 【测试】 | `DualTrackStructureTest.guardAndHookEventsAreDistinctTypes`、`ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks`、`ExecPathSecurityTest.l3_9_guardRunsExactlyOncePerCall` |
| 【门】 | — |
| **判定** | ✅ 预填：建议通过 —— 「类型不可互替」由结构测试保证，「钩子绕不过守卫」由顺序用例保证（被拒调用**不进 hook 链**）。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I6 零开销降级

| 项 | 内容 |
|---|---|
| 契约 | 可选能力未启用时零额外开销：不多一次拷贝、不多一层包装、不因「可能有」而常驻对象 |
| 违反后果 | 所有用户为少数人用的能力付常数开销；热路径被无谓分配拖慢 |
| 【代码】 | `mos4j-session/.../ToolOutputLimiter.java`（未装配不截断）、`mos4j-session/.../Session.java`、`mos4j-tool/.../ToolRegistry.java`（schema 注册期缓存） |
| 【测试】 | `ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled`、`ExecPathSecurityTest.emptyBusesBehaveLikeNotInstalled`、`DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled`、`ContextFlowTest.withoutLimiterTheOutputIsUntouched`、`ExecPathTest.registryListIsZeroReflectionAndStableOrder`（工具清单零反射且顺序稳定） |
| 【门】 | — |
| **判定** | ✅ 预填：建议通过，**口径已澄清并写入本行**（[`REVIEW-QUESTIONS.md`](REVIEW-QUESTIONS.md) Q6，用户已接受）：J8 起 SDK 缺省装配路径强制，每条 shell 命令多一次 profile 生成 + 一个 `sandbox-exec` 进程——这是**能力启用**的必要开销，不属于「未启用却常驻」，因此不违反 I6；Describe 需说清（`SandboxBoundary.caveats()` 写明路径强制状态与平台后端状态）。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

### I7 依赖纪律

| 项 | 内容 |
|---|---|
| 契约 | SDK 模块运行时依赖只有 Jackson 家族（databind/core/annotations/dataformat-yaml）/ RE2J / SnakeYAML 系（仅由 YAML 模块传递引入）/ JDK；Spring 只允许出现在按需的 starter |
| 违反后果 | 非 Spring 宿主被迫拖入 Spring；依赖树膨胀到无法审计 |
| 【代码】 | 父 `pom.xml`（enforcer `bannedDependencies`）、`mos4j-agent/pom.xml`（门面聚合依赖的显式豁免 + 理由） |
| 【测试】 | `BoundaryRulesTest`（7 条：反向依赖 / pom 依赖方向 / 协议层不依赖日志层 / 共享值类型唯一定义 / 投影类身份 / 词汇表只在 core） |
| 【门】 | 【门】CI C2a（enforcer）+ C2b（`dependency:analyze`，12 模块）+ C3（边界）+ C6（源码扫描 `java.util.regex` = 0、Spring = 0） |
| **判定** | ✅ 预填：建议通过 —— **本轮实跑**：12 模块 `dependency:analyze -Dmdep.analyze.failOnWarning=true` 全 `No dependency problems found`；C6 扫描（`src/main` + `src/test`）spring=0、regex=0；`BoundaryRulesTest` @Test=7。J8 引入的 FFM 是 **JDK 自带**，**零新增外部依赖**。<br>人工：☐ 通过　☐ 不通过（违反的契约句：____________）　☐ 需讨论：____________ |

---

## 2. 十条机制（M1–M10）

| 机制 | 契约要点 | Java 落点 | 证据（类.方法） | 判定 |
|---|---|---|---|---|
| M1 载荷冻结 | 钩子不得改写工具名/参数/CallID；改写即违约拒绝执行 | `mos4j-agent/.../ExecPath.java`（preflight + 执行前二次校验）；`ToolCall.args` 保持原始 JSON 文本 | `ExecPathSecurityTest.l3_3_rewritingNameIsFrozenViolation` / `l3_3_rewritingArgsIsFrozenViolation` / `l3_3_rewritingCallIdIsFrozenViolation` / `l3_3_frozenToolIsNotExecuted` | ✅ 预填：建议通过（4 条对称用例：改名字/改参数/改 CallID 均被拒，且被冻结的工具**未被执行**）<br>☐通过 ☐不通过 ☐需讨论 |
| M2 回填序=调用序 | 结果与入参严格同序；并发只发生在 dispatch 阶段 | `mos4j-agent/.../ExecPath.java`（按调用索引的槽位数组） | `ExecPathTest.resultsFollowCallOrderNotCompletionOrder` | ✅ 预填：建议通过（`ExecPathTest` @Test=15，含并发乱序完成的对照）<br>☐通过 ☐不通过 ☐需讨论 |
| M3 错误只可加重 | post hook 可把成功改失败，不能把失败洗成成功 | `mos4j-agent/.../ExecPath.java`（finalize 单向 if）+ `mos4j-tool/.../PostHook.java`（接口无「清除错误」能力） | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` / `l3_8_postHookCanWorsenASuccess` | ✅ 预填：建议通过（同 I3）<br>☐通过 ☐不通过 ☐需讨论 |
| M4 投影自愈 | 孤儿 tool/call 由投影补合成中断回执；日志不改写 | `mos4j-session/.../Projection.java`（`INTERRUPTED_RECEIPT_TEMPLATE`） | `ProjectionTest`（L2-5/6/7/8） | ✅ 预填：建议通过（`ProjectionTest` @Test=6，自愈在投影层、落回执不写日志）<br>☐通过 ☐不通过 ☐需讨论 |
| M5 流完整性 | 结束标记**或**结束原因任一到即完整；两者皆无才截断；结束原因为空 ≠ 截断 | `mos4j-model/.../protocol/{openai,anthropic,gemini}` 各适配器 | 【语料】`model/{openai,anthropic,gemini}/*.sse`；`L1OpenAiScenariosTest`（O5a/O5b）、`L1AnthropicScenariosTest`（a4/a5/a6）、`L1GeminiScenariosTest`（g5a/g5b） | ✅ 预填：建议通过，**Q4 已实施**（Gemini 的 `require_finish=false` 逃生阀现在门面可达：`Mos4j.Builder.requireFinish(Boolean)`；用在非 Gemini 或宿主自带模型上 **fail-fast**，不静默忽略——`Mos4jProtocolKnobsTest.requireFinishOnANonGeminiProtocolFailsFast` / `requireFinishWithAHostSuppliedModelFailsFast`）<br>☐通过 ☐不通过 ☐需讨论 |
| M6 用量口径 | `freshInputTokens` 跨协议同口径；缓存读/写单列；`cacheHitRatio` 分母用去缓存口径 | `mos4j-model/.../model/Usage.java`（`freshInput()`）+ 各协议适配器 | `UsageJsonTest` + `L1OpenAiScenariosTest`（O4）+ `L1AnthropicScenariosTest`（a7/a8/a9/a10）+ `L1GeminiScenariosTest`（g1/g_nonStream） | ✅ 预填：建议通过（`UsageJsonTest` @Test=4；三协议各有用例，且 `Usage`/`UsageDetails` 用手写序列化器）<br>☐通过 ☐不通过 ☐需讨论 |
| M7 写前观察 | 没见过就写→拒；见过但已改/已删→拒；新建免读；edit 必读 | `mos4j-runtime/.../ObserveGate.java`（只给原因码+令牌）+ `mos4j-tool/.../FileOps.java`（文案） | `ObserveGateTest`（11 条）+ `FileToolsTest.l3_15_writeExistingFileWithoutReadIsDenied` / `l3_15_writeAfterReadIsAllowed` / `l3_15_writeAfterReadButChangedIsDenied` / `l3_16_editWithoutReadIsDenied` / `l3_17_newFileDoesNotRequireRead` | ✅ 预填：建议通过（`ObserveGateTest` @Test=11，与表内计数一致；`FileToolsTest` @Test=30）<br>☐通过 ☐不通过 ☐需讨论 |
| M8 压缩遮蔽与切割点 | 切割点永不落在 tool 结果上；摘要以 `[历史摘要]` 注入；摘要失败不遮蔽 | `mos4j-session/.../{CompactionService,CompactionPolicy,CutPointFinder,Transcript}.java` | `CompactionBudgetTest`（7）+ `CompactionCutPointTest`（3）+ `PreviousSummaryTest`（3）+ `ContextFlowTest.compactionRunsBeforeTheModelCall` / `compactionFailureClosesTheTurn` | ✅ 预填：建议通过（计数一致：7 / 3 / 3）<br>☐通过 ☐不通过 ☐需讨论 |
| M9 路径双校验 | Lexical + Realpath 两层；**读也走 Realpath**；符号链接深度上限 32 | `mos4j-runtime/.../SandboxedFs.java`（逐段解析）+ `RootSet.java` | `SandboxedFsTest`（18 条）+ `RootSetTest`（8 条） | ✅ 预填：建议通过，**附口径澄清**（Q7）：M9 是**文件层**（读也走 Realpath）；J8 的路径强制是**进程层**（只收窄写），两者别混为一谈<br>☐通过 ☐不通过 ☐需讨论 |
| M10 退出码 + 不许绕 | 非零退出码是正常结果（附 `[exit code: N]`）；只有启动失败/超时才是错误；拒绝文案含「不许绕」 | `mos4j-runtime/.../LocalRunner.java` + `net.hegq.mos4j.core.Texts`（文案唯一出处） | `ShellToolTest.l3_18_nonZeroExitIsNotAnError` / `l3_18_successExitIsAlsoMarked` / `l3_18_timeoutIsAnError` + `LocalRunnerTest.nonZeroExitCodeIsAResultNotAnError` + `ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence` | ✅ 预填：建议通过（`ShellToolTest` @Test=8、`LocalRunnerTest` @Test=14；J8 新增：`wrap` 失败 → `notStarted`，属于「没起来」= 错误，与非零退出码的口径不冲突）<br>☐通过 ☐不通过 ☐需讨论 |

---

## 3. 三协议口径对照（M5 / M6 的协议差异，逐协议判定：R1–R10）

> 这是最容易「以为一样其实不一样」的地方：同一个 `Usage` / 同一个流结束判定，三个协议的口径不同。

| 项 | OpenAI | Anthropic | Gemini | 判定 |
|---|---|---|---|---|
| R1 流结束标记 | `[DONE]` | `message_stop` | `finishReason`（更严） | ✅ 一致（`L1OpenAiScenariosTest` 5 / `L1AnthropicScenariosTest` 12 / `L1GeminiScenariosTest` 6 条用例覆盖）<br>☐一致 ☐有异议 |
| R2 结束原因 | `finish_reason` | `stop_reason`（`message_delta`） | `finishReason` | ✅ 一致<br>☐一致 ☐有异议 |
| R3 截断判定 | 两者皆无 → `StreamTruncatedException` | 两者皆无 → 同 | 读完仍无 `finishReason` 且 `require_finish=true` → 同；开关可关（逃生阀） | ✅ 一致（Go 侧 `llm/gemini/gemini.go` 同样有 `require_finish` 逃生阀）；**但逃生阀在 SDK 门面不可达 → Q4**<br>☐一致 ☐有异议 |
| R4 空结束原因 | 合法（不等于截断） | 合法（A5 语料） | — | ✅ 一致<br>☐一致 ☐有异议 |
| R5 输入 token 口径 | `prompt_tokens` **含**缓存命中 | `input_tokens` **不含**缓存 | `promptTokenCount` **含**缓存命中 | ✅ 一致（协议事实，三侧适配器各自换算）<br>☐一致 ☐有异议 |
| R6 `freshInput` | `prompt − cached` | `= input` | `prompt − cached` | ✅ 一致（`Usage.freshInput()` 单点实现）<br>☐一致 ☐有异议 |
| R7 总计口径 | 输入 + 输出 | 输入 + 缓存写 + 缓存读 + 输出 | 输入 + 输出（含缓存） | ✅ 一致<br>☐一致 ☐有异议 |
| R8 鉴权 | `Authorization: Bearer` | `x-api-key` + `anthropic-version: 2023-06-01` | `x-goog-api-key`（密钥不进 URL） | ✅ 一致<br>☐一致 ☐有异议 |
| R9 工具调用形状 | `tool_calls`（args 增量拼接） | `tool_use` 块（身份字段是 `id`） | `functionCall`（args 是完整对象；同 id 跨 chunk 合并） | ✅ 一致<br>☐一致 ☐有异议 |
| R10 工具回执形状 | `role=tool` 消息 | `tool_result` 块 | `functionResponse` | ✅ 一致<br>☐一致 ☐有异议 |
| 证据 | `L1OpenAiScenariosTest`(5) | `L1AnthropicScenariosTest`(12) | `L1GeminiScenariosTest`(6) | 【语料】`model/*/*.sse` |

---

## 4. Java 侧自创机制（`SELF-INVENTED.md` SI-1 … SI-52）

> Go 侧没有对应物、Java 侧自创的机制**必须逐条评审**（PR 模板要求登记）。评审重点：**代价是否被诚实声明**。

| 编号 | 机制 | 核心代价 | 判定 |
|---|---|---|---|
| SI-1 | `CancellationToken` 取消传播 | 协作式：每个可阻塞点必须显式响应，无编译期保证（漏检不报错） | ✅ 建议接受（代价已声明）｜☐接受 ☐需改 ☐需讨论 |
| SI-2 | 进程组击杀降级 | 尽力而为、非原子（不等价于 Go） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-3 | 版本令牌降级 | `dev:ino:size:mtimeNs`，不含 ctime → 只改元数据的改动检测不到 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-4 | 注解+反射 Schema | 反射失败即注册失败（fail-closed），不静默空 schema | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-5 | 审批审计接口下沉 core | 多一个共享词汇表文件（core 只放接口与值对象） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-6 | 安全编排顺序写死 | 审批排在冻结检查之后（不为必然被拒的调用打扰人） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-7 | 审批四档中的 `unavailable` | 多一档结局（与人工拒绝对模型说的话不同） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-8 | `RootSet` 活根集合与挂载名语义 | 路径解析多一层「首段匹配」 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-9 | 逐段符号链接解析 | 代码量比一次求值大 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-10 | 观察门只给事实、文案在工具层 | 多一次映射 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-11 | `PathScope` 只能收窄 + 三平台后端（J8） | **缺省装配只在已验证的平台生效**（当前 = macOS Seatbelt）；Linux/Windows 暂不验证 → 不装配（Describe 的 caveat 会说清原因）；宿主不允许应用沙箱时**装配期就报错**（Q5 fail-fast），或由宿主显式关闭（Q1 `noPathScope()`，Describe 如实声明） | ✅ 建议接受（口径收窄已登记 D-J8-4；**Q3 已裁决：维持收窄**）｜☐接受 ☐需改 ☐需讨论 |
| SI-12 | 文本工具约定（唯一命中 / 行尾归一 / BOM 保留 / 拒二进制） | 模型想批量改多处得多调几次 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-13 | 输出限流与截断标记口径 | 截断改内容（不超限时逐字原样返回） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-14 | 截断落盘逃生通道 | 多一个目录要宿主准备 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-15 | 两段式 token 估算 + 「不配置就不压」 | 估算是启发式，不是分词 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-16 | 摘要输入的冻结格式与旧摘要折叠 | 格式一旦冻结就难改 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-17 | 审计门白名单是封闭集合 | 写文件/跑命令的结果要进状态得宿主显式授权 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-18 | 上下文文件发现规则 | 父目录与真实路径要宿主提供 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-19 | glob 语义按常见 glob 实现（RE2J） | 不支持 POSIX 字符类（`[:alpha:]`） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-20 | grant_access 三态与「模型只有申请能力」 | 模型无法自行扩大访问范围（设计不是缺陷） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-21 | 工具面延后加载（BM25 + deferred） | 多一轮交互才能用到工具 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-22 | SSRF 防线的钉住与传输边界 | 内网资源永远抓不到（设计不是缺陷） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-23 | 重试装饰器 | 装配后调用次数不再是「一次输入一次调用」 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-24 | ExecPath 取消语义（协作式） | 取消是信号不是强杀，长任务停止依赖工具自身响应 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-25 | SDK 门面与 CLI | 门面的缺省面（缺省工具）是宿主能力的决定 | ✅ 建议接受（**Q1 已裁决并实施**：门面有 `pathScope(...)` / `noPathScope()` / `pathScopeProbe(...)` 三个出口，CLI 有 `--no-path-scope`）｜☐接受 ☐需改 ☐需讨论 |
| SI-26 | 工具面与 Go 的形状差异 | `grant_access` / deferred 两步与 Go 不可直接回放（有意差异 B13-4 / B13-5） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-27 | 技能审计接口下沉 core + 工具侧审计槽（J7） | 多一个 core 接口；工具靠「换槽」跳转，不知道会话是什么 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-28 | 提示词字符串层插入（`PromptSections.insert`） | 靠标题文本识别插点（宿主自定义同名标题会干扰） | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-29 | 技能目录重扫口径 + 相对路径锚 workspace（J7） | 宿主必须自己决定何时重扫、自己把 notice 补给上下文 | ✅ 建议接受｜☐接受 ☐需改 ☐需讨论 |
| SI-30 | Linux Landlock 用「重新执行自己的 java 启动器」+ argv 载荷（J8，**暂不验证 → 默认不装配**） | 每条命令多起一个 JVM（启动开销）；写根极多会撞 `ARG_MAX`；拿不到 `java.class.path` 时 fail-closed | ✅ 建议接受（**当前默认不装配**，代价暂不落到生产路径）｜☐接受 ☐需改 ☐需讨论 |
| SI-31 | `Describe` caveat 追加「平台后端状态」（J8） | Go 侧无对应表述；caveat 文案变长（多一句「为什么没装」） | ✅ 建议接受（已登记进 `SELF-INVENTED.md`）｜☐接受 ☐需改 ☐需讨论 |
| SI-32 | 路径强制的**装配期可用性探测**（`PathScopeProbe`，Q5） | 装配期多一次 `sandbox-exec` 进程（毫秒级）；**受限主机的用户会在启动时直接失败**（比运行期失败早，但升级前必须知道） | ✅ 建议接受（**Q5 已裁决并实施**；探针可注入 → 测试与受管主机不必真跑 apply）｜☐接受 ☐需改 ☐需讨论 |
| SI-33 | 路径强制的**显式出口**与「关闭原因」自述（Q1） | 多一个状态字段与几行文案；Go 侧无对应表述（Go 的出口是「不传 runner」，隐式） | ✅ 建议接受（**Q1 已裁决并实施**；Describe 区分静态装 / 动态装 / 未装三态，不谎报）｜☐接受 ☐需改 ☐需讨论 |
| SI-34 | policy 的**单一状态源**（可见性与运行时守卫读同一份名单） | 多一个槽类；换来「两份名单不会漂移」（否则「看得见却调不动」/「看不见却能调」） | ✅ 建议接受（已落地：`PolicySlot` + `Agent.setPolicy` 热替换只换 policy 层，不覆盖 MCP hidden 槽）｜☐接受 ☐需改 ☐需讨论 |
| SI-35 | `DescribeContributor` 槽（工具层往装配层写装配事实；SPI 已下沉 `net.hegq.mos4j.core`，零依赖） | 多一个扩展点；换来「装配层不依赖工具层」 | ✅ 建议接受（已落地：`McpKit.describeContributor()`；贡献者失败转 caveat，不让快照整体不可用）｜☐接受 ☐需改 ☐需讨论 |
| SI-36 | `Agent.close()` 的**容错关闭**语义（幂等 / 逐个关 / 关不掉不拖垮其余） | 关闭失败会被抛出来（而不是静默咽掉） | ✅ 建议接受（已落地：`Config.closer(...)` + `Agent implements AutoCloseable`）｜☐接受 ☐需改 ☐需讨论 |
| SI-37 | MCP 结果投影的**占位诊断按类型分开** + 结构化 opaque | 类型名不再出现在模型可见文本里（只在结构化占位里）；原始 JSON 上限 2048 | ✅ 建议接受（已落地：`McpCallResult.toModelText/opaqueParts/textContent` + `McpToolAdapter.requireJsonObject`；守护：`McpResultProjectionTest` 9 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-38 | CLI 的 `--skills DIR`（可重复）参数面 | CLI 多一个参数面（Go 的 CLI 没有这个开关） | ✅ 建议接受（已落地：`cli/Main.java`；守护：`MainTest.skillsDir_isWiredIntoTheSession`）｜☐接受 ☐需改 ☐需讨论 |
| SI-39 | 压缩「显式关闭」与「已装压缩器」**冲突时 fail-fast**（Go 是显式关闭优先） | 宿主同时写了两处时**启动失败**，而不是默默不压缩 | ✅ 建议接受（已落地：`Mos4j.Builder.disableCompaction(true)` + 已装 `compaction` → 装配期报错；守护：`Mos4jTest.disableCompactionConflictsWithAnInstalledCompactionService`）｜☐接受 ☐需改 ☐需讨论 |
| SI-40 | 摘要超时**拒绝负值**（Go 用负数表示不限） | 宿主传负值 → 报错；不限要显式写 `Duration.ZERO` | ✅ 建议接受（已落地：`CompactionService` 构造器与 `Mos4j.Builder.summaryTimeout` 双重校验；守护：`Mos4jTest.aNegativeSummaryTimeoutIsRejected`）｜☐接受 ☐需改 ☐需讨论 |
| SI-41 | 上下文文件的装配入口形状（Builder 六件套 vs Go 的 `Config.Context`） | 装配参数多一个门面层（目录 / 候选名 / 读取器 / 预算 / 全局目录 / 递归层数） | ✅ 建议接受（已落地：`Mos4j.Builder.contextFiles(...)` 等六个方法；缺省**不递归**、默认全局目录 `~/.mos`；守护：`ContextFilesTest` 16 条 + `Mos4jTest.contextFilesAreWiredIntoTheSystemPrompt` + `Mos4jAutoConfigurationTest.contextFilesDirIsWiredIntoTheSystemPrompt`）｜☐接受 ☐需改 ☐需讨论 |
| SI-42 | 运行期根管理按**挂载名**（`addRoot(id, dir)`）而不是按目录路径（Go 的 `AddRoot(dir)`） | 宿主要自己起挂载名（模型侧路径按首段匹配挂载名） | ✅ 建议接受（已落地：`Agent.addRoot/addReadRoot/addWriteRoot/removeRoot/roots`；读写分离根与 Go 同语义；守护：`RootManagementTest` 3 条 + `RootSetTest` 13 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-43 | 子代理集**装配期固定**（不做运行期热加载）+ 清单预算降级（先丢描述再丢条目） | 子代理是「角色」不是「说明书」——热换角色等于热换执行语义 | ✅ 建议接受（已落地：`SubAgentCatalog.load(...)` 装配期一次 + `render()` 纯函数三级降级 + 如实披露省略数量；守护：`SubAgentCatalogTest` 12 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-44 | 子代理回执**固定 JSON 结构**（`{status, summary, findings, error}`）+ 非 JSON 降级为 `summary` | 要让「子代理失败也是普通工具结果」机械可测，结构必须固定 | ✅ 建议接受（已落地：`SubAgentReceipt.parse(...)` + `SubAgentStatus`（wire 值小写）+ 坏回执转 `status=error`；守护：`SubAgentToolTest` 11 条 + `SubAgentToolsTest` 12 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-45 | 「无可续」用 **checked** `NothingToResumeException`（Go 用哨兵错误） | unchecked 异常容易被漏接（漏接 = 那一轮被静默收尾） | ✅ 建议接受（已落地：`ResumeOptions` + `NothingToResumeException` + `LoopDriver.resume(...)`；无可续时**保持挂起**不写 `turn/end`；守护：`ResumeTest` 9 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-46 | 审批先验**不新增 `reusedFrom` 字段**（复用时签发新 id，旧 id 写进 `approval/asked.reason`） | 事件字段清单已冻结（80 条 / 71 名），加字段等于改冻结基线 | ✅ 建议接受（已落地：`ApprovalPrior` + `SessionApprovalPrior` + `Texts.priorReused(...)`；只有 `APPROVED`/`REJECTED` 可复用；未装通道仍 fail-closed；守护：`SessionApprovalPriorTest` 9 条 + `ApprovalPriorTest` 6 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-47 | 会话派生读是**纯读**（不写日志 / 不改状态 / 不推进序号 / 不新增事件类型） | 派生做成「顺手补一条事件」→ 日志不再是纯事实 | ✅ 建议接受（已落地：`Session.pendingApprovals/approvalOutcome/approvalDecision/interruptedTurn/hangingCalls` + `HangingCall`；空 call id 不算悬空；同 id 答过就不算悬空；守护：`SessionDerivationTest` 8 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-48 | 协议注册表用 `Map<String, String>` 配置形状（Go 的 `map[string]string`） | 改成结构体或 `Map<String,Object>` → 跨语言配置不能直接搬 | ✅ 建议接受（已落地：`ModelRegistry` 只做「名字 → 工厂」映射、生产裸 `ChatModel`、未知名字 fail-closed 并列已注册名；守护：`ModelRegistryTest` 8 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-49 | 提示词构建器**双入口互斥** + 框架在宿主 Builder 的**副本**上装配 | 直接改宿主手里的 Builder → 宿主复用自己那份时被塞进框架分节 | ✅ 建议接受（已落地：`Config.prompt(...)` / `Mos4j.Builder.prompt(...)`；整串与分节互斥、空白整串按未提供；同名段冲突明确报错；老整串路径仍可用；守护：`PromptBuilderTest` 6 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-50 | 缓存键覆盖**完整请求上下文**（schema 键递归排序后哈希） | 只拿消息正文当键 → 工具清单 / 采样参数变了却命中旧缓存 = 返回错答案 | ✅ 建议接受（已落地：`CachingChatModel`；只缓存非流式成功、取消优先于命中、失败不缓存、命中共享同一 `Response` 对象、`ttl<=0` 不过期；与**服务端 prompt cache 不是一回事**；守护：`CachingChatModelTest` 10 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-51 | 示例集**独立模块 + 独立 profile**（不进默认 reactor）+ testkit 替身 | 示例需要真 API Key（CI 跑不了）；放进默认 reactor 会稀释「一次依赖拿到能跑的智能体」的承诺 | ✅ 建议接受（已落地：`examples/` 7 个示例 + `testkit`（仅 test scope）；依赖逐模块显式声明；用例不碰网络；守护：examples 28 条 + `StubModelTest` 5 条）｜☐接受 ☐需改 ☐需讨论 |
| SI-52 | **官方 MCP SDK 的 SLF4J 例外改挂在 `mos4j-tool`**（原属 `mos4j-mcp`）：MCP 与工具族合并后，窄 enforcer 覆盖跟着搬家，但**范围不变**（只放行 `org.slf4j*`；Spring / Guava / ORM / Lombok / Logback 仍禁） | 例外不搬 → MCP 编译不过（`NoClassDefFoundError`）；例外整条关掉 → 依赖纪律名存实亡 | ✅ 建议接受（官方 SDK 在 `mos4j-tool` 里是 **optional**，不传递给下游宿主；父 POM 禁列本身未改；守护：`dependency:analyze` 无告警 + C6 扫描）｜☐接受 ☐需改 ☐需讨论 |

---

## 5. 诚实边界（必须逐条判定「接受 / 需补 / 需人做」）

> 这些是**已知的没做的事**。不写进评审表就会变成「没人知道」的缺口。

| # | 边界 | 影响面 | 判定 |
|---|---|---|---|
| B1 | ~~**Go 参考仓库只读访问未获批**~~ → **已获批**（2026-11-06，`grant_access(read)` 成功），已完成首轮逐项对拍；**发现三处工具名与四处参数形状不一致**（详见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3） | 工具面与模型可见契约是否应对齐 Go | ☑已裁决（B13-1/2/3 实施，B13-4/5 记差异） ☐需人做 |
| B2 | ~~**T2.11 Go 侧原测试名仅登记 1 条**~~ → **已补齐**（137 个真实 Go 测试名逐条登记，机器复核全部存在） | 追溯矩阵完整度 | ✅ 建议接受｜☐接受 ☐需补 ☐需人做 |
| B3 | **macOS Seatbelt 的强制效果未验证**（本机 `sandbox_apply: Operation not permitted`，退出码 71）→ 1 条用例跳过。**已验证的部分**：profile 已被内核的解析器接受（真机用例：非 65 退出 + 反例 exit 65 证明判据有效）。**J8 后 macOS 已缺省装配**：本机经 SDK 跑 shell 会直接失败（fail-closed 生效，探针实测；见 `J8-STATUS.md` §5）。**验收包已备好**：[`tools/seatbelt-acceptance/README.md`](../../tools/seatbelt-acceptance/README.md)（A 组自动化用例 + B 组真机探针脚本 + 结果回填清单） | 沙箱路径强制的真机效果未验证 | ⚠️ **建议「需人做（换环境）」**：发布前必须在一台允许 `sandbox-exec` 的 macOS 上跑一次（那条跳过用例会自己变通过；命令见验收包）｜☐接受 ☐需补 ☐需人做（换环境） |
| B4 | **SSRF 防线二/四（地址钉住、体积超时）是传输层实现**，真机 rebinding 对拍不可行 | 联网工具的传输层边界 | ✅ 建议接受（设计如此，非缺陷）｜☐接受 ☐需补 ☐需人做（换环境） |
| B5 | **审批阻塞迁虚拟线程未单独立项验证**（审批在 preflight 串行段，回调线程属宿主域） | 取消与审批的线程模型 | ⚠️ 建议「需人做」：G2 评审时**看一次**审批回调的线程归属（影响取消语义）｜☐接受 ☐需补 ☐需人做 |
| B6 | **CLI 是单轮形态**（无多轮 REPL / tty 审批 / 流式打点） | 交付形态的完整度 | ✅ 建议接受（范围决定）｜☐接受 ☐需补 ☐需人做（列入按需清单） |
| B7 | **T6.8 的基准是最小断言级**（无 JMH 微基准） | 性能结论的强度 | ✅ 建议接受（引 JMH 会破依赖纪律）｜☐接受 ☐需补 ☐需人做 |
| B8 | **投影自愈 fuzz 未单独立项**（与 JSONL 截断 fuzz 高度重叠） | 随机化覆盖度 | ✅ 建议接受｜☐接受 ☐需补 ☐需人做 |
| B9 | **沙箱系统临时目录不可写** → 文件测试用模块内 `target/test-tmp`，JUnit `@TempDir` 不可用 | 测试环境约定（已在测试里遵守） | ✅ 建议接受｜☐接受 ☐需补 ☐需人做（换环境） |
| B10 | ~~**工作区非 Git 仓库**~~ → **已解决**：工作区已初始化为 Git 仓库（有初次提交），本轮改动可用 `git diff` 汇总（30 改 + 7 新增）；**Python 无 PyYAML** 仍在 → CI YAML 只能人工看 | 变更追溯与 CI 配置校验 | ✅ 建议接受（追溯已通；CI YAML 人工看）｜☐接受 ☐需补 ☐需人做 |
| B11 | ~~**Spring Boot Starter 尚未实现**~~ → **已实现**（2026-11-06）：按需模块 `mos4j-spring-boot-starter`（不进默认 reactor，`-Pstarter` 构建），含端到端真跑一轮 | 交付形态的可选外壳 | ✅ 建议接受（当前 **22** 条测试：16 + 1 + 5）｜☐接受 ☐需补 ☐需人做 |
| B12 | **G1–G6 人工语义评审未完成**（即本表） | 合入门 | ⚠️ **本表已预填，待签字**；签字完成前这一条保持「需人做」｜☐接受 ☐需补 ☐需人做 |
| B13 | ~~**工具名与参数形状与 Go 不一致**~~ → **已按裁决落地**（2026-11-06）：**B13-1** 文件工具模型可见名改为 `read`/`write`/`edit`；**B13-2** `grep.glob`、`glob.limit`（1000/10000）、`ls.limit`（500/5000）、`shell.description`、`tool_search` 参数名 `topK`→`limit`；**B13-3** `edit` 改 `edits[]`（数组/单对象/JSON 字符串三种形状、按原文定位、重叠报错、原子落盘）。**B13-4** `grant_access` 保留 `mount`+`dir`+`reason`、**B13-5** 保留 `tool_search`+`activate` 两步 = **有意差异**。清单由 `ToolContractTest` 机器守护 | 跨语言会话日志能否直接回放；模型可见契约 | ☑已裁决并落地 ☐需人做 |
| B14 | ~~**Go 的「技能（skill）」机制 Java 完全没有**~~ → **J7 已补齐**（2026-11-07）：`mos4j-tool/tool/skill` 全套（目录发现 / `SKILL.md` frontmatter / 清单字节预算多级降级 / 正文调用时现读 / 重扫原子快照 / `/name` 手势）+ `skill` 工具**条件注册**（13 → 14）+ 两个事件（`skill/activate`、`skill/allowed-tools-mismatch`）+ `SkillAudit`；**不新增模块**；字段清单升为 80/71 | 能力缺口已封；与 Go 的有意差异：不新增模块、`allowed-tools` 不生效 | ☑已落地 ☐需人做（G1–G6 评审；`allowed-tools` 那条见 **Q8**） |
| B15 | ~~**Go 的 `http` 工具 Java 没有**~~ → **已撤销**（2026-11-06 当日更正）：`http` 是 `HTTPFetcher` 的实现标识，不是模型可见工具；`web.Tools()` 只产出 `web_search` + `web_fetch` | 无缺口 | ☑无需处理（事实更正） |
| B16 | **J8：Linux Landlock 后端代码在位但未在 Linux 上跑过**（本机 Darwin；`LandlockScopeTest` 5 条在非 Linux 上跳过）→ **默认不装配**（`supported()` 只认已验证的平台）；**Windows 后端完全没做**（且 Windows 上 shell 工具不可用：`/bin/sh` 与 `ulimit` 都是 Unix 假设）；~~**Go 的动态 Scope 未对位**~~ → **Q2′ 已补**（`LocalRunner.pathScopeSupplier` + `PathScopeFactory`，SDK 把 `RootSet` 的活根接进 supplier；守护用例 `DynamicPathScopeTest.aNewlyGrantedRootReachesTheNextShellCall`）；请求期 `RunRequest.pathScope` **有意不补**（Go 自己的 shell 工具也不传），登记为有意差异 | 三平台能力矩阵中 Linux/Windows 两行不可验收；请求期字段与 Go 不同构 | ⚠️ **建议「需人做」**：Linux/Windows 按用户口径暂缓（可接受）；动态 Scope 缺口已封（Q2′），请求期字段登记为有意差异 ｜☐接受（登记为缺口） ☐需补 ☐需人做 |
| B17 | **Go 参考实现有完整 MCP 接入**（S8.2/S8.3/S8.4：`tool/mcp/` 12 文件约 3600 行，2026-11-04/06 落地），**MoS4J 原先是 0 行** → **已立项并完成 M0–M3**（2026-11-07，用户拍板「官方 SDK」；**2026-11-08 起并入 `mos4j-tool`**，进默认 reactor，官方 SDK 为 **optional** 依赖）：官方 MCP Java SDK **2.1.0**，契约层 + 客户端 + 命名 + 适配 + **真 stdio 子进程 E2E** + **多服务器管理器**（两阶段换代 / 失败隔离 / scope 隔离）+ **三档曝光落注册表** + **说明每会话注入** + **认证**（`McpTokenProvider`，401/403 归认证类）+ **资源工具三个** + **policy / guard 联动** + **`mcp_servers` 分节** + **Describe 快照 / `Agent.close()`** + **结果投影口径收紧** + **deferred 工具同轮再声明** + **streamable-http 真服务器 E2E**（测试作用域嵌入式 Jetty）。**有意不做**：Starter 接入（会把官方 SDK 的传递依赖带进 Starter 基线）。**依赖纪律破例**：`slf4j-api`（SDK 内 199 处引用）/ `reactor-core` / `json-schema-validator` 只进本模块（见 SI-52：例外随模块合并改挂到 `mos4j-tool`，范围不变）；Jetty **仅 test scope** | 相对参考实现的能力缺口已从「完全没有」收窄到「M0–M3 全做完」；I7 条款需修订 | ⚠️ **建议「需人做」**：I7 破例要在签字时一并认可；对外口径只能到「客户端与编排可用」（详见 [`MCP-STATUS.md`](MCP-STATUS.md)）｜☐接受（登记为缺口） ☐需补 ☐需人做 |
| B18 | **发布配置部分就绪**：`release` profile 已配**源码 / javadoc 附件 + GPG 签名**，`internal-publish` profile 已配**内部私服**的 `distributionManagement`（地址与仓库 id 由 `-D` 注入，**不写死环境信息**；未注入即 fail-closed），POM 已补 **`<scm>`** 与 **`<licenses>`**（**MIT**，2026-11-07 用户拍板）；**发布目标已裁决**（2026-11-07 用户：**先发内部私服**，Maven Central 暂缓）。**仍缺**：**私服地址与账号**、**GPG 密钥**（均不入库）、**人工评审签字**（108 条待签）、**Seatbelt 强制效果未在真机验收**。**已备好**：发布清单与步骤 [`docs/release.md`](../release.md)、发布说明草稿 [`CHANGELOG.md`](../../CHANGELOG.md)、对外版本 API（`net.hegq.mos4j.core.Version`，与 Maven 坐标对齐，有机械门） | 构件能否真的发到目标仓库，以及发布出去的构件能不能被信任与追溯 | ⚠️ **建议「需人做」**：私服地址、密钥与账号属人；签字与 Seatbelt 验收必须人做（详见 [`docs/release.md`](../release.md) §4）｜☐接受 ☐需补 ☐需人做 |
| B19 | ~~**Go 差异清单里剩下 7 项未实现**~~ → **已全部补齐**（2026-11-08）：首轮 10 处 + 同日逐行复核 3 处 = **13 项全部落地**（#1 子代理 / #2 续跑 / #3 审批先验 / #4 派生读 / #5 压缩三开关 / #6 协议注册表 / #7 上下文文件 / #8 提示词构建器 / #9 根管理 / #10 示例集 / #11 取消原因 / #12 运行期参数 / #13 本地缓存）；逐条语义见 [`GO-DIFF.md`](GO-DIFF.md)，API 见 [`API.md`](API.md) §3.15 | 相对参考实现的能力缺口已从「13 项」收窄到「0 项」 | ✅ 建议接受（**实现已落地，待逐行签字**；机器用例通过 ≠ 语义已对齐）｜☐接受 ☐需补 ☐需人做 |
| B20 | **#5/#7/#9 的四条有意差异**（压缩开关冲突 fail-fast、摘要超时拒绝负值、根管理按挂载名、上下文文件按 Builder 装配） | 与 Go 的调用形状不同（跨语言回放时需注意） | ✅ 建议接受（已登记为 SI-39…SI-42，语义与 Go 等价，差别只在**装配形状与失败时机**；见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.8）｜☐接受 ☐需补 ☐需人做 |
| B21 | ~~**取消原因缺失**~~ → **已补齐**（2026-11-08）：`CancellationToken.cancel(Throwable reason)` / `cause()`；**首次原因生效**（先到先得）；未取消或无因时 `cause()` 返回 `null`；原因写进 `turn/end.error` 与 driver 抛出的异常；**不新增冻结事件字段**；守护：`CancellationTokenCauseTest` 8 条 + `RuntimeKnobsTest.cancellationReasonReachesTurnEndAndTheThrownError` | 宿主现在能分清「用户点了停止」「上游超时」「进程退出」 | ✅ 建议接受（已落地；口径见 [`API.md`](API.md) §3.15.7）｜☐接受 ☐需补 ☐需人做 |
| B22 | ~~**运行期可调参数缺失**~~ → **已补齐**（2026-11-08）：`Agent.setConcurrency` / `setMaxDecisionRounds` / `setRules(ParamPolicy)`；驱动**每轮开始时**现取轮数上限、**工具执行前**现取并发值；参数级规则必须**装配期先装过** `ParamPolicy`，否则 fail-closed；名字级与参数级槽状态独立；守护：`RuntimeKnobsTest` 5 条 | 宿主不用重建 Agent 就能限并发 / 收紧参数规则 | ✅ 建议接受（已落地；口径见 [`API.md`](API.md) §3.15.8）｜☐接受 ☐需补 ☐需人做 |
| B23 | ~~**`llm.WithCache` 语义缓存缺失**~~ → **已补齐**（2026-11-08）：`CachingChatModel(ChatModel, Duration)`；只缓存**非流式**成功结果；`ttl<=0` 不过期；取消检查先于命中；失败 / 空响应不缓存；命中返回同一 `Response` 对象；**缓存键覆盖完整请求上下文**（含 schema 递归排序后哈希）；与**服务端 prompt cache 不是一回事**；守护：`CachingChatModelTest` 10 条 | 重复提问省得到钱；不影响正确性 | ✅ 建议接受（已落地；口径见 [`API.md`](API.md) §3.15.9 与 SI-50）｜☐接受 ☐需补 ☐需人做 |
| B24 | **13 项差异补齐带来 9 条新的有意差异**（SI-43…SI-51）：子代理集不热加载 / 回执结构固定化 / 「无可续」用 checked 异常 / 先验复用不新增 `reusedFrom` / 派生读纯读纪律 / 注册表用 `Map<String,String>` / 提示词双入口互斥 / 缓存键覆盖面更严 / 示例独立交付 | 跨语言回放时调用形状与 Go 不同（但**语义等价**） | ✅ 建议接受（**逐条登记在 [`SELF-INVENTED.md`](SELF-INVENTED.md) 与 [`GO-DIFF.md`](GO-DIFF.md) §4**；差异点全在「装配形状与失败时机」，不在语义）｜☐接受 ☐需补 ☐需人做 |
| B25 | **机器测试 ≠ 跨语言回放**：13 项差异的 Java 用例证明「实现自洽」，**不证明**「与 Go 的逐行语义一致」；Go 原测试名已逐字抄录对位（[`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.9），但**回放比对本身尚未做**（需两边的会话日志同输入对照） | 语义对齐的最后一层证据缺失 | ⚠️ **建议「需人做」**：机器能证「同名测试存在」，不能证「断言等价」；这正是本表存在的原因｜☐接受 ☐需补 ☐需人做 |
| B26 | **六模块合并后，部分依赖方向不再由 Maven 依赖图强制**（2026-11-08）：合并前「工具层不能 import 驱动层」是 Maven 依赖图的硬约束；合并后同层能力住进同一模块，只能靠**包前缀边界**（`BoundaryRulesTest`：能力层包不得 import `agent`/`driver`/`sdk`/`cli`）守住 | 包前缀门是**测试约定**，不是编译器强制——绕过测试直接 import 仍能编译通过；另外 `net.hegq.mos4j.mcp` 的包名与所在模块 `mos4j-tool` 不一致，MCP 官方 SDK 的传递依赖（slf4j / reactor / json-schema-validator）只出现在这一个模块 | ⚠️ **建议「接受 + 知悉」**：边界门已做**负向验证**（注入违规 import → 门变红）；§1 I7 的依赖纪律仍由父 POM enforcer 强制；MCP 包路径保留是为不破坏既有 import｜☐接受 ☐需补 ☐需人做 |

---

## 6. 里程碑门（G1–G6）判定

| 门 | 机械结论 | 人工结论 | 判定 |
|---|---|---|---|
| G0 契约冻结 | D1/D2/D6 三份文档已定稿；字段名清单冻结并有守护测试 | 文档是否与参考实现语义一致 | ✅ 机械通过（清单 80/71 + `FieldNameContractTest`）；**人工待做**：D1/D2/D6 三份文档尚未被人**逐行读过并签字** → ☐通过 ☐不通过 ☐需讨论 |
| G1 最小垂直切片 | OpenAI L1 5/5 + L2 16/16 + 一个 turn 端到端 | turn 语义是否与 Go 一致 | ✅ 预填：建议通过（`L1OpenAiScenariosTest` @Test=5、L2 场景 16 条全绿、端到端一轮真跑）<br>☐通过 ☐不通过 ☐需讨论 |
| G2 安全纵深 | L3 D1/D2/D3 全绿（16 条） | 拒绝/冻结/审批语义是否与 Go 一致 | ✅ 预填：建议通过（`ExecPathSecurityTest` @Test=25 / `SecurityFlowTest` @Test=8）；**附一条待看**：B5 审批线程模型<br>☐通过 ☐不通过 ☐需讨论 |
| G3 文件与沙箱 | D4 8/8；jail 双校验 + 观察门 + 沙箱 | 边界判定是否与 Go 一致 | ⚠️ **条件通过**：文件层（`SandboxedFsTest` 18 + `RootSetTest` 8 + `ObserveGateTest` 11）全绿；**进程层路径强制的强制效果未验证**（B3）→ 需人做（换环境）<br>☐通过 ☐不通过 ☐需讨论 |
| G4 上下文工程 | 压缩遮蔽 / 切割点合法 / 逐字节一致 | 预算与切割语义是否与 Go 一致 | ✅ 预填：建议通过（7 + 3 + 3 + 6 条用例）<br>☐通过 ☐不通过 ☐需讨论 |
| G5 工具全集与联网 | **13 个必备内置工具 + 1 个条件注册（`skill`）**行为一致；grep 用 RE2J；SSRF 判定层绕过测试齐 | 工具语义与 glob/grep 口径是否与 Go 一致 | ✅ 预填：建议通过（`ToolContractTest` 机器守护 14 个工具名与参数名；`FileToolsTest` @Test=30）<br>☐通过 ☐不通过 ☐需讨论 |
| G6 三协议 + 硬化 | L1 三协议全绿；取消/超时无泄漏；依赖审计 12 模块 | 协议口径（结束标记 / usage）是否与 Go 一致 | ✅ 预填：建议通过（5 + 12 + 6 条 L1 用例；12 模块依赖审计全绿）；**Q4 已实施**（Gemini 逃生阀门面可达，用错协议 fail-fast）<br>☐通过 ☐不通过 ☐需讨论 |

---

## 7. 签署

### 7.1 签字进度（签之前 = 全部「未签」）

| 节 | 条目 | 待签 | 已裁决 | 备注 |
|---|---|---|---|---|
| §1 七条不变式 | I1–I7 | 7 | 0 | |
| §2 十条机制 | M1–M10 | 10 | 0 | |
| §3 三协议口径 | R1–R10 | 10 | 0 | |
| §4 Java 侧自创机制 | SI-1…SI-52 | 52 | 0 | |
| §5 诚实边界 | B1–B26 | 22 | 4（B1 / B13 / B14 / B15） | B3 / B5 / B16 / B17 / B18 / B25 为「需人做」类，签的是「接受这个待办」而不是「已做完」 |
| §6 里程碑门 | G0–G6 | 7 | 0 | G0 另需 D1/D2/D6 三份文档逐行签字 |
| **合计** | | **108** | **4** | |

### 7.2 签署栏

| 项 | 内容 |
|---|---|
| 评审人 | ____________________ |
| 日期 | ____________________ |
| 评审范围 | ☐ 全表 112 条　☐ 部分（未评审条目列在 §7.3） |
| 结论 | ☐ 全部通过　☐ 有不通过项（列出：____________）　☐ 需维护者拍板（列出：____________） |
| 遗留处理 | ☐ 接受现有边界　☐ 列入下一里程碑　☐ 需授权（Go 仓库只读 / 其他） |
| 未评审条目 | （若不是全表评审，在这里逐条列出编号：____________） |
| 备注 | ________________________________________________ |

### 7.3 未评审条目（如实列出，不留空）

| 编号 | 原因（没看 / 没时间 / 需讨论） | 计划 |
|---|---|---|
| | | |

---

## 8. 预填依据（2026-11-08，可复核）

| 项 | 结果 |
|---|---|
| 引用存在性（脚本核对） | 本表内 **41 条 `XxxTest.方法` 引用全部存在**（去重 37 条；脚本按源码里的方法签名逐条比对；另有 `*.java` 是文件名引用、`ToolCall.args` 是字段引用，均非方法引用）；**【代码】块里的仓库内路径全部存在**（按「仓库根相对」与「`docs/contract/` 相对」两种基准解析；`mos4j-xxx/.../Xxx.java` 是省略写法，按末段文件名在仓库内定位；Go 参考侧路径 `llm/...`、`sandbox/...` 不在本仓库，不参与该检查） |
| 测试计数复核 | 表内写的计数与实际 `@Test` 数一致：`ObserveGateTest` 11 / `SandboxedFsTest` 18 / `RootSetTest` 13 / `CompactionBudgetTest` 7 / `CompactionCutPointTest` 3 / `PreviousSummaryTest` 3 / `ContextFlowTest` 8 / `UsageJsonTest` 4 / `ShellToolTest` 8 / `LocalRunnerTest` 14 / `ExecPathSecurityTest` 25 / `SecurityFlowTest` 8 / `GuardBusTest` 7 / `BoundaryRulesTest` 7 / `FileToolsTest` 30 / `ExecPathTest` 15 / `L1OpenAiScenariosTest` 5 / `L1AnthropicScenariosTest` 12 / `L1GeminiScenariosTest` 6；`@Disabled` = 0（三协议语料 `@Disabled` 已归零） |
| 机械门（2026-11-08，**六模块合并 + Go 差异 13 项补齐后**） | 默认 reactor **846 通过 / 0 失败 / 7 跳过**（6 个测模块，106 个测试类）；`-Pstarter` **870 / 0 / 7**（7 个测模块）；`-Pexamples` **871 / 0 / 7**（8 个示例模块，示例自身 25 条）；`-Pexamples,mcp` **874 / 0 / 7**（示例自身 28 条）；6 个 SDK 模块 + starter + 8 个示例模块 `dependency:analyze` 无告警；C6（`src/main` + `src/test`）spring=0、regex=0；裸 NUL=0；文档相对链接 0 断链；字段清单两份副本逐字节一致（`aeb48e08…`）。日志：`.j7log2/final-default.log`、`.j7log2/final-starter.log`、`.j7log2/final-examples.log`、`.j7log2/final-examples-mcp.log`（`.j7log2/` 为本地临时目录，已移出 Git 索引并加入 `.gitignore`，不属交付物） |
| 本轮新增的守护用例 | 上一批（Q1/Q2′/Q4/Q5）：`PathScopeRuntimeTest` 11 / `Mos4jTest` 14 / `Mos4jProtocolKnobsTest` 5 / `DynamicPathScopeTest` 2 / `ReActDriverTest` 8 / starter `Mos4jStarterKnobsTest` 5；**能力缺口收口批**：`PolicyVisibilityTest` 7 / `ChatModelDecoratorsTest` 11 / `ModelCatalogTest` 9 / `AgentCloseTest` 4 / `McpAuthTest` 5 / `McpResourceToolsTest` 8 / `McpPolicyIntegrationTest` 3 / `McpKitWiringTest` 4 / `ToolContractTest` 的 MCP 资源工具冻结断言 1；**能力缺口收尾批（2026-11-07）**：`McpDeferredVisibilityTest` 2 / `McpResultProjectionTest` 9 / `McpHttpServerE2ETest` 3 / `MainTest.skillsDir_isWiredIntoTheSession` 1 / `SkillCatalogConcurrencyTest` 1；**Go 差异补齐批（2026-11-07，#5/#7/#9）**：`ContextFilesTest` 16 / `RootSetTest` 13 / `RootManagementTest` 3 / `DynamicPathScopeTest` 3 / `Mos4jTest` 19 / starter `Mos4jAutoConfigurationTest` 18；**Go 差异 13 项补齐批（2026-11-08）**：`SubAgentCatalogTest` 12 / `SubAgentToolsTest` 12 / `SubAgentToolTest` 11 / `SubAgentFlowTest` 7 / `ResumeTest` 9 / `SessionApprovalPriorTest` 9 / `ApprovalPriorTest` 6 / `SessionDerivationTest` 8 / `ModelRegistryTest` 8 / `PromptBuilderTest` 6 / `CancellationTokenCauseTest` 8 / `RuntimeKnobsTest` 5 / `CachingChatModelTest` 10 / examples 28 + `StubModelTest` 5 |
| Go 侧实读 | **J8 批次期间完成，本轮未重读**（结论沿用）：`sandbox/seatbelt_darwin.go`（profile 形态与转义）、`sandbox/pathscope.go`（`deriveScope` / `effectiveScope` / `validateScope` / `writeOpen`）、`sandbox/seatbelt_darwin_test.go`（3 条用例，已对位为 Java 用例）、`sandbox/scope_dynamic_darwin_test.go`（动态 scope，→ Q2 缺口）、`llm/model.go`、`llm/gemini/gemini.go`（`require_finish` 逃生阀） |
| 本轮**没有**复核的 | Go 侧**除上述文件外**的源码未重新逐行读（沿用 J2–J7 已完成的对拍结论与冻结语料）；「语义是否与 Go 一致」的最终判断仍属人工范畴 |
| 待人拍板 | **已拍板（2026-11-07，用户「全部接受建议」）**：Q1–Q8 全部按建议实施，详见 [`REVIEW-QUESTIONS.md`](REVIEW-QUESTIONS.md) 每条的「裁决与实施」栏。**剩余的人工动作**：本表的逐行签字（§7.2 签署栏）。 |
| 评审表覆盖核对（2026-11-08） | §1 `I1–I7`（7）+ §2 `M1–M10`（10）+ §3 `R1–R10`（10）+ §4 `SI-1…SI-52`（52）+ §5 `B1–B26`（26）+ §6 `G0–G6`（7）= **112 条判定项**；已裁决 4 条（B1 / B13 / B14 / B15）→ **108 条待签**。**本轮修掉的真实缺口**：§4 此前只列到 SI-31，漏了 J8/Q 批次新增的 **SI-32（装配期探测）与 SI-33（显式出口与关闭原因自述）**；能力缺口收口批补入 **SI-34（policy 单一状态源）/ SI-35（`DescribeContributor` 槽）/ SI-36（`Agent.close()` 容错语义）**；收尾批补入 **SI-37（MCP 结果投影按类型占位）/ SI-38（CLI `--skills` 参数面）**；Go 差异补齐批（2026-11-07）补入 **SI-39…SI-42**；**Go 差异 13 项补齐批（2026-11-08）补入 SI-43…SI-51（9 条）**；**六模块合并批（2026-11-08）补入 SI-52（官方 MCP SDK 的 SLF4J 例外改挂在 `mos4j-tool`）**；§5 补入 **B19（Go 差异清单）/ B20（#5/#7/#9 的四条有意差异）**，并把 **B21（取消原因）/ B22（运行期可调参数）/ B23（`llm.WithCache`）** 从「backlog」改写为「已补齐」，新增 **B24（9 条新有意差异）/ B25（机器测试 ≠ 跨语言回放）**；**六模块合并批（2026-11-08）新增 B26（模块合并后依赖方向由 Maven 图降为包前缀门）**。**Go 差异逐行复核**：见 [`GO-DIFF.md`](GO-DIFF.md)（13 项已全部落地；Go 原测试名逐字对位见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.9） |

---

## 附：评审前建议跑一遍的命令

```bash
# 全量构建 + 全部测试（默认 reactor 基线 846 通过 / 7 跳过；含按需 starter 为 870/7，含按需 examples 为 871/7，含 examples+mcp 为 874/7）
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pstarter clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pexamples clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pexamples,mcp clean verify

# 依赖纪律（6 个 SDK 模块）
./mvnw -B -am -pl mos4j-core,mos4j-model,mos4j-runtime,mos4j-tool,mos4j-session,mos4j-agent \
dependency:analyze -Dmdep.analyze.failOnWarning=true

# 边界门（负向验证：临时违规 → 应变红）
./mvnw -B -pl mos4j-agent -am test -Dtest=BoundaryRulesTest -Dsurefire.failIfNoSpecifiedTests=false

# 源码扫描（期望均为 0；SDK 模块含测试）
grep -rn "import java.util.regex" --include="*.java" mos4j-*/src | wc -l
grep -rn "import org.springframework" --include="*.java" mos4j-*/src | wc -l

# 裸 NUL 字节（期望 0）
grep -rlP "\x00" --include="*.java" mos4j-*/src examples/*/src 2>/dev/null | wc -l
```
