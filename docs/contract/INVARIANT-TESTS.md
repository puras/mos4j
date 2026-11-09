# 不变式测试逐条台账（T2.11）

> **地位**：把 `INVARIANTS.md` 的七条不变式与 `MECHANISMS.md` 的十条机制，逐条对应到**真实存在的 Java 测试方法**，
> 并把 Go 参考仓库（`/Users/puras/workspace/proj/hegq/mos`，只读）的**原测试名**抄录在同一行，
> 让评审人可以两边对着看。
> **用途**：`docs/mos4j-tasks.md` 的 G2 门「Go 不变式测试逐条译过（映射表无空项）」以本文件为准；
> 总览见 [`INVARIANT-TEST-MAP.md`](INVARIANT-TEST-MAP.md)。
>
> **抄录口径（2026-11-06 更新）**：Go 只读授权已获得，「Go 侧原测试」列改为**逐条抄录真实存在的测试函数名**，
> 形式为 `包路径.测试名`（例：`agent/hook.TestGuardVerdictNotBypassableByHooks`）。
> **不猜**：没有对应 Go 测试的条目如实写「Go 侧无对应测试」并说明原因；
> 抄录自 Go 仓库的 `func Test…` 定义，可用 `grep -rn "^func Test" <包>` 复核。
> **本文件不替代人工判断**：机器只能证明「同名测试都存在」，证不了「Java 用例断言的就是 Go 那条断言」——
> 后者是 [`REVIEW-SHEET.md`](REVIEW-SHEET.md) 的人工签字项。

---

## 1. 逐条台账

| 编号 | 不变式 / 机制（摘要） | Go 侧原测试 | Java 守护测试（真实类名.方法名） | 状态 |
|---|---|---|---|---|
| I1 | fail-closed：能力未装配 = 拒绝，不是放行 | `agent/approval.TestServiceWithoutAnswererFailsClosed`；`agent/execpath.TestPreExecuteAskFailsClosed`；`agent/execpath.TestNilRegistryFailsClosed`；`agent/driver/react.TestApprovalNotConfiguredFailsClosed`；`agent/driver/react.TestMissingPlannerFailsClosed`；`agent/driver/react.TestToolsWithoutRegistryFailsClosedCleanly`；`agent/policy.TestParamPolicyUnparsableArgsFailsClosed`；`tool/builtin.TestShellWithoutRunnerFailsClosed`；`tool/builtin.TestReadWithoutFilesystemFailsClosed`；`tool/builtin.TestEditWithoutFilesystemFailsClosed`；`tool/builtin.TestGrantRootFailClosed`；`llm/openai.TestNewMissingModel`；`llm/anthropic.TestNewMissingConfig`；`llm/gemini.TestNewMissingConfig` | `ExecPathSecurityTest.l3_4_askWithoutChannelIsDenied`；`SecurityFlowTest.missingApprovalChannelFailsClosed`；`GuardBusTest.guardExceptionIsDenied`；`GuardBusTest.nullDecisionIsDenied`；`ApprovalServiceTest.nullAnswerIsUnavailable` | ✅ |
| I2 | 日志即事实：append-only，投影自愈 | `session.TestAppendSeqContinuityAndCopy`；`session.TestAppendFailFast`；`session.TestJSONLRoundTrip`；`session.TestJSONLTrailingHalfLine`；`session.TestTotalUsageDerivedNotStored`；`session.TestJSONLDoesNotEscapeHTML`；`session.TestHeaderRoundTrip`；`session.TestOpenCreatesAndRestores`；`session.TestRestoreRepairsOrphanTurn`；`session.TestReasoningRoundTrip` | `L2ScenariosTest.logIsNotRewritable`；`L2ScenariosTest.maskingDoesNotRewriteLog`；`ProjectionTest`（四态）；`SessionTest.eventsReturnsDefensiveCopy` | ✅ |
| I3 | 审计不变式：错误只可加重不可减轻 | `agent/execpath.TestPostExecuteCanEscalateToError`；`agent/execpath.TestPostExecuteCannotWashError`；`agent.TestDefaultAuditorBuiltinWhitelist`；`agent.TestDefaultAuditorTrust`；`agent.TestDefaultAuditorCustomList`；`agent.TestNilAuditorTrustedNothing`；`agent/approval.TestServiceAuditFailureAborts` | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess`；`ExecPathSecurityTest.l3_8_postHookCanWorsenASuccess` | ✅ |
| I4 | 上下文是稀缺资源：预算、遮蔽、治理事件不进投影 | `agent.TestShouldCompact`；`agent.TestPlanCutNeverAtToolResult`；`agent.TestPlanNoOp`；`agent.TestSummarizePromptShape`；`agent.TestSummarizeRejectsToolCalls`；`agent.TestSummarizeNilModel`；`agent.TestSerializeConversation`；`session.TestProjectCompactionShadowing`；`session.TestProjectRepeatedCompaction`；`session.TestSessionContextTokens`；`session.TestTotalUsageIncludesCompactionUsage`；`agent/driver/react.TestCompactionWiredIntoDecide`；`agent/driver/react.TestIterativeCompactionCarriesPreviousSummary`；`agent/driver/react.TestCompactionThresholdGate`；`agent/driver/react.TestCompactionPlanNoOp`；`agent/driver/react.TestCompactionFailureAbortsTurn` | `L2ScenariosTest.approvalEventsAreAuditOnly`；`SessionApprovalAuditTest.approvalEventsDoNotEnterTheProjection`；`SessionTest`（usage 折叠 / context token 基座）；`CompactionBudgetTest`（7 条）；`CompactionCutPointTest`（3 条）；`PreviousSummaryTest`（3 条）；`ContextFlowTest.compactionRunsBeforeTheModelCall` | ✅（J4 补齐压缩部分） |
| I5 | Hook / Guard 双轨制：类型分离、钩子无法绕过守卫 | `agent/hook.TestGuardVerdictNotBypassableByHooks`；`agent/hook.TestGuardBusSerialDeny`；`agent/hook.TestGuardBusAllows`；`agent/hook.TestNilGuardBusAllows`；`agent/hook.TestGuardRemovalIsIdempotent`；`agent/hook.TestKindsAreIsolated`；`agent/hook.TestWaterfallOrderAndRewrite`；`agent/hook.TestWaterfallShortCircuit`；`agent/hook.TestWaterfallErrorPropagation`；`agent/hook.TestWaterfallNilEventRejected`；`agent/hook.TestNilHookBusIsNoop`；`agent/execpath.TestGuardDenialSkipsExecution`；`agent/execpath.TestGuardSeesCallAndTurn`；`agent/execpath.TestNilGuardsAllowEverything`；`agent/driver/react.TestGuardDeniesThroughDriver` | `DualTrackStructureTest.guardAndHookEventsAreDistinctTypes`；`DualTrackStructureTest.guardApiNeverMentionsHookTypes`；`DualTrackStructureTest.hookApiNeverMentionsGuardTypes`；`ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks`；`ExecPathSecurityTest.l3_9_guardRunsExactlyOncePerCall` | ✅ |
| I6 | 零开销降级：未启用的能力不多一次拷贝 | `agent/hook.TestNilHookBusIsNoop`；`agent/hook.TestNilGuardBusAllows`；`agent/execpath.TestNilGuardsAllowEverything`；`agent.TestTruncateNoOp`；`tool/builtin.TestObserveNilGateIsLegacy`；`tool/builtin.TestSearchToolsWithoutFilesystem`；`sandbox.TestScopeFuncNilFallsBack` | `ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled`；`ExecPathSecurityTest.emptyBusesBehaveLikeNotInstalled`；`ExecPathSecurityTest.noSecurityMeansSameBehaviorAsBefore`；`DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled`（fs / sandbox 未装配 = 工具不注册 + 快照显式声明）；`ContextFlowTest.withoutLimiterTheOutputIsUntouched`（未装配限流器 = 输出逐字原样） | ✅（J2 + J3 + J4） |
| I7 | 依赖纪律：SDK 运行时只有 Jackson 家族 / RE2J / SnakeYAML 系 / JDK | **Go 侧无对应测试**（Go 用 `go.mod` 管理依赖，没有同款「运行时依赖白名单」机械门；这条是 Java 侧新增的纪律） | 机械门：Enforcer `bannedDependencies` + CI 源码扫描 + `dependency:analyze` 硬门（`mos4j-core/llm/fs/sandbox/tool/session/hook/execpath/driver/policy`） | ✅（机械门） |
| M1 | 载荷冻结：name / args / id 逐字比对 | `agent/execpath.TestHookCannotRewritePayload`；`agent/policy.TestCheckCannotMutatePayload` | `ExecPathSecurityTest.l3_3_rewritingNameIsFrozenViolation`；`l3_3_rewritingArgsIsFrozenViolation`；`l3_3_rewritingCallIdIsFrozenViolation`；`l3_3_frozenToolIsNotExecuted` | ✅ |
| M2 | 回填序 = 调用序 | `agent/execpath.TestOutcomesFollowCallOrder`；`agent/execpath.TestConcurrentToolsOverlapButKeepOrder`；`agent/driver/react.TestConcurrentResultsKeepCallOrderThroughDriver` | `ExecPathTest.resultsFollowCallOrderNotCompletionOrder` | ✅ |
| M3 | finalize 单向（只可加重） | `agent/execpath.TestPostExecuteCannotWashError`；`agent/execpath.TestPostExecuteCanEscalateToError` | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` | ✅ |
| M4 | 投影自愈（孤儿回执 / 序位 / seq 继承 / 与遮蔽同生共死） | `session.TestProjection`；`session.TestRestoreRepairsOrphanTurn`；`session.TestProjectHealsUnpairedToolCalls`；`session.TestProjectOrphanKeepsPairUnderShadowing`；`session.TestViewSeqs` | `ProjectionTest` 四组用例 | ✅ |
| M5 | 流完整性按结束标记判定 | `llm/openai.TestChatStreamTruncatedErrors`；`llm/openai.TestChatStreamWithDoneOnlyIsComplete`；`llm/anthropic.TestChatStreamTruncatedErrors`；`llm/anthropic.TestChatStreamWithoutMessageDeltaIsComplete`；`llm/anthropic.TestChatStreamCutWithoutAnyEndSignalErrors`；`llm/gemini.TestChatStreamTruncatedErrors`；`llm/gemini.TestRequireFinishFalseAllowsMissingFinish`；`agent/driver/react.TestEmptyFinishReasonIsNotTruncated` | `L1OpenAiScenariosTest`（O5a 真截断 / O5b 只发 `[DONE]`）；`L1AnthropicScenariosTest`（12 条）；`L1GeminiScenariosTest`（6 条） | ✅（J6 补齐 Anthropic / Gemini） |
| M6 | 用量统一在 FreshInputTokens | `llm/transport.TestUsageFromAnthropicPath`；`llm/transport.TestUsageFromOpenAIPath`；`llm/transport.TestUsageFromGeminiPath`；`llm/wrap.TestUsageFreshInput`；`llm/wrap.TestUsageOmitsEmptyDetails`；`llm/wrap.TestUsageCacheHitRatio`；`llm/openai.TestChatUsageDetails`；`llm/openai.TestChatStreamUsage`；`llm/anthropic.TestChatUsageCacheDetails`；`llm/anthropic.TestChatStreamUsageFromMessageStart`；`llm/anthropic.TestChatStreamUsageMergesBothEvents`；`llm/anthropic.TestChatStreamUsagePartsSumToTotal`；`llm/anthropic.TestChatStreamUsageDeltaWinsOverStart`；`llm/anthropic.TestChatStreamUsageStartFillsDeltaGaps`；`session.TestTotalUsageDistinguishesCache`；`session.TestTotalUsagePartsSumToTotal` | `UsageJsonTest`；`L1OpenAiScenariosTest`（O4）；`L1AnthropicScenariosTest`；`L1GeminiScenariosTest` | ✅ |
| M7 | 写前观察（read-before-write） | `tool/builtin.TestObserveHappyPath`；`tool/builtin.TestObserveWriteRequiresRead`；`tool/builtin.TestObserveStaleAfterExternalChange`；`tool/builtin.TestObserveEditRequiresRead`；`tool/builtin.TestObserveDeletedAfterRead`；`tool/builtin.TestObserveConsecutiveWrites`；`tool/builtin.TestObservePerFile`；`tool/builtin.TestObserveNilGateIsLegacy` | `ObserveGateTest.l3_15_writeWithoutReadIsDenied`；`l3_16_writeAfterReadWithoutChangeIsAllowed`；`l3_17_writeAfterReadButChangedIsDenied`；`l3_17_deletedAfterReadIsDenied`；`newFileDoesNotRequirePriorRead`；`fileAppearingAfterAbsentObservationIsDenied`；`versionTokenUsesDevInoSizeMtimeOnUnix`；`FileToolsTest.l3_15_*`；`l3_16_editWithoutReadIsDenied`；`l3_17_newFileDoesNotRequireRead` | ✅ |
| M8 | 压缩遮蔽 / 切割点 / previous-summary | `agent.TestShouldCompact`；`agent.TestPlanCutNeverAtToolResult`；`agent.TestPlanNoOp`；`agent.TestSummarizeRejectsToolCalls`；`agent.TestSummarizeNilModel`；`session.TestProjectCompactionShadowing`；`session.TestProjectRepeatedCompaction`；`agent/driver/react.TestIterativeCompactionCarriesPreviousSummary`；`agent/driver/react.TestCompactionFailureAbortsTurn` | `CompactionBudgetTest.overBudgetTriggersCompaction`；`withinBudgetDoesNotCompact`；`withoutContextWindowNeverCompacts`；`maskingDoesNotRewriteTheLog`；`compactionUsageIsCounted`；`emptySummaryDoesNotMaskTheHistory`；`summaryFailurePropagates`；`CompactionCutPointTest.cutPointNeverLandsOnAToolResult`；`projectionStaysValidAfterCompaction`；`PreviousSummaryTest.previousSummaryIsFoldedNotAccumulated`；`transcriptFormatIsFrozen`；`summaryRequestCarriesNoTools`；`ContextFlowTest.compactionRunsBeforeTheModelCall`；`compactionFailureClosesTheTurn` | ✅（J4） |
| M9 | 路径双校验（Lexical + Realpath） | `filesystem.TestResolveInsideWorkspace`；`filesystem.TestResolveRelativeAnchoredAtWorkspace`；`filesystem.TestResolveRejectsLexicalEscape`；`filesystem.TestResolveRejectsSymlinkEscapeOnRead`；`filesystem.TestResolveRejectsSymlinkComponentOnCreate`；`filesystem.TestResolveRejectsSymlinkTargetOnWrite`；`filesystem.TestResolveRejectsSymlinkLoop`；`filesystem.TestResolveWithoutRootsDeniesAll`；`filesystem.TestRootsNoImplicitTmp`；`filesystem.TestHostDeclaredRoots`；`filesystem.TestDeniedErrorCarriesCurrentRoots`；`filesystem.TestSandboxedWithNilRootsDeniesAll`；`filesystem.TestRootSetAddAtRuntime`；`filesystem.TestRootSetReadWriteSeparate`；`filesystem.TestRootSetRemoveNarrows`；`filesystem.TestRootSetAnchorStableOnAdd`；`tool/builtin.TestReadOutsideJailDenied`；`tool/builtin.TestWriteThroughSymlinkDenied`；`tool/builtin.TestEditOutsideJailDenied`；`tool/builtin.TestGrepOutsideJailDenied` | `SandboxedFsTest.l3_11_dotDotOutOfRootIsDenied`；`l3_12_symlinkTargetOutsideRootIsDenied`；`l3_12_readAlsoGoesThroughRealpath`；`l3_13_symlinkAsMiddleComponentIsDenied`；`l3_13_leafMayNotExistButMiddleComponentStillChecked`；`l3_14_symlinkLoopIsDeniedInsteadOfHanging`；`RootSetTest`（8 条）；`FileToolsTest.readOutsideRootIsDenied`；`writeThroughSymlinkMiddleComponentIsDenied` | ✅ |
| M10 | 退出码标记 + 「不许绕」文案 | `tool/builtin.TestShellNonZeroExitIsNormalResult`；`tool/builtin.TestShellTimeout`；`tool/builtin.TestShellEmptyCommand`；`sandbox.TestNonZeroExitIsNotError`；`sandbox.TestTimeoutKillsCommand`；`sandbox.TestTimeoutKillsChildProcesses`；`sandbox.TestEmptyCommandRejected`；`sandbox.TestBadWorkDirRejected`；`sandbox.TestHostTimeoutIsDefault`；`sandbox.TestMaxTimeoutAllowsLongerThanDefault`；`sandbox.TestShorterModelTimeoutIsHonored` | `ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence`；`SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence`；`ShellToolTest.l3_18_nonZeroExitIsNotAnError`；`l3_18_successExitIsAlsoMarked`；`l3_18_timeoutIsAnError`；`LocalRunnerTest.nonZeroExitCodeIsAResultNotAnError` | ✅ |
| F-1 | 字段名冻结（`fieldnames.json`） | **Go 侧无对应清单文件**（Go 没有「字段名冻结清单」这回事；Java 侧新增）。相关事实由 `session.TestJSONLRoundTrip`、`session.TestHeaderRoundTrip`、`session.TestTotalUsageDerivedNotStored` 间接守住 | `FieldNameContractTest`；`EventFieldNameGuardTest`；`EventJsonTest`；`DtoZeroValueTest` | ✅ |

