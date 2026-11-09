# L1 协议场景清单（J0 · T0.5）

> **口径**（整体方案 §0.0②）：SSE 报文**照抄 Go 测试字面量**（真实抓包非手造）；期望值由 Java 侧计算，不与 Go 基线机器对拍。
> **落地形态**：每条场景 = `src/test/resources/model/{protocol}/{case}.sse`（原始报文）+ Java 测试类断言四元组 `content / toolCalls / finishReason / usage`。
> 本清单是 T0.5 的验收范围：实现到 J1 时逐条点亮；清单本身 J0 冻结。
> **点亮状态（J6 收口）**：Anthropic 10 条 → `L1AnthropicScenariosTest`（另加非流式 + 请求形状 2 条，共 12）；OpenAI 5 条 → `L1OpenAiScenariosTest`；Gemini 4 条 → `L1GeminiScenariosTest`（另加非流式 + 请求形状 2 条，共 6）。三协议全部点亮，`@Disabled` 归零。

## Anthropic（10 条）

| # | 场景名 | Go 用例出处 | 断言要点 |
|---|---|---|---|
| A1 | 文本流基础 | `TestChatStream` | `content="Hi there"`；deltas 拼接一致；`finishReason=end_turn` |
| A2 | 工具调用流 | `TestChatStreamToolUse` | `input_json_delta` 片段**按 index 累加**拼出完整 args；`toolCalls[0].name="calculator"` |
| A3 | 思维链流 | `TestChatStreamThinking` | reasoning 块与文本块分离；未订阅时零开销（I6） |
| A4 | 真截断报错 | `TestChatStreamTruncatedErrors` | 无结束标记且无 stop_reason → `StreamTruncatedException` |
| A5 | **只有 message_stop 也完整** | `TestChatStreamWithoutMessageDeltaIsComplete` | 有结束标记 → 完整；`finishReason=""`（未知≠截断，M5）；content="回答" |
| A6 | 无任何结束信号 | `TestChatStreamCutWithoutAnyEndSignalErrors` | 两者皆无 → `StreamTruncatedException`（M5） |
| A7 | usage 来自 message_start | `TestChatStreamUsageFromMessageStart` | 只发 message_start 的网关：用量不丢（tokens 恒 0 是事故） |
| A8 | usage 两侧合并 | `TestChatStreamUsageMergesBothEvents` | start(10) + delta(7) → prompt=10、completion=7、total=17 |
| A9 | 分项和=总计（Anthropic 口径） | `TestChatStreamUsagePartsSumToTotal` | total = 输入+缓存写+缓存读+输出（input 不含缓存） |
| A10 | delta 赢过 start | `TestChatStreamUsageDeltaWinsOverStart` | 同字段两侧都有 → delta 值生效 |

## OpenAI（6 条）

| # | 场景名 | Go 用例出处 | 断言要点 |
|---|---|---|---|
| O1 | 文本流基础 | `TestChatStream` | chunk delta 拼接；`[DONE]` 结束 |
| O2 | tool_calls 流（index 累加） | `TestChatStreamToolCallAggregation` | `tool_calls[i].function.arguments` **按 index 分片累加**——乱序到达也按 index 归位 |
| O3 | finish_reason=tool_calls | `TestChatWithToolCall` | 结束原因透传；工具调用回合判定 |
| O4 | usage | `TestChatStreamUsage` | usage 块在 `[DONE]` 前到达；合并正确；details 透传 |
| O5 | 截断流 / 只有 [DONE] 也完整 | `TestChatStreamTruncatedErrors` / `TestChatStreamWithDoneOnlyIsComplete` | 无任何结束信号 → `StreamTruncatedException`；只有 `[DONE]` 无 finish_reason → 完整（M5 对称用例） |
| O6 | 多工具并行调用 | `TestChatStreamToolCallAggregation` 扩展 | 多个 index 的 tool_calls 互不串扰，顺序保持（自建扩展，无 Go 对应用例） |

## Gemini（6 条）

| # | 场景名 | Go 用例出处 | 断言要点 |
|---|---|---|---|
| G1 | 文本流基础 | `TestChatStream` | candidates[0].content.parts 拼接；`finishReason` 结束 |
| G2 | functionCall 流 | `TestChatStreamToolCall` | parts 中的 functionCall → toolCalls；args 已是完整 JSON 对象 |
| G3 | finishReason 判定 | `TestRequireFinishFalseAllowsMissingFinish` | 结束原因即结束标记（更严）；`require_finish=false` 可关（M5） |
| G4 | usageMetadata | `TestChatStreamUsage`（Gemini 侧从 `TestChatStream` 断言） | prompt/completion/total 直接读取 |
| G5 | 截断流 | `TestChatStreamTruncatedErrors` | 无 finishReason 且流断 → 截断错误（require_finish 开时） |
| G6 | 多 part / 多候选边界 | `TestChatContentPartsAndStructuredOutput` 扩展 | 只取 candidates[0]；part 顺序保持（自建扩展，无 Go 对应用例） |

## 共同断言口径

1. **四元组逐字段断言**（content / toolCalls / finishReason / usage），期望值写在 Java 测试里；
2. **usage 不变式**（M6）：每条带 usage 的场景同时断言「分项之和 == 总计（按该协议自身口径）」；
3. **SSE 解析容错**：报文中的 `event:` 行有无可选（A5/A6 的 Go 原文就没有 `event:` 行）；
4. 每份 `.sse` 文件头部注释注明 Go 出处用例名（`#` 注释行解析器须跳过）。
