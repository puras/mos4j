# L2 会话场景清单（J0 · T0.6，Java 自建）

> **口径**：场景设计对齐 MoS 踩坑记录（这是「参考」的价值本体），期望值 Java 自算。
> **落点**：`mos4j-session` 测试；每条场景在 J1 点亮，清单 J0 冻结。

## S1 投影与事件基础

| # | 场景 | 断言 |
|---|---|---|
| L2-1 | append 分配 seq | 首条 seq=1，严格递增，无空洞；`nextTurn()` 随 turn/end 递增 |
| L2-2 | 投影基本形态 | user/assistant/tool 消息角色与内容正确；system 不进事件日志 |
| L2-3 | 日志不可改写（I2） | append 后修改 Event 副本 → 日志内不变；events() 返回防御性视图 |
| L2-4 | JSONL 往返 | 落盘→读回→投影一致；`turn=0` 的事件 JSON 不含 `turn` 键（NON_DEFAULT 口径，§4.4） |

## S2 投影自愈（M4）

| # | 场景 | 断言 |
|---|---|---|
| L2-5 | 孤儿 tool/call | 崩溃模拟（append call 后无 result）→ 投影补合成中断回执，文案与 API.md 一致 |
| L2-6 | 回执序位 | 合成回执按声明序补在首个非 tool/result 事件之前；多个孤儿按序各补一条 |
| L2-7 | 回执继承 seq 语义 | 合成回执的投影位置跟随声明它的 assistant 事件 |
| L2-8 | 压缩与孤儿叠加 | 遮蔽线落在孤儿调用方之前 → 回执与调用方一起被遮蔽（同生共死） |

## S3 压缩（M8 / I4）

| # | 场景 | 断言 |
|---|---|---|
| L2-9 | 预算触发 | 超预算 → 压缩触发；`tokensBefore` 记录压缩前估算 |
| L2-10 | 遮蔽不改写日志 | compaction 事件追加；firstKeptSeq 之前事件仍在日志，只是不投影 |
| L2-11 | 切割点永不落 tool 结果 | 构造 tool/result 恰在预算线 → 切割点回退到安全位置；投影历史始终合法 |
| L2-12 | 连续两轮压缩 | 上一轮摘要以 `[历史摘要]` 前缀识别为 previous-summary 传给模型 |
| L2-13 | 审批事件不进投影 | `approval/asked`、`approval/decided` 全量落日志但不出现在 messages()（不走预算制，也不占投影） |
| L2-14 | 用量折叠 | totalUsage 含压缩调用自身的用量（session totals include summarization work） |

**S3 的真实守护位置（J4）**：

| 场景 | 守护测试 |
|---|---|
| L2-9 | `CompactionBudgetTest.overBudgetTriggersCompaction` / `withinBudgetDoesNotCompact` / `withoutContextWindowNeverCompacts` |
| L2-10 | `CompactionBudgetTest.maskingDoesNotRewriteTheLog`（+ 会话侧的 `L2ScenariosTest.maskingDoesNotRewriteLog`） |
| L2-11 | `CompactionCutPointTest.cutPointNeverLandsOnAToolResult` / `projectionStaysValidAfterCompaction` / `cutPointStaysWhenTheKeptSegmentStartsWithAssistant` |
| L2-12 | `PreviousSummaryTest.previousSummaryIsFoldedNotAccumulated` / `transcriptFormatIsFrozen` / `summaryRequestCarriesNoTools` |
| L2-13 | 会话侧：`L2ScenariosTest.approvalEventsAreAuditOnly`（J1 已绿） |
| L2-14 | `CompactionBudgetTest.compactionUsageIsCounted` |
| 接线级 | `ContextFlowTest.compactionRunsBeforeTheModelCall` / `compactionFailureClosesTheTurn` |

## S4 增量场景（自建，Go 无对应用例）

| # | 场景 | 断言 |
|---|---|---|
| L2-15 | 修复孤儿后恢复 | 孤儿 turn 补 `turn/end`（repairOrphanTurn 语义）→ nextTurn 恢复正常 |
| L2-16 | 长会话投影性能形态 | 1000+ 事件投影不重复解析整段日志（实现形态自由，行为正确即可） |