---

## 2. 文案类不变式的额外约束

拒绝文案不是措辞偏好，是安全语义（缺了「不许绕」半句，模型会拆解命令绕过规则）。
因此：

- 文案只允许从 `net.hegq.mos4j.core.Texts` 取（单一出处）；
- `Texts` 的值必须与 `API.md` §4 文案表**逐字一致**；
- 测试只引用 `Texts`，不复制字符串常量——改一处，测试全红；
- L3-1 / L3-2 / L3-4 / L3-5 / L3-6 逐条断言 `Texts.xxx(...)`（见 `ExecPathSecurityTest`）。

---

## 3. Go ↔ Java 对拍发现（2026-11-06，Go 只读授权后首次逐项核对）

> 下面是**机器核对出来的差异**，不是判决。每一项都要人来定「改 Java」还是「记为有意差异」。
> 证据均可复核：`grep -n "<名字>" <Go 包>/<文件>.go`。

### 3.1 工具名全集对照（Go 侧 13 个 / Java 侧 14 个，其中 `skill` 条件注册）

Go 侧清单来自**两个证据交叉**：① `Name() string` 实现的穷举扫描
（`grep -rn 'func (.*) Name() string { return "' --include='*.go'`）；② `var _ tool.Tool = ...` 断言 + 实际注册点
（`mos.go:415` 注册 `skill.NewTool(...)`）。不是抽样。

