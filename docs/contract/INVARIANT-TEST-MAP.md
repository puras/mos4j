# 不变式测试映射表（J0 · T0.9）

> **用途**：保证七条不变式 × 十个机制**每一条都有 Java 测试承接**，无空项。G2 门的机械判定就是本表无空项。
> **人工评审**：本表是机械的「有无」；「语义对不对」由人判，在 `docs/contract/REVIEW-SHEET.md` 逐行签字。
> **列说明**：「Java 测试」列在 J0 时是规划名（`@Disabled` 骨架随 T0.10 建好），实现里程碑点亮；点亮后此表更新为真实测试类名。**2026-11-05（J1）已把点亮的行换成真实类名 + 方法名**；**2026-11-05（J2）把安全纵深各行换成真实类名 + 方法名**；**2026-11-05（J3）把文件与沙箱各行换成真实类名 + 方法名**；**2026-11-05（J4）把上下文工程各行换成真实类名 + 方法名**；未点亮的行仍是规划名。

| 编号 | 条目 | Java 测试（真实类名 + 方法名） | 所在模块 | 点亮里程碑 |
|---|---|---|---|---|
| I1-1 | fail-closed：未装配审批 → 拒绝 | `ExecPathSecurityTest.l3_4_askWithoutChannelIsDenied` + `SecurityFlowTest.missingApprovalChannelFailsClosed` | mos4j-execpath / mos4j-driver | ✅ J2（L3-4） |
| I1-2 | fail-closed：Guard 判定异常 / null → 拒绝 | `GuardBusTest.guardExceptionIsDenied` + `GuardBusTest.nullDecisionIsDenied` | mos4j-hook | ✅ J2 |
| I1-3 | fail-closed：沙箱未启用时 shell 拒绝裸奔 | `ShellToolTest.shellToolRequiresARunner`（无 Runner 造不出工具）+ `DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled`（未装配 = 工具不注册 + 快照显式声明） | mos4j-tool / mos4j-driver | ✅ J3 |
| I2-1 | append-only：日志不可改写 | `L2ScenariosTest.logIsNotRewritable` + `SessionTest.eventsReturnsDefensiveCopy` | mos4j-session | ✅ J1（L2-3） |
| I2-2 | 崩溃洞保留 + 投影自愈 | `ProjectionTest`（L2-5/6/7/8）+ `L2ScenariosTest` | mos4j-session | ✅ J1 |
| I3-1 | 错误只可加重不可减轻 | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` + `l3_8_postHookCanWorsenASuccess` | mos4j-execpath | ✅ J2 |
| I4-1 | 预算触发与遮蔽 | `CompactionBudgetTest`（7 条：超预算触发 / 未超不触发 / 未配置窗口永不触发 / 遮蔽不改日志 / 摘要用量计入 / 空摘要不遮蔽 / 摘要异常不遮蔽） | mos4j-context | ✅ J4（L2-9/10） |
| I4-2 | 切割点合法性 | `CompactionCutPointTest`（3 条：切割点回退到 assistant / 压缩后投影始终合法 / 保留段以 assistant 开头时不动） | mos4j-context | ✅ J4（L2-11） |
| I4-3 | 审批事件不占投影 | `L2ScenariosTest.approvalEventsAreAuditOnly`（L2-13）+ `SessionApprovalAuditTest.approvalEventsDoNotEnterTheProjection` | mos4j-session | ✅ J1 / ✅ J2 |
| I5-1 | 钩子无法绕过守卫（结构性） | `DualTrackStructureTest.guardAndHookEventsAreDistinctTypes` + `ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks` | mos4j-hook / mos4j-execpath | ✅ J2 |
| I5-2 | 载荷冻结 | `ExecPathSecurityTest.l3_3_rewritingNameIsFrozenViolation` / `l3_3_rewritingArgsIsFrozenViolation` / `l3_3_rewritingCallIdIsFrozenViolation` / `l3_3_frozenToolIsNotExecuted` | mos4j-execpath | ✅ J2 |
| I5-3 | 守卫只跑一次 | `ExecPathSecurityTest.l3_9_guardRunsExactlyOncePerCall` | mos4j-execpath | ✅ J2 |
| I6-1 | 可选能力零开销降级 | `ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled` / `emptyBusesBehaveLikeNotInstalled`（J2 部分）+ `DescribeSandboxTest.describeSaysWhenFileAndSandboxAreNotInstalled`（J3：fs / sandbox 未装配时工具不注册、快照显式声明）+ `ContextFlowTest.withoutLimiterTheOutputIsUntouched`（J4：未装配限流器则输出逐字原样进日志） | mos4j-execpath / mos4j-driver / mos4j-sandbox / mos4j-context | ✅ J2 / ✅ J3 / ✅ J4 |
| I7-1 | 依赖纪律 | CI 门 C2/C6（非测试，机械门）：Enforcer 白名单 + 源码扫描 + `dependency:analyze` 硬门 | build/CI | ✅ J0 起常驻 / ✅ J2（analyze 硬门） |
| I7-2 | 模块边界（反向依赖 / 日志与协议同形） | `BoundaryRulesTest`（7 条：能力层不 import 驱动层/装配层 / 驱动层不 import 装配层 / pom 依赖方向 / 协议层不依赖日志层 / 共享值类型唯一定义 / 投影类身份 / 词汇表只在 core）+ CI 门 C3 | mos4j-sdk（测试）/ build/CI | ✅ 加固批次（负向验证：故意违规→变红） |
| M1 | 载荷冻结三字段逐一改写 | `ExecPathSecurityTest.l3_3_*`（三个字段各一条） | mos4j-execpath | ✅ J2 |
| M2 | 回填序=调用序 | `ExecPathTest.resultsFollowCallOrderNotCompletionOrder` | mos4j-execpath | ✅ J1（执行通道层） |
| M3 | finalize 单向 | `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` | mos4j-execpath | ✅ J2 |
| M4 | 投影自愈四态 | `ProjectionTest`（孤儿回执 / 序位 / seq 继承 / 与遮蔽同生共死） | mos4j-session | ✅ J1 |
| M5 | 流完整性判定 | `L1OpenAiScenariosTest`（O5a 真截断 / O5b 只发 `[DONE]`）+ `L1AnthropicScenariosTest`（a4/a6 无结束信号→截断；a5 只有 message_stop 也完整，空 finishReason 合法）+ `L1GeminiScenariosTest`（g5a/g5b finishReason 即结束标记 + require_finish 逃生阀） | mos4j-llm | ✅ J1（OpenAI）/ ✅ J6（Anthropic·Gemini） |
| M6 | 用量口径（分项和=总计 + FreshInput） | `UsageJsonTest` + `L1OpenAiScenariosTest`（O4）+ `L1AnthropicScenariosTest`（a7/a8/a9/a10：两侧合并 delta 赢 + 分项和）+ `L1GeminiScenariosTest`（g1/g_nonStream：直读 + 分项和） | mos4j-llm | ✅ J1（OpenAI）/ ✅ J6（Anthropic·Gemini） |
| M7 | 写前观察三态 + edit 门 | `ObserveGateTest`（11 条：L3-15/16/17 三态 + 新建免读 + 令牌形态）+ `FileToolsTest.l3_15_*` / `l3_16_editWithoutReadIsDenied` / `l3_17_newFileDoesNotRequireRead` | mos4j-fs / mos4j-tool | ✅ J3 |
| M8 | 压缩遮蔽 / 切割点 / previous-summary | `CompactionBudgetTest`（7 条）+ `CompactionCutPointTest`（3 条）+ `PreviousSummaryTest`（3 条：旧摘要折叠 / 格式冻结 / 摘要器不带工具）+ `ContextFlowTest.compactionRunsBeforeTheModelCall` / `compactionFailureClosesTheTurn`（接线级） | mos4j-context / mos4j-driver | ✅ J4（L2-12） |
| M9 | 路径双校验四态 | `SandboxedFsTest`（18 条：L3-11/12/13/14 四态 + 读也走 Realpath）+ `RootSetTest`（8 条）+ `FileToolsTest.readOutsideRootIsDenied` / `writeThroughSymlinkMiddleComponentIsDenied` | mos4j-fs / mos4j-tool | ✅ J3 |
| M10 | 退出码标记 + 不许绕文案 | `ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence`（文案）+ `ShellToolTest.l3_18_*`（退出码标记） | mos4j-execpath / mos4j-driver / mos4j-tool | ✅ J2（文案）/ ✅ J3（退出码） |
| F-1 | 字段名守护（§4.4 JSON 口径） | `FieldNameContractTest` + `EventJsonTest` + `DtoZeroValueTest` + `EventFieldNameGuardTest` | mos4j-core / mos4j-session | ✅ J0（守护）/ ✅ J1（JSON 口径） |
| C-1 | 取消贯通（Java 侧自创，SI-1） | `ExecPathTest`（cancelledToken_skipsPendingCalls_andKeepsOrder 无空洞 / alreadyCancelledToken_executesNothing / nullToken_isZeroOverhead）+ `RetryingChatModelTest.cancellationDuringBackoff_isTerminal`（退避中取消不再发起）+ `CancellationFlowTest`（5 条：取消先于落盘 / 令牌到达模型调用 / 取消后工具不执行但回执补齐 / 无孤儿 turn / 无令牌即无取消） | mos4j-execpath / mos4j-llm / mos4j-driver | ✅ J6（HTTP 层真取消 ✅ J1）/ ✅ 加固批次（driver→planner→agent 贯通） |
| FZ-1 | 硬化 fuzz（参数容错 / SSE / JSONL 尾行） | `ToolArgsFuzzTest`（500 轮 + 合法 JSON 逐字不变）/ `SseParserFuzzTest`（噪声行 + 400 轮随机）/ `JsonlTruncationFuzzTest`（200 轮字节级截断，恢复数==完整行数） | mos4j-tool / mos4j-llm / mos4j-session | ✅ J6（fuzz 抓到并修复 3 个真实缺陷，见 `J6-STATUS.md` §4） |
| F-2 | 模型可见工具面守护（工具名 + 参数名 + 必填参数） | `ToolContractTest`（4 条：**14 个**工具名冻结（含条件注册的 `skill`）/ 参数名冻结 / 必填参数只由 `@ToolParam(required=true)` 决定 / schema 不含 `title`·`$defs`）+ `FileToolsTest`（edit 批量 9 条新用例）+ `BuiltinSearchToolsTest`（`grep` 的 `glob` 过滤 2 条 + `glob`/`ls` 的 `limit` 2 条）+ `ShellToolTest.descriptionIsEchoedForAudit` + `DeferredToolsTest.toolSearchUsesLimitParameter` + `TruncationTest.headToolsMatchTheGoReference` | mos4j-tool / mos4j-context | ✅ B13（Go 对拍批次，见 `J6-STATUS.md` §8）/ ✅ J7（13 → 14，`skill` 条件注册） |
| F-3 | 技能机制（渐进式披露 · 条件注册 · 审计先于加载） | `SkillCatalogTest`（33 条：发现 / 同名先到先得 / 坏技能诊断 / 清单多级降级 / 正文现读 / `/name` 展开 / 重扫幂等与差异 / 原子替换 / allowed-tools 求交）+ `SkillToolTest`（15 条：仅用户可调用 fail-closed / 审计失败不加载 / mismatch 只诊断 / 每轮换状态）+ `SkillFlowTest`（9 条：条件注册 / 提示词位置 / E2E 激活事件 / 重扫不重渲染）+ `SessionSkillAuditTest`（5 条）+ `EventJsonTest`（+2 条：`skill` 载荷口径）+ `PromptSectionsTest`（现 16 条：`skills` 中心位置与 `insert` 纯函数） | mos4j-tool / mos4j-session / mos4j-context / mos4j-driver / mos4j-sdk | ✅ J7 |

**覆盖核对**：I1（3 项）、I2（2）、I3（1）、I4（3）、I5（3）、I6（1）、I7（机械门 + 边界门）、M1–M10（各 1+）、C-1（取消贯通，J6 + 加固批次）、FZ-1（fuzz，J6）、F-2（工具面守护，B13 + J7）、F-3（技能机制，J7）→ 无空项。

> **机械「有无」与人工「对不对」的分工**：本表证明「这条不变式有没有测试守着」，不证明「测试断言的语义与 Go 一致」。后者要人签字，见 [`REVIEW-SHEET.md`](REVIEW-SHEET.md)。