| # | Go 工具名 | Go 出处 | Java 工具名 | Java 出处 | 结论 |
|---|---|---|---|---|---|
| 1 | `read` | `tool/builtin/file.go:57` | `read` | `tool/builtin/ReadFileTool.java:30` | ✅ 名称一致（B13-1 已改名；参数一致） |
| 2 | `write` | `tool/builtin/file.go:230` | `write` | `tool/builtin/WriteFileTool.java:24` | ✅ 名称一致（B13-1 已改名；参数一致） |
| 3 | `edit` | `tool/builtin/edit.go:63` | `edit` | `tool/builtin/EditFileTool.java:27` | ✅ 名称一致（B13-1 已改名）+ 参数一致（B13-3 改 `edits[]`） |
| 4 | `shell` | `tool/builtin/shell.go:49` | `shell` | `tool/builtin/ShellTool.java:27` | ✅ 完全一致（B13-2 补 `description`） |
| 5 | `grep` | `tool/builtin/search.go:82` | `grep` | `tool/builtin/GrepTool.java:33` | ✅ 完全一致（B13-2 补 `glob` 过滤） |
| 6 | `glob` | `tool/builtin/search.go:286` | `glob` | `tool/builtin/GlobTool.java:25` | ✅ 完全一致（B13-2 补 `limit`） |
| 7 | `ls` | `tool/builtin/list.go:30` | `ls` | `tool/builtin/LsTool.java:25` | ✅ 完全一致（B13-2 补 `limit`） |
| 8 | `current_time` | `tool/builtin/current_time.go:19` | `current_time` | `tool/builtin/CurrentTimeTool.java:21` | ✅ 完全一致 |
| 9 | `grant_access` | `tool/builtin/grant.go:47` | `grant_access` | `tool/builtin/GrantAccessTool.java:26` | ✅ 名称一致；**参数形状有意不同**（B13-4，见 §3.2） |
| 10 | `tool_search` | `tool/searchtools/searchtools.go:24` | `tool_search` | `tool/searchtools/ToolSearchTool.java:19` | ✅ 名称与参数名一致（B13-2：`topK`→`limit`）；**语义有意不同**（B13-5，见 §3.3） |
| 11 | `web_search` | `tool/web/web.go:70` | `web_search` | `tool/web/WebSearchTool.java:25` | ✅ 完全一致 |
| 12 | `web_fetch` | `tool/web/web.go:229` | `web_fetch` | `tool/web/WebFetchTool.java:43` | ✅ 完全一致 |
| 13 | `skill` | `agent/skill/tool.go:13`（`mos.go:415` 注册） | `skill` | `tool/skill/SkillTool.java` | ✅ 名称与参数一致（J7 已补齐；两边都是条件注册：只有存在模型可见技能才注册） |
| — | — | — | `activate` | `tool/searchtools/ActivateTool.java:19` | ⚠️ **Java 有 / Go 无**（Go 的激活并入 `tool_search`，见 §3.3；B13-5 记为有意差异） |

> **更正（2026-11-06 当日）**：上一版把 `http` 列为 Go 的模型可见工具——**这是误报**。
> `tool/web/fetch.go:91` 的 `HTTPFetcher.Name() == "http"` 是**抓取器实现的标识**，
> 而 `web.Tools(searcher, fetcher)` 只产出 `web_search` + `web_fetch` 两个工具
> （`tool/web/web.go:282-291`；`var _ tool.Tool` 只有 `*Search` 与 `*Fetch`）。
> Go 侧模型可见工具实为 **13** 个，与 Java 侧同为 13 个。

Go 侧**从未出现** `read_file` / `write_file` / `edit_file`（Go 仓库 0 命中）。
`docs/mos4j-tasks.md` §10 / `J3-STATUS.md` 预登记过规则：「不一致则改名并在 `API.md` §3.4 记录」。

### 3.2 参数形状也不一致（所以「只改名」会造成假兼容）

| 工具 | Go 的参数（`json` tag 原文） | Java 的参数 | 后果 |
|---|---|---|---|
| 读 | `path` + `offset?` + `limit?`（`file.go:51-54`） | `path` + `offset?` + `limit?` | ✅ 一致（Go 的 `limit` 描述里写明「默认 2000、另有 50KB 总量上限」） |
| 写 | `path` + `content`（`file.go:225-227`） | `path` + `content` | ✅ 一致 |
| 改 | `path` + `edits[]{old_string,new_string}`（`edit.go:49-60`） | `path` + `edits[]{old_string,new_string}`（数组 / 单对象 / JSON 字符串三种形状） | ✅ 一致（B13-3；两边都要求每处 `old_string` 唯一命中，都按原文定位） |
| `grant_access` | `path` + `mode(read/write/both)`（`grant.go:42-44`） | `mount` + `dir` + `reason?` | ⚠️ **形状完全不同**：有意差异（B13-4），Go 的 `path`+`mode` 表达不了 Java 的挂载寻址 |
| `grep` | `pattern` + `path?` + `glob?` + `limit?`（`search.go:92-96`） | `pattern` + `path?` + `glob?` + `limit?` | ✅ 一致（B13-2） |
| `glob` | `pattern` + `path?` + `limit?`（`search.go:296-298`） | `pattern` + `path?` + `limit?`（缺省 1000 / 上限 10000） | ✅ 一致（B13-2） |
| `ls` | `path?` + `limit?`（`list.go:40-41`，缺省 500） | `path?` + `limit?`（缺省 500 / 上限 5000） | ✅ 一致（B13-2） |
| `shell` | `command` + `description?` + `timeout?`（`shell.go:39-41`） | `command` + `description?` + `timeout?` | ✅ 一致（B13-2；Java 把 `description` 回显在输出头部，Go 放在结构化回执的同名字段） |
| `tool_search` | `query` + `limit?`（`searchtools.go` `searchArgs`） | `query` + `limit?` | ✅ 参数名一致（B13-2：`topK`→`limit`）；语义仍不同（Go 一步激活、Java 两步，见 §3.3） |

**结论（已裁决并落地，2026-11-06）**：B13 拆为五个可独立回滚的子项，人工裁决结果：

| 子项 | 裁决 | 现状 |
|---|---|---|
| **B13-1** 文件工具模型可见名 | 改为 Go 的 `read` / `write` / `edit`（Java 类名不变） | ✅ 已落地 |
| **B13-2** 纯增量参数 | `grep.glob` / `glob.limit` / `ls.limit` / `shell.description` / `tool_search.limit` | ✅ 已落地 |
| **B13-3** `edit` 批量 | 改为 `edits[]`，三种形状，按原文定位，重叠报错，原子落盘 | ✅ 已落地 |
| **B13-4** `grant_access` 形状 | **不改成 Go 的 `path`+`mode`**，保留 Java 的 `mount`+`dir`+`reason` | ⏸ 登记为有意差异 |
| **B13-5** deferred 两步 | **不改成 Go 的一步式**，保留 `tool_search` + `activate` | ⏸ 登记为有意差异 |

工具名与参数名清单现由 `mos4j-tool` 的 `ToolContractTest` **机器守护**（14 个工具名 + 每个工具的参数名与必填参数；`skill` 在测试里以真目录 + 真 `SKILL.md` 装配），
避免以后再靠人工 grep 发现漂移。

### 3.3 deferred 工具：Go 一步，Java 两步（语义差异，不是名称差异）

| | Go | Java |
|---|---|---|
| 工具数 | 1 个（`tool_search`） | 2 个（`tool_search` + `activate`） |
| 行为 | `tool_search(query, limit)` **搜索并直接激活**命中的工具（`searchtools.go:281` 调 `reg.Activate(names)`） | `tool_search(query, limit)` 只搜索；再由模型显式调 `activate(tools[])` |
| 回执 | 结构化 `{found, loaded[], text}` | 纯文本 |
| 生效时机 | 下一轮 | 下一轮 ✅ 一致 |

Java 的两步是**能力超集**（模型可以激活没搜过的名字），但模型可见契约不同。
**已裁决（B13-5）：保留 Java 两步，不改成 Go 一步式**，登记为有意差异——
Go 的一步式反而不能显式激活没搜到的工具。

### 3.4 机制级缺口：Go 的「技能（skill）」机制——**J7 已补齐**

> 本节原是 2026-11-06 登记的机制级缺口（「Go 有 / Java 无」）。**2026-11-07 已由 J7 落地**，
> 下表右列改为实际落点；「怎么对拍的」与「哪里有意不同」一并写在这里。

| 方面 | Go 的做法 | Java 落点（J7） |
|---|---|---|
| 站位 | 渐进式披露：清单（name + description）进**系统提示词**，正文由 `skill` 工具按需加载 | ✅ 同口径：`Agent.of` 把 `SkillCatalog.render()` 经 `PromptSections.insert(..., "skills", ...)` 插到 `context` 之后、`instructions` 之前 |
| 契约 | 技能 = 一个目录 + 一个 `SKILL.md`；frontmatter 必需 `name`（kebab-case，≤64）/ `description`（≤1024）；`disable-model-invocation: true` = 模型不可见 | ✅ 同口径：`SkillMetadata` / `SkillFrontmatter` / `OsSkillReader`；常量与 Go 逐字相同（`64*1024` / `8*1024` / `64` / `1024`） |
| 运行期刷新 | 显式、幂等、全量重扫；原子快照替换；digest 只含元数据 | ✅ `SkillCatalog.reload()` + `SkillReloadResult`（`Agent.reloadSkills()`）；**清单变化走 user 侧完整替换通知**，不重渲染静态系统提示词 |
| 权限面 | 技能正文走**专用工具**，不走 `read` | ✅ `skill(name)` 只返回技能正文，不碰文件工具面 |
| 审计 | 加载成功后记一条 `skill/activate`，只记元数据（name / description / dir） | ✅ `SkillAudit`（core）+ `SessionSkillAudit`（session）；**先审计后返回正文**（写失败即不加载）；另有 `skill/allowed-tools-mismatch` 诊断事件 |
| 清单渲染 | `<available_skills>` 标签 + 字节预算三级降级；丢弃条数如实写进末尾提示；纯函数逐字节稳定 | ✅ 五级降级（全量 → 只列名 → 逐条塞 → 最小提示 → 空串）；两次渲染逐字节一致有测试 |
| 边界 | `allowed-tools` 只解析、只留痕，**不生效** | ✅ 同口径：装配期警告 + 激活时求交留痕 `missingAllowed`；**不改变执行工具面**（不造第二套安全真相） |
| 模块边界 | Go 有独立的 `agent/skill` 包 | **有意不同**：不新增模块，落在 `mos4j-tool`/`core`/`session`/`driver`/`sdk` 五个既有模块（保持 12 模块） |

### 3.5 冻结清单漂移：已按批准口径并入 5 条（80 条 / 71 名 / `3d35911d…`）

2026-11-06 发现 4 处漂移；**2026-11-07 已按用户批准的口径升版**（只并入与 skill 相关的 5 条必要字段，
**不夹带**无关的 `name,omitempty` / `callId` options 漂移）：

| 差异 | Go 现状 | 冻结清单（升版后） | 结论 |
|---|---|---|---|
| `skill` | `session/session.go:144` `json:"skill,omitempty"` | ✅ 已并入 | J7 落地了 skill 事件，字段名必须有守 |
| `description` | `session/session.go:93` `json:"description,omitempty"` | ✅ 已并入 | `SkillEvent` 子字段 |
| `dir` | `session/session.go:94` `json:"dir,omitempty"` | ✅ 已并入 | `SkillEvent` 子字段 |
| `name` | `session/session.go`（`SkillEvent.Name` 无条件输出） | ✅ 已并入 | 同上（无 `omitempty`） |
| `missingAllowed` | `session/session.go`（`json:"missingAllowed,omitempty"`） | ✅ 已并入 | mismatch 载荷 |
| `callId` 选项 | `session/session.go:103` `json:"callId"`（**无** omitempty） | ⏸ 仍记为 `omitempty` | **有意不并**：名称不受影响（SHA 只覆盖名称），options 列下次维护时再刷 |

升版后可复核的机器事实：

- 条数 **80** / 唯一名称 **71**；
- `_names_sha256` = `3d35911d810641c25b8ac0da032ac6f8c1d80dd90c240a4aafe5e95d4714912c`；
- 算法 = `sha256("\n".join(sorted(set(names))))`，排序按 `(file, tag)`；
- `docs/contract/fieldnames.json` 与 `mos4j-core/src/main/resources/contract/fieldnames.json` **diff 无差异**。

### 3.6 其他口径差异（已记录，非阻塞）

| 项 | Go | Java | 出处 |
|---|---|---|---|
| 截断保留策略 | head = {`read`,`current_time`,`grep`,`glob`,`ls`}；tail = {`shell`}（`agent/truncate.go:51-60`） | head = {`read`,`current_time`,`grep`,`glob`,`ls`}；tail = {`shell`,`bash`}（`mos4j-session` `Truncation.Builder`） | ✅ head 已一致；tail 多一个 `bash` 是 Java 历史别名（无实际工具） |
| 默认截断上限 | 32KB / 2000 行（`agent/truncate.go:43-44`） | 50KB / 2000 行（PI 口径） | Java 侧是**有意**取 PI 口径，已记录 |
| 读工具自身上限 | 2000 行 / 50KB（`file.go:54` 的参数描述） | 2000 行 / 50KB（`Truncation`） | ✅ 两处口径一致 |
| 审计白名单 | {`current_time`,`read`,`grep`,`glob`,`ls`}（`agent/auditor.go:52-58`） | {`read`,`ls`,`glob`,`grep`,`current_time`}（`mos4j-session` `Auditor`） | ✅ 完全一致（B13-1 改名后） |
| `grant_access` 的 guideline | Go 有（`grant.go:62` `Guideline()`：「什么时候能申请授权」） | Java 无 guideline | 缺口，可补 |
| `web_search` / `web_fetch` 的可信度 | Go 明确**不在**审计白名单（`agent/auditor.go:46-51` 注释） | Java 同样不在 | ✅ 一致 |
| Go 的 `http` 工具 | ❌ **不存在**（`http` 只是 `HTTPFetcher` 的实现标识，见 §3.1 更正） | — | ✅ 无缺口 |

### 3.7 本批新增机制的 Go 原测试名对位（2026-11-07 补抄）

> 口径：**Go 的原测试名逐字保留**（便于回查 Go 只读仓库），右侧给 Java 侧的对应用例。
> 这些用例全部是**本批新增**的机制（policy 可见性 / 装饰器 / 模型目录 / Describe+Close / MCP policy），
> 之前只在代码里实现，**没有把 Go 的原测试名抄进台账**——这一节补上。

| 机制 | Go 原测试名（逐字） | Java 对应用例 |
|---|---|---|
| policy 可见性 | `mos_test.go:85 TestPolicyVisibility` | `PolicyVisibilityTest.aDeniedToolIsNotInTheModelToolList` / `anAllowedToolStaysVisible` / `theSamePolicyAlsoBlocksTheCallAtRuntime` |
| policy 热替换 | `mos_test.go:260 TestSetPolicyHotSwap` | `PolicyVisibilityTest.aHotSwapMovesBothTheListAndTheGuard` / `aHostSuppliedGuardBusStillGetsThePolicy` |
| 模型目录 | `mos_test.go:456 TestModelCatalogSwap` / `:503 TestSingleModelHasNoCatalog` / `:520 TestModelConfigValidation` | `ModelCatalogTest.theSwitchTakesEffectOnTheNextRound` / `aSingleModelSetupCannotSwitch` / `aDefaultIdOutsideTheCatalogIsRejected` / `aDuplicateIdIsRejected` / `theDefaultIdIsHonored` / `thePublicDescriptionCarriesNoCredentials` |
| 装饰器（模型） | `llm/wrap_test.go:53 TestWithLogging` / `:132 TestWithTimeout` / `:140 TestWithFallback` / `:182 TestWrappersCapabilitiesPassthrough` | `ChatModelDecoratorsTest.aSuccessfulCallIsReportedOnce` / `aFailedCallIsAlsoReported` / `theTimeoutCancelsTheModelCooperatively` / `anExternalCancellationIsNotReportedAsATimeout` / `aFailedPrimaryFallsBackToTheBackup` / `theCapabilitiesAreTheUnionOfBothModels` / `theDecoratorsComposeExplicitly` |
| deferred 同 turn 可见 | `agent/driver/react/react_deferred_test.go:133 TestActivatedToolVisibleInSameTurn` / `:39 TestHistoryToolNamesReactivated` | `DeferredFlowTest.activationTakesEffectNextRound` / `historicalCallIsAdmitted` + `McpDeferredVisibilityTest.aDeferredMcpToolIsSearchableAndDeclaredAfterActivation`（MCP 的 deferred 工具） |
| MCP × policy | `mos_mcp_policy_test.go:52 TestMCPToolDeniedByPolicyInvisible` / `:83 TestMCPToolBlockedByPolicyBeforeExecution` / `:140 TestMCPToolBlockedByParamRule` / `:176 TestMCPToolAskWithoutApprovalFailsClosed` / `:213 TestMCPDeniedDeferredToolNotSearchable` / `:273 TestMCPToolNotInPolicyWhitelist` | `McpPolicyIntegrationTest.aDeniedMcpToolIsInvisibleAndUncallable` / `anAllowedMcpToolStaysVisible` / `aHotPolicySwapAlsoMovesTheMcpTools` |
| 技能（skill） | `agent/skill/tool_test.go:109 TestToolRecordsActivation` / `:130 TestToolFailsWhenRecorderFails` / `allowed_tools_test.go:113 TestMismatchNotInModelResult` / `:96 TestToolNoMismatchWhenAllVisible` / `reload_test.go:132 TestReloadReplacesSnapshotAtomically` / `:153 TestReloadKeepsGoodSkills` / `render_test.go:82 TestRenderContentEscapesFrameworkTags` | `SkillToolTest.activationIsRecorded` / `auditFailureBlocksLoading` / `mismatchIsDiagnosedButDoesNotBlockLoading` / `mismatchAuditFailureIsSwallowed` / `SkillCatalogTest.reloadIsIdempotent` / `reloadReportsDiffAndFullCatalog` / `addingBadSkillDoesNotNotifyModel` / `renderContentEscapesFrameTag` |

> **有意差异提醒**：Go 的 `tool_search` **执行即激活**（`tool/searchtools/searchtools.go` 的 `Execute` 里直接 `reg.Activate(names)`）；
> Java 保留**两步式**（`tool_search` 只检索、`activate` 才激活）——这是 **B13-5 已裁决的有意差异**，不是缺口。
> 两种口径下**激活都从「下一次请求」起可见**（Java：`ReActDriver` 每轮决策前重取工具清单）。

---

### 3.8 Go 差异补齐批次（#5 / #7 / #9）的原测试名对位（2026-11-07）

> 背景：2026-11-07 的源码级初查识别出 10 处 Go↔Java 差异；用户选定**只补 #5（压缩三开关）/ #7（上下文文件装配）/ #9（Agent 层运行期根目录管理）**。
> 本节把三项差异对应的 **Go 原测试名逐字抄录**（便于回查 Go 只读仓库），右侧给 Java 侧守护用例。

| 差异 | Go 原测试名（逐字） | Java 对应用例 |
|---|---|---|
| #7 候选名优先级 | `agent/contextfile/contextfile_test.go:22 TestLoadPicksFirstCandidateInDirectory` / `:45 TestLoadOverrideShadowsSameDirectoryOnly` | `ContextFilesTest.candidatePriorityWins` / `candidatePriorityFallsBackToAgentsMd` / `sameDirectoryOverride` |
| #7 扫描顺序（全局 → 由外向内 → 工作目录） | `:74 TestLoadOrderIsGlobalThenOuterToInner` | `ContextFilesTest.walksUpwardsAndKeepsTheOuterOneFirst` / `globalDirsComeFirst` / `doesNotWalkUpwardsByDefault` |
| #7 真实路径去重 | `:104 TestLoadDeduplicatesSymlinkedFile` | `ContextFilesTest.deduplicatesByRealPath` |
| #7 递归深度上限 | `:125 TestLoadMaxDepthLimitsAncestors` | `ContextFilesTest.doesNotWalkUpwardsByDefault`（缺省 0 层）+ `ContextFiles.Options.maxDepth` |
| #7 字节预算 | `:150 TestLoadTruncatesToByteBudget` / `:188 TestLoadBudgetExhaustionSkipsRestAndWarns` / `:364 TestLoadBudgetTooSmallYieldsEmptyText` | `ContextFilesTest.byteBudgetTrimsAndWarns` / `budgetExhaustionSkipsTheRestAndWarns` / `byteBudgetPrefersLineBoundaries` |
| #7 BOM / 渲染 / 空结果 | `:213 TestLoadStripsBOM` / `:229 TestLoadRendersPathTag` / `:247 TestLoadNoFilesIsEmptyNotError` | `ContextFilesTest.stripsBom` / `renderWrapsIntoProjectInstructions` |
| #7 目录缺失 = 失败 | `:258 TestLoadMissingDirectoryIsError` | `ContextFilesTest.missingDirectoryFailsClosed` |
| #7 坏文件继续试下一个候选 | `:270 TestLoadWarnsOnUnreadableFile` / `:291 TestLoadContinuesToNextCandidateAfterBrokenFile` | `ContextFilesTest.readFailureIsDegraded` |
| #7 注入 reader / 全局目录 / `~` 展开 | `:342 TestLoadUsesInjectedReader` / `:380 TestDefaultGlobalDir` / `:390 TestExpandHome` / `:407 TestLoadExpandsGlobalDirTilde` | `ContextFilesTest.pathJoiningIsTheHostsBusiness` + `ContextFiles.expandHome` + `Mos4jTest.contextFilesAreWiredIntoTheSystemPrompt` + `Mos4jAutoConfigurationTest.contextFilesDirIsWiredIntoTheSystemPrompt` |
| #9 运行期加根 | `mos_test.go:202 TestRuntimeAddRoot` / `filesystem/roots_test.go:15 TestRootSetAddAtRuntime` | `RootManagementTest.rootsCanBeAddedAndRemovedAtRuntime` / `RootSetTest.addedRootTakesEffectOnTheNextCall` |
| #9 读写分离根 | `filesystem/roots_test.go:42 TestRootSetReadWriteSeparate` | `RootSetTest.aReadOnlyRootCanBeReadButNotWritten` / `aWriteOnlyRootCanBeWrittenButNotRead` + `RootManagementTest.readOnlyAndWriteOnlyRootsAreSeparate` |
| #9 移除根（收窄） | `filesystem/roots_test.go:60 TestRootSetRemoveNarrows` | `RootSetTest.removedRootStopsResolving` / `removingAnUnknownRootFailsInsteadOfPretending` |
| #9 锚点稳定 / 快照 / 历史 / 并发 | `:78 TestRootSetAnchorStableOnAdd` / `:134 TestRootSetSnapshotIsCopy` / `:148 TestRootSetRootsForReturnsCopy` / `:162 TestRootSetHistory` / `:183 TestRootSetConcurrentSafe` | `RootSetTest.anchorIsTheRealPathAndStaysPut` / `rootsSnapshotIsImmutable` / `concurrentAddsAreSafe` |
| #9 根接进沙箱范围（工具能写、命令也得能写） | `mos_test.go:239 TestRootsWiredToSandboxScope` | Java 侧同一份 `RootSet` 同时进文件工具与 `LocalRunner.pathScopeSupplier` → `PathScopeRuntimeTest.dynamicWriteRootsAreFetchedOnEveryCall` / `DynamicPathScopeTest.aNewlyGrantedRootReachesTheNextShellCall` |
| #9 只读根不进 shell 写范围 | `sandbox/scope_dynamic_test.go:101 TestScopeFuncReadWriteSeparate`（写根来自 `roots.RootsFor(filesystem.OpWrite)`，只读根不在其中）+ `filesystem/roots_test.go:42 TestRootSetReadWriteSeparate` | `DynamicPathScopeTest.readOnlyRootsDoNotReachTheShellWriteScope`（只写根进写范围、只读根不进） |
| #5 摘要超时 | `agent/compact_test.go:239 TestSummarizeTimeout` / `:268 TestSummarizeTimeoutDefaultAndUnlimited` / `:285 TestSummarizeTimeoutAppliesToIterative` / `:298 TestSummarizeCallerCancelIsNotTimeout` | `CompactionTimeoutTest`（4 条）+ `Mos4jTest.summaryTimeoutIsAppliedToTheInstalledCompactionService` |
| #5 压缩失败策略（fail-closed / 跳过继续） | Go `driver.CompactionFailClosed` / `driver.CompactionSkipAndContinue`（`agent/driver/react/react.go`） | `ContextFlowTest.compactionFailureClosesTheTurn` / `compactionFailureCanSkipAndContinue` |
| #5 显式关闭压缩 | Go `mos.Config.DisableCompaction`（`mos.go:96`） | `Mos4jTest.disableCompactionConflictsWithAnInstalledCompactionService`（**Java 口径 = fail-fast**，见下方有意差异） |

> **本批的四条有意差异**（不是缺口，是两边刻意不同）：
>
> 1. **压缩「显式关闭」与「已装压缩器」冲突**：Go 是「显式关闭优先」（`DisableCompaction` → 直接不装，静默）；
>    Java 是 **fail-fast**（装配期报错）——「装了又悄悄摘掉」会让宿主以为自己有压缩，实际没有。
>    见 `SELF-INVENTED.md` SI-39。
> 2. **摘要超时的负值**：Go 用**负数**表示不限；Java **拒绝负数**，不限用 `Duration.ZERO` 明确表达。
>    见 `SELF-INVENTED.md` SI-40。
> 3. **运行期根管理的参数形状**：Go 是 `AddRoot(dir)`（按目录路径）；Java 是 `addRoot(id, dir)`（按**挂载名**），
>    与既有 `RootSet.add(String id, Path dir)` 的挂载名语义一致（模型侧路径按首段匹配挂载名）。
> 4. **上下文文件的装配入口**：Go 是 `mos.Config.Context *ContextConfig`；Java 是 `Mos4j.Builder.contextFiles(...)` 四件套
>    （目录 / 候选名 / 读取器 / 预算 + 全局目录 + 递归层数），语义与 Go 的 `contextfile.Options` 一一对应。
>
> **压缩失败不写事件**（本批沿用的口径）：冻结字段清单里没有 `failed` 字段，写半截 `compaction` 事件会破坏投影语义；
> 失败原因只经 `onCompactionFailure` 回调交给宿主。

---

### 3.9 Go 差异补齐批次（#1 / #2 / #3 / #4 / #6 / #8 / #10 / #11 / #12 / #13）的原测试名对位（2026-11-08）

> 背景：2026-11-07 的源码级初查 + 同日逐行复核共识别出 **13 处** Go↔Java 差异；本批次全部补齐（差异语义见 [`GO-DIFF.md`](GO-DIFF.md)，API 见 [`API.md`](API.md) §3.15）。
> 本节把各项差异对应的 **Go 原测试名逐字抄录**（便于回查 Go 只读仓库），右侧给 Java 侧守护用例。
> 抄录来源：`grep -n "^func Test" <Go 文件>`（只抄真实存在的函数名，不猜）。

#### 3.9.1 子代理（#1）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `agent/subagent/subagent_test.go:23 TestLoadDiscoversDefinitions` | `SubAgentCatalogTest.childDirectoriesAreSubAgents` / `rootDirectoryItselfIsASubAgent` |
| `:62 TestLoadFallsBackToDirName` | `SubAgentCatalogTest.missingNameFallsBackToDirectoryName` |
| `:77 TestLoadSkipsInvalidDefinitions` | `SubAgentCatalogTest.brokenDefinitionsAreSkippedWithDiagnostics` |
| `:97 TestLoadSkipsBrokenFiles` | `SubAgentCatalogTest.brokenDefinitionsAreSkippedWithDiagnostics` |
| `:119 TestLoadDuplicateNameFirstWins` | `SubAgentCatalogTest.duplicateNamesAreFirstComeFirstServed` |
| `:139 TestLoadUnreadableDirWarns` | `SubAgentCatalogTest.unreadableDirectoryIsDiagnosed` |
| `:151 TestLoadRootDirectoryHoldsOneDefinition` | `SubAgentCatalogTest.rootDirectoryItselfIsASubAgent` |
| `:165 TestReadInstructionsIsLive` | `SubAgentCatalogTest.instructionsAreReadOnDemand` |
| `:190 TestBuildSpec` / `:212 TestBuildSpecFailsWhenDefinitionUnreadable` | `SubAgentFlowTest.modelCallsSubAgentAndGetsARceipt` + `SubAgentCatalogTest.brokenDefinitionsAreSkippedWithDiagnostics` |
| `:226 TestToolsAsSingleString` | `SubAgentCatalogTest.modelOverridePresent`（frontmatter 解析） |
| `:237 TestToolName` | `SubAgentToolTest.toolShape` / `SubAgentToolTest.catalogCreatesOneToolPerSubAgent` |
| `agent/subagent/registry_test.go:36 TestChildRegistryIntersects` | `SubAgentToolsTest.whitelistIsIntersectedWithTheParentSurface` |
| `:52 TestChildRegistryEmptyWhitelistInheritsParent` | `SubAgentToolsTest.emptyDeclarationInheritsTheParentSurface` |
| `:64 TestChildRegistryReusesParentTools` | `SubAgentToolsTest.childRegistryKeepsOnlyAllowedTools` |
| `:78 TestChildRegistryDropsUnknownTools` | `SubAgentToolsTest.childRegistryReportsDroppedDeclarations` |
| `:90 TestChildRegistryWithoutParent` | `SubAgentToolsTest.emptyParentSurfaceFallsBackToTheDeclaration` |
| `:101 TestChildRegistryExcludesSubagentTools` / `:122 TestChildRegistryExcludesToolSearch` / `:143 TestChildRegistryExcludesEvenWithEmptyWhitelist` | `SubAgentToolsTest.childRegistryAlwaysExcludesDelegationAndSearch` |
| `agent/subagent/resolve_test.go:9 TestResolveToolsIntersectsWithParent` | `SubAgentToolsTest.whitelistIsIntersectedWithTheParentSurface` |
| `:17 TestResolveToolsDropsToolsParentCannotSee` | `SubAgentToolsTest.childRegistryReportsDroppedDeclarations` |
| `:25 TestResolveToolsEmptyDeclaredInheritsParent` | `SubAgentToolsTest.emptyDeclarationInheritsTheParentSurface` |
| `:33 TestResolveToolsWithoutParentVisible` | `SubAgentToolsTest.emptyParentSurfaceFallsBackToTheDeclaration` |
| `:41 TestCheckModelRejectsUnknownModel` / `:54 TestCheckModelAcceptsKnownModel` / `:63 TestCheckModelSkipsWhenUnset` | `SubAgentCatalogTest.modelOverrideMustExist` / `modelOverridePresent` + `SubAgentFlowTest.modelOverrideUsesTheCatalogEntry` |
| `agent/subagent/render_test.go:10 TestRenderIsDeterministic` | `SubAgentCatalogTest.catalogRender` |
| `:30 TestRenderEmptyWhenNoSubagent` | `SubAgentCatalogTest.emptyCatalog` + `SubAgentFlowTest.withoutSubAgentsNothingChanges` |
| `:38 TestRenderShowsToolWhitelist` | `SubAgentCatalogTest.catalogRender` |
| `:50 TestRenderDegradesUnderBudget` / `:72 TestRenderFallsBackToMinimal` / `:87 TestRenderEmptyWhenBudgetTooSmall` | `SubAgentCatalogTest.catalogBudgetDegradesAndDiscloses` |
| `agent/subagent/runner_test.go:9 TestResultValidateStatus` / `:26 TestResultValidateRequiresError` / `:34 TestResultValidateFindings` | `SubAgentToolsTest.validationRules` / `unknownStatusBecomesError` |
| `:42 TestRunnerFunc` | `SubAgentToolTest.runnerFailureBecomesAnErrorReceipt`（Java 侧 runner 是宿主接缝） |
| `agent/subagent/tool_test.go:24 TestToolNameAndDescription` | `SubAgentToolTest.toolShape` |
| `:43 TestToolParameters` | `SubAgentToolTest.parameterSchema` |
| `:71 TestToolIsExclusiveByDefault` | `SubAgentToolTest.toolIsExclusiveByDefault` |
| `:80 TestToolExecuteReturnsReceipt` | `SubAgentToolTest.catalogCreatesOneToolPerSubAgent` + `SubAgentFlowTest.modelCallsSubAgentAndGetsARceipt` || `:118 TestToolFailureIsNormalResult` | `SubAgentToolTest.runnerFailureBecomesAnErrorReceipt` + `SubAgentFlowTest.failingSubAgentStillEndsTheParentTurn` |
| `:138 TestToolRejectsInvalidReceipt` | `SubAgentToolTest.invalidReceiptBecomesAnErrorReceipt` + `SubAgentToolsTest.unknownStatusBecomesError` |
| `:155 TestToolRejectsBadArgs` | `SubAgentToolTest.malformedArgsAreAFrameworkError` / `emptyTaskIsAFrameworkError` |
| `:172 TestToolTimeout` / `:197 TestToolTimeoutEvenIfRunnerIgnoresContext` | `SubAgentToolTest.timeoutBecomesATimeoutReceipt` |
| `:218 TestParentCancelCancelsSubagent` | `SubAgentToolTest.cancellationStopsTheRunner` |
| `:252 TestToolWithoutRunner` | `SubAgentToolTest.missingRunnerIsAFrameworkError` |
| `mos_subagent_test.go:104 TestSubAgentsWiredIntoPromptAndTool` | `SubAgentFlowTest.subAgentRegistersToolAndCatalogSection` |
| `:132 TestNoSubAgentsNoBehaviorChange` | `SubAgentFlowTest.withoutSubAgentsNothingChanges` |
| `:151 TestSubAgentEndToEnd` | `SubAgentFlowTest.modelCallsSubAgentAndGetsARceipt` |
| `:233 TestSubAgentCallStaysInParentSession` | `SubAgentFlowTest.subAgentRunsInItsOwnSession`（**方向相反**：Java 侧子会话**独立**，见 SI-43 与 GO-DIFF §3.9 说明） |
| `:282 TestSubAgentCannotUseToolsOutsideWhitelist` | `SubAgentFlowTest.subAgentToolSurfaceIsNarrowed` + `SubAgentToolsTest.childRegistryAlwaysExcludesDelegationAndSearch` |
| `:350 TestSubAgentUnknownModelFailsAssembly` | `SubAgentCatalogTest.modelOverrideMustExist` |
| `:367 TestSubAgentModelOverrideUsesCatalogModel` | `SubAgentFlowTest.modelOverrideUsesTheCatalogEntry` |
| `:415 TestSubAgentUsesIndependentSession` | `SubAgentFlowTest.subAgentRunsInItsOwnSession` |

#### 3.9.2 续跑（#2）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `agent/driver/react/resume_test.go:86 TestResumeRunsHangingCallAndFinishesTurn` | `ResumeTest.resumeKeepsTheOriginalTurnAndReplaysThePayloadVerbatim` |
| `:148 TestResumeOnlySelectedCall` | `ResumeTest.resumeOnlyRunsTheSelectedCallIds` |
| `:182 TestResumeAllHangingCalls` | `ResumeTest.resumeKeepsTheOriginalTurnAndReplaysThePayloadVerbatim` |
| `:207 TestResumeWithoutInterruptedTurn` | `ResumeTest.resumeWithoutAnInterruptedTurnFails` |
| `:225 TestResumeWithoutHangingCallsKeepsTurnPending` | `ResumeTest.resumeWithoutHangingCallsKeepsTheTurnHanging` |
| `:246 TestResumeSkipsCompletedCalls` | `ResumeTest.resumeOnlyRunsTheSelectedCallIds` |
| `:271 TestResumeReusesPriorApproval` | `ResumeTest.resumeReusesThePriorApprovalInsteadOfAskingAgain` |
| `mos_resume_test.go:74 TestResumeContinuesInterruptedTurn` | `ResumeTest.resumeAfterJsonlRestoreFindsTheHangingCalls` |
| `:120 TestResumeWithoutHangingCallsReportsError` | `ResumeTest.resumeWithoutHangingCallsKeepsTheTurnHanging`（Java 口径 = checked `NothingToResumeException`，见 SI-45） |
| `:139 TestNoAutoResumeByDefault` | `ResumeTest.resumeWithoutAnInterruptedTurnFails` + `ResumeTest.resumeKeepsTheOriginalTurnAndReplaysThePayloadVerbatim`（**不自动拾取**：必须显式调用 `resume(...)`） |
| `:164 TestResumeReusesPriorApprovalDecision` | `ResumeTest.resumeReusesThePriorApprovalInsteadOfAskingAgain` |

#### 3.9.3 审批先验（#3）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `prior_test.go:22 TestApprovalPriorMatchesHangingCall` | `SessionApprovalPriorTest.hitsOnSameTurnSameCallSameToolWhileStillHanging` |
| `:49 TestApprovalPriorSkipsFinishedCall` | `SessionApprovalPriorTest.missesWhenTheCallAlreadyHasAResult` |
| `:72 TestApprovalPriorRequiresSameTurnAndCall` | `SessionApprovalPriorTest.missesWhenTheTurnDiffers` / `missesWhenTheCallIdDiffers` / `missesWhenTheToolNameDiffers` |
| `:94 TestApprovalPriorMissWithoutDecision` | `SessionApprovalPriorTest.missesWhenThereIsNoDecisionAtAll` |
| `agent/driver/react/react_prior_test.go:19 TestPriorHitSkipsAsking` | `ApprovalPriorTest.priorHitDoesNotAskTheHumanAgain` |
| `:80 TestPriorMissStillAsks` | `ApprovalPriorTest.priorMissAsksTheHumanAsUsual` |

> **Java 侧新增的严格项**（Go 未有对应测试名，因 Go 把 `reusedFrom` 写进载荷）：
> `ApprovalPriorTest.priorHitStillWritesAPairedAudit`（复用也写完整审计对）、
> `withoutAnApprovalChannelThePriorCannotRescueTheCall`（未装通道 → fail-closed）、
> `aReusedRejectionStillRejects`（`REJECTED` 可复用但仍拒绝）、
> `aNonReusablePriorIsIgnored`（`CANCELLED` / `UNAVAILABLE` 不可复用）。

#### 3.9.4 会话派生读（#4）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `session/derive_test.go:24 TestPendingApprovalsDerivedFromLog` | `SessionDerivationTest.pendingApprovalsListsAskedButNotDecided` |
| `:43 TestPendingApprovalsPairingDoesNotCross` | `SessionDerivationTest.pendingApprovalsListsAskedButNotDecided`（按 id 配对不跨） |
| `:56 TestApprovalOutcomeByID` | `SessionDerivationTest.approvalOutcomeIsLookedUpById` |
| `:74 TestApprovalDecisionMatchesTurnAndCallID` | `SessionDerivationTest.approvalDecisionMatchesTurnAndCallIdAndTakesTheLastOne` |
| `:98 TestHangingCallsDerivedFromLog` | `SessionDerivationTest.hangingCallsAreCallsWithoutResults` |
| `:124 TestPendingApprovalMatchesHangingCall` | `SessionDerivationTest.hangingCallsAreCallsWithoutResults` + `pendingApprovalsListsAskedButNotDecided` |
| `:145 TestInterruptedTurnDerivedFromLog` | `SessionDerivationTest.interruptedTurnReportsTheLastUnfinishedTurn` |
| `:179 TestDerivedReadsDoNotMutateLog` | `SessionDerivationTest.derivationDoesNotMutateTheLog` |

> **Java 侧新增的严格项**：`SessionDerivationTest.callsWithoutIdAreNotHanging`（空 call id 不算悬空）、
> `aCallIdAnsweredOnceIsNotHangingAnyMore`（同 id 重复调用不再算悬空）。

#### 3.9.5 协议注册表（#6）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `llm/registry_test.go:10 TestRegistryRegisterCreateNames` | `ModelRegistryTest.aRegisteredProviderIsCreatedByName` / `theNamesAreSorted` |
| `:51 TestRegistryEmpty` | `ModelRegistryTest.anEmptyNameIsRejected` + `theNamesAreSorted` |

> **Java 侧新增的严格项**：`ModelRegistryTest.anUnknownProviderFailsClosed`（未知名字 fail-closed 并列已注册名）、
> `aFactoryFailureIsNotSwallowed`、`registeringTheSameNameTwiceOverridesTheFactory`、
> `aNullConfigIsTreatedAsEmpty`、`theRegistryIsUsableFromSeveralThreads`。

#### 3.9.6 提示词构建器（#8）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `agent/prompt/prompt_test.go:32 TestRenderOrdersDeterministically` | `PromptSectionsTest`（中心顺序用例）+ `PromptBuilderTest.theInjectedBuilderBecomesTheSystemPrompt` |
| `:59 TestAddRejectsDuplicateAndEmpty` | `PromptBuilderTest.aHostsOwnSkillsSectionIsNotDuplicated`（同名段冲突报错） |
| `:76 TestSetUpserts` | `PromptSectionsTest`（既有 set 口径） |
| `:92 TestCompleteSemantics` | **有意差异**：Go 的 `Complete` 是「整段替换整个提示词」，Java 的 `complete` 是「换某段内容」（GO-DIFF 8c） |
| `:118 TestCloneIndependence` | `PromptBuilderTest.theHostsBuilderIsNotMutated` |
| `:138 TestDefaultGolden` | `PromptSectionsTest`（缺省渲染用例） |
| `:148 TestAddMCPServers` / `:170 TestAddSubAgents` / `:202 TestAddToolGuidelines` | `PromptSectionsTest`（中心顺序用例）+ `PromptBuilderTest.theSkillsSectionLandsInTheInjectedBuilder` |
| `:241 TestAddToolGuidelinesRejectsCollision` | `PromptBuilderTest.aHostsOwnSkillsSectionIsNotDuplicated` |
| `:256 TestAddProjectContextLandsBetweenPolicyAndTools` | `PromptBuilderTest.theInjectedBuilderBecomesTheSystemPrompt`（`context` 段落在中心顺序位） |
| `:276 TestAddProjectContextEmptyTextAddsNothing` | `PromptSectionsTest`（空内容不添段） |
| `:286 TestAddProjectContextRejectsDuplicate` | `PromptBuilderTest.aHostsOwnSkillsSectionIsNotDuplicated` |

#### 3.9.7 取消原因（#11）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `cancel_cause_test.go:32 TestRunWithCancelCauseIsDistinguishable` | `CancellationTokenCauseTest.cancelWithReasonKeepsTheReason` + `throwIfCancelledReportsTheReason` |
| `:72 TestRunWithCauseFallbackIsDistinguishable` | `CancellationTokenCauseTest.firstReasonWins` / `cancelWithoutReasonDoesNotClearAnExistingReason` |

> **Java 侧新增的严格项**：`notCancelledHasNoCause`（未取消 → `cause()` 为 `null`）、
> `cancelWithoutReasonLeavesCauseNull`（无因取消保留原语义）、`callbacksStillRunOnce`、`noneTokenNeverHasACause`。

#### 3.9.8 运行期可调参数（#12）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `mos_test.go:173 TestRuntimeKnobs`（`SetConcurrency(7)` / `SetMaxDecisionRounds(9)`，并断言 `Describe()` 反映新值） | `RuntimeKnobsTest.concurrencyIsHotSwappable` / `maxDecisionRoundsIsReadAtTheStartOfEveryRound` |

> **Java 侧新增的严格项**：`RuntimeKnobsTest.paramRulesAreHotSwappable`（参数级规则热替换）、
> `setRulesWithoutInstalledParamPolicyFailsClosed`（未装配 → fail-closed）、
> `cancellationReasonReachesTurnEndAndTheThrownError`（#11 的端到端落点）。

#### 3.9.9 本地缓存装饰器（#13）

| Go 原测试名（逐字） | Java 对应用例 |
|---|---|
| `llm/wrap_test.go:159 TestWithCache` | `CachingChatModelTest.theSameRequestHitsTheCache` |
| `:240 TestWithCacheStreamPassthrough` | `CachingChatModelTest.aStreamCallIsNotCached` |
| `:256 TestCacheTTLExpiry` | `CachingChatModelTest.anExpiredEntryIsNotServed` / `aZeroTtlNeverExpires` |

> **Java 侧新增的严格项**：`aDifferentRequestIsNotServedFromTheCache`、
> `theToolsArePartOfTheCacheKey`（工具清单进键；Java 侧的键覆盖面比 Go 更严，见 SI-50）、
> `aFailureIsNotCached`、`aCancelledCallIsNotServedFromTheCache`（取消优先于命中）、
> `theCacheIsUsableFromSeveralThreads`、`clearingTheCacheForcesAFreshCall`。

#### 3.9.10 示例程序集（#10）

| Go 侧 | Java 对应用例 |
|---|---|
| `examples/{agents,chat,mcp,minimal,recovery,skills,subagent}`（**Go 侧示例是程序，不带单元测试**；只有 `examples/chat` 目录里给“练手项目”用的 `guard_test.go` / `approval_test.go` / `lab/main_test.go`） | `examples/*/src/test/**`（28 条）+ `StubModelTest`（5 条）：每个示例都真跑一遍装配 + 循环（脚本化模型替身，**不碰网络**） |

> **口径差**：Go 的示例靠真模型跑（需要 API Key），Java 的示例用例改用**脚本化替身**（`examples/testkit` 的 `StubModel`）→ 可进 CI；登记为 SI-51。

---

## 4. 待补事项（诚实清单）

| 项 | 状态 | 怎么补 |
|---|---|---|
| Go 侧原测试名逐条抄录 | ✅ 已完成（2026-11-06，Go 只读授权后）；**本批新增机制的原测试名已补抄**（§3.7）；**#5/#7/#9 差异批的原测试名已补抄**（§3.8）；**#1/#2/#3/#4/#6/#8/#10/#11/#12/#13 差异批的原测试名已补抄**（§3.9） | 复核命令：`grep -rn "^func Test" <Go 包>` |
| **Go↔Java 差异的补齐**（13 项） | ✅ **已全部补齐**（2026-11-08）：生产代码 + 定向用例 + 文档登记（[`GO-DIFF.md`](GO-DIFF.md) §2 全绿表 / [`API.md`](API.md) §3.15 / [`SELF-INVENTED.md`](SELF-INVENTED.md) SI-43…SI-51） | 守护用例见 §3.9；**剩余**：人工逐行签字（§4 最后一行）+ 跨语言回放（不是机器能替代的） |
| **examples 的独立构建与依赖纪律** | ✅ **已验证**（2026-11-08）：`-Pexamples clean verify` 与 `-Pmcp -Pexamples clean verify` 均 BUILD SUCCESS；examples 用例 **28 条** + `StubModelTest` **5 条** 全绿；8 个模块的 `dependency:analyze` **无告警** | 命令：`./mvnw -Pmcp -Pexamples -pl examples/testkit,examples/minimal,examples/chat,examples/agents,examples/skills,examples/subagent,examples/recovery,examples/mcp -am dependency:analyze`；踩坑：testkit 的脚本用完会抛 `IllegalStateException`（不是 `IndexOutOfBoundsException`） |
| 工具名与 Go 侧 builtin 的逐字对照 | ✅ 已核对（**Go 13 / Java 14 全集**：`skill` 条件注册，不是抽样） | 差异见 §3.1；参数差异见 §3.2 |
| 工具面对齐裁决 | ✅ **已裁决并落地**（B13-1 / B13-2 / B13-3 实施；B13-4 / B13-5 登记为有意差异） | 见 §3.2 结论表与 `REVIEW-SHEET.md` §5 B13 |
| Go 的 skill 机制缺口 | ✅ **已补齐**（J7：`mos4j-tool` 的 `tool/skill` 包 + `skill` 条件工具 + 两个 skill 事件 + `SkillAudit`；清单已升版至 80/71） | 见 §3.4 / §3.5；测试：`SkillCatalogTest`(33) + `SkillToolTest`(15) + `SkillFlowTest`(9) + `SessionSkillAuditTest`(5) + `EventJsonTest`(+2) + `PromptSectionsTest`(现 16) + `Mos4jTest`(+3) + `Mos4jAutoConfigurationTest`(+3) |
| MCP 的**结果投影口径**（Go `tool/mcp/client.go` 的 `project`） | ✅ **已补齐**（2026-11-07）：占位诊断**按类型分开**（image / audio / resource / resource_link / 未知）；原始 JSON 上限 2048（对齐 `maxOpaqueJSON`）；新增 `opaqueParts()` / `textContent()`；参数非 JSON 对象 / 上游无结果 → fail-closed | 守护：`McpResultProjectionTest`(9) + `McpToolAdapterTest.nonTextContentBecomesAPlaceholder`；口径见 §3.7 与 `MCP-STATUS.md` §2.1 |
| MCP 的 **deferred 工具同轮再声明** | ✅ **已核实并补上证明**（2026-11-07）：`ReActDriver` 每轮决策前重取工具清单（与 Go `react.go` 同口径）→ 行为本就成立；本批补 MCP 层用例 | 守护：`McpDeferredVisibilityTest`(2) + 既有 `DeferredFlowTest.activationTakesEffectNextRound` |
| MCP 的 **streamable-http 真服务器 E2E** | ✅ **已补齐**（2026-11-07）：官方 SDK 服务端只有 stdio 与 HttpServlet 两族 → 测试作用域嵌入式 Jetty 12.0.36，用官方 SDK 的 servlet 传输起**真的** MCP 服务器 | 守护：`McpHttpServerE2ETest`(3)；踩坑：Jetty 版本要整族一致（`jetty-ee10-servlet` 12.1.x 传递的是 12.0.x 的 core，混用会 `NoSuchMethodError`） |
| 技能的 **CLI 入口** 与 **并发 reload 口径** | ✅ **已补齐**（2026-11-07）：CLI `--skills DIR`（可重复，自创参数面 → SI-38）；并发 reload 压测（**自选加固**，Go 侧无并发用例） | 守护：`MainTest.skillsDir_isWiredIntoTheSession`(1) + `SkillCatalogConcurrencyTest`(1) |
| Go 的 `http` 工具缺口 | ✅ **已撤销**（2026-11-06 当日更正：`http` 不是模型可见工具） | 见 §3.1 更正说明 |
| 冻结清单与 Go 源码的漂移 | ✅ 已核对（4 处：3 个缺失字段 + 1 个 options 过期） | 名称不受影响；见 §3.5 |
| Anthropic / Gemini 的 M5 用例 | ✅ 已于 J6 落地并回填 | — |
| Seatbelt **profile 语法**的真机验证 | ✅ **已验证**（2026-11-07）：`sandbox-exec -p '<profile>' /usr/bin/true` 对正确 profile 返回 **exit 71**（语法过了，apply 被宿主策略拒）+ `unbound variable` 不出现；故意写坏的 profile 返回 **exit 65 / `unbound variable`**（反例用例保证判据有效） | 已固化为两条自动化用例（`seatbeltProfileIsAcceptedByTheKernelParser` / `seatbeltProfileSyntaxErrorsAreDetectable`） |
| Seatbelt **强制效果**的真机验证 | ⏸ 当前环境不允许应用 Seatbelt（`sandbox_apply: Operation not permitted`，exit 71），测试如实标为**跳过**而不是假装通过 | 在允许应用 Seatbelt 的 macOS 主机上重跑 `SandboxBoundaryTest.seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt`（该用例已写好，会自动从「跳过」变成「通过」） |
| Linux Landlock 强制效果的验证（J8） | ⏸ **未在 Linux 上跑过**（开发机是 Darwin arm64）：`LandlockScopeTest` 5 条在非 Linux 上**整类跳过**；且**默认不装配**（`supported()` 只认已验证的平台），Linux 宿主行为不变 | 任意 Linux ≥ 5.13（`CONFIG_SECURITY_LANDLOCK=y`）：`./mvnw -pl mos4j-runtime -am test` 即自动执行；建议加 `runner.os == 'Linux'` 的 CI job。**按用户口径本轮暂缓** |
| Go 的**动态 Scope** 未对位（J8） | ✅ **已对位（Q2′，2026-11-07）**：`LocalRunner.pathScopeSupplier`（每次调用现取写根）+ `PathScopeFactory`（按平台现造 scope），SDK 把 `RootSet` 的活根接进 supplier → 会话里 `grant_access` 授权的新目录，**shell 跟着能写**。Go 的**请求期** `Request.PathScope` **有意不补**（Go 自己的 shell 工具也不传它）→ 登记为有意差异（D-J8-7），不是缺口 | 守护用例：`PathScopeRuntimeTest.dynamicWriteRootsAreFetchedOnEveryCall` / `emptyWriteRootsFailClosedInsteadOfRunningUnbounded` / `aFailingScopeSourceFailsClosed`、`DynamicPathScopeTest.aNewlyGrantedRootReachesTheNextShellCall` / `theWriteRootsAreFetchedPerRunNotPerAssembly`；见 `J8-STATUS.md` D-J8-6 / D-J8-7 |
| 路径强制的**显式出口**与**装配期探测**（Q1/Q5） | ✅ **已落地（2026-11-07）**：门面 `pathScope(PathScope)` / `noPathScope()` / `pathScopeProbe(PathScopeProbe)`；探测不通 → **装配期报错**并给两条出路；探针可注入（SDK + Starter 的 `PathScopeProbe` Bean）；Starter 属性 `mos4j.path-scope=auto\|disabled`；CLI 开关 `--no-path-scope`；Describe 区分「平台没有后端」与「宿主显式关闭」 | 守护用例：`Mos4jTest.anUnavailablePathScopeFailsFastAtAssembly` / `anAvailableProbeInstallsThePathScope` / `noPathScopeIsDeclaredInTheSnapshot` / `theDefaultPathScopeIsDeclaredAsInstalledWhereSupported`、`PathScopeRuntimeTest.theAbsenceReasonIsReported` / `aDynamicScopeIsReportedAsInstalled`、`Mos4jStarterKnobsTest.pathScopeDisabledIsHonoredAndDeclared`；见 `SELF-INVENTED.md` SI-32 / SI-33 |
| 协议逃生阀的**门面可达性**（Q4） | ✅ **已落地（2026-11-07）**：`Mos4j.Builder.requireFinish(Boolean)`（只对 Gemini 生效；用在其他协议或宿主自带模型上 **fail-fast**）+ `Mos4j.Builder.maxTokens(Integer)`（模型级缺省，走 `sdk → Config → ReActPlanner → Request`）；Starter 属性 `mos4j.require-finish` / `mos4j.max-tokens` | 守护用例：`Mos4jProtocolKnobsTest`（5 条）、`ReActDriverTest.theConfiguredMaxTokensReachesEveryRequest` / `withoutTheSettingMaxTokensStaysUnset`、`Mos4jStarterKnobsTest.maxTokensReachesTheWire` |
| Windows 路径强制后端（J8） | ❌ **完全没做**（`PathScopes.create` 抛 `UnsupportedOperationException`）；且 Windows 上 shell 工具当前不可用（`/bin/sh` 与 `ulimit` 是 Unix 假设） | 先有 Windows 验证环境，再拆 `LocalRunner.SHELL` 与 `ResourceLimits.shellSnippet()`；8–12 人日量级。见 `J8-STATUS.md` D-J8-5 |
| **policy 可见性与运行时守卫同源**（2026-11-07） | ✅ **已落地**：`PolicySlot`（`AtomicReference<ToolNamePolicy>` 单一状态源）同时服务可见性与守卫；`Agent.setPolicy(...)` 热替换只换 policy 层，不覆盖 MCP hidden 槽 | 守护用例：`PolicyVisibilityTest`（7 条）、`McpPolicyIntegrationTest`（3 条）；见 `SELF-INVENTED.md` SI-34 |
| **ChatModel 装饰器**（Logging / Timeout / Fallback） | ✅ **已落地**（`Cache` 按用户口径后置）：统一接口 + **显式嵌套**，不引入隐式全局中间件链；超时协作式、外部取消优先；降级只在主模型**运行时失败**时发生（取消不降级） | 守护用例：`ChatModelDecoratorsTest`（11 条） |
| **运行期模型目录** | ✅ **已落地**：`ModelOption` / `SwitchableChatModel` / `ModelCatalog`（重复 ID 与未知 ID fail-closed）；切换**下一轮生效**；凭据不进快照与公开描述 | 守护用例：`ModelCatalogTest`（9 条） |
| **Describe 补充 / `Agent.close()` / `mcp_servers` 分节** | ✅ **已落地**：`DescribeContributor` 槽（工具层 → 装配层写事实，SPI 下沉 `net.hegq.mos4j.core`；贡献者失败转 caveat）+ `Config.closer(...)` + `Agent implements AutoCloseable`（幂等 / 逐个关 / 关不掉不拖垮其余）+ `PromptSections` 的 `mcp_servers` 中心位置 | 守护用例：`AgentCloseTest`（4 条）、`McpKitWiringTest`（4 条）、`PromptSectionsTest.mcpServersGoesBetweenSkillsAndInstructions`；见 `SELF-INVENTED.md` SI-35 / SI-36 |
| **MCP M3**（认证 / 资源工具 / policy 联动 / 分节 / Describe / Close） | ✅ **已落地**：`McpTokenProvider`（每次请求取一次）+ `McpError.Kind.AUTH`（401/403 归认证类）+ 三个共享资源工具 + policy 联动 + 分节 + 快照 + 关闭；**Starter 接入有意不做** | 守护用例：`McpAuthTest`（5 条）、`McpResourceToolsTest`（8 条）、`McpPolicyIntegrationTest`（3 条）、`McpKitWiringTest`（4 条）、`ToolContractTest.mcpToolsAreNotInTheFrozenList` / `mcpResourceToolsAreNotInTheFrozenList`；见 `MCP-STATUS.md` |
| 本批新增机制在 §1 台账的 **Go 侧原测试列** | ✅ **已补抄**（2026-11-08，§3.9）：policy 可见性槽、装饰器、模型目录、Describe 补充与关闭语义、子代理 / 续跑 / 审批先验 / 派生读 / 协议注册表 / 提示词构建器 / 取消原因 / 运行期参数 / 本地缓存的 Go 原测试名均已逐字抄录 | 复核命令：`grep -rn "^func Test" <Go 包>`（只抄真实存在的函数名） |
| **对外 Version API**（2026-11-07） | ✅ **已落地**：`net.hegq.mos4j.core.Version`（常量 + `version()`）+ `Mos4j.version()` + CLI `--version`；**版本号真源 = 父 POM 的 `<version>`**，由 `VersionTest` 机械对齐 | 守护用例：`VersionTest`（3 条：格式 / 与父 POM 一致 / 访问器一致）、`MainTest.versionExitsZeroAndPrintsTheVersion`；发布清单见 `docs/release.md` |
| **冻结清单的绝对基线与口径**（2026-11-07） | ✅ **已钉死**：80 条 / 71 名 / 名字集合摘要 `3d35911d…`；文档副本与 classpath 基线逐字节一致 | 守护用例：`FieldNameContractTest.frozenListMatchesTheApprovedBaseline` / `theDocumentCopyIsByteIdenticalToTheClasspathBaseline`；**口径**：`3d35911d…` = 唯一名字集合按字典序用 `\n` 拼接后的摘要（**不是文件字节摘要**，后者是 `aeb48e08…`） |
| **发布配置**（签名 / 发布地址 / 源码附件 / SCM / 许可证） | 🟡 **部分就绪**：`release` profile 已配源码 / javadoc 附件 + GPG 签名，`internal-publish` profile 已配**内部私服**的 `distributionManagement`（地址与仓库 id 由 `-D` 注入，**不写死环境信息**；Maven Central 按 2026-11-07 用户裁决**暂缓**），POM 已补 `<scm>` 与 `<licenses>`（**MIT**）；**仍缺**：私服地址与账号、GPG 密钥（均不入库） | 见 `docs/release.md` §4 与 §5；发布前还差**人工评审签字**与 **Seatbelt 真机验收** |
| 「Java 用例断言的 == Go 那条断言」 | ⏸ 机器只能证同名测试存在 | 人工逐行读 Go 原测试与 Java 用例，在 [`REVIEW-SHEET.md`](REVIEW-SHEET.md) §1/§2 签字 |
