# J1 推进状态（最小垂直切片）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 更新：2026-11-05。J1 = 跑通一个「读文件 → 作答」的完整 turn + L1/L2 场景点亮（任务列表 §3）。
> 配套：[任务列表](../mos4j-tasks.md)、[推进方案](../mos4j-execution.md)、[契约目录](.)。

## 一句话状态

**T1.1–T1.17 已完成并有测试；T1.18 部分完成**（L1 的 OpenAI 部分全绿，Anthropic/Gemini 明确留到 J6；L2 13/16，剩 3 条属压缩器 = J4 任务）。**G1 门尚未人工评审。**

## 维护者已拍板的三个口径（2026-11-05）

| 议题 | 拍板结果 | 影响 |
|---|---|---|
| G1 的「L1 全绿」指什么 | **= OpenAI L1 全绿**（5/5）+ 语料完整性 2 条；Anthropic / Gemini 的适配器与 L1 场景**明确留到 J6**（T6.1–T6.3） | G1 不再因那 14 条 `@Disabled` 挂起；G6 才要求三协议全绿 |
| J1 的「读文件」用什么验收 | **允许测试内工具**；生产级文件工具属 **J3 的 T3.11**（jail 双校验 + 写前观察门） | J1 垂直切片成立；不把 `mos4j-fs` 拉进 J1 |
| 无引用的 `SkillEvent` | **删除**（已删；`mos4j-session` 主源码目录不再有该类） | 冻结清单外的词汇彻底清干净 |

## 任务完成情况

| 任务 | 产出（路径） | 状态 | 证据（测试） |
|---|---|---|---|
| T1.1 共享值类型 | `mos4j-llm/.../llm/model/{Message,ToolCall,Usage,UsageDetails,ContentPart,Tool}.java` | ✅ | `FieldNamesTest`、`UsageJsonTest` |
| T1.2 ChatModel 抽象 | `mos4j-llm/.../llm/{ChatModel,Capability,Request,Response,StreamChunk,StreamEmitter}.java`、`StreamTruncatedException` / `StatusError` / `EmptyResponseException` / `StreamAbortedException` | ✅ | `OpenAiChatModelTest`、`L1OpenAiScenariosTest` |
| T1.3 OpenAI 协议 DTO | `mos4j-llm/.../llm/protocol/openai/{OpenAiDtos,OpenAiStreamDtos,OpenAiConfig}.java` | ✅ | `L1CorpusIntegrityTest` |
| T1.4 OpenAI 非流式 | `OpenAiChatModel.chat` | ✅ | `OpenAiChatModelTest` |
| T1.5 OpenAI 流式 | `OpenAiChatModel.chatStream` | ✅ | `L1OpenAiScenariosTest`（含「只发 `[DONE]`」判完整、「真截断」判错误） |
| T1.6 usage 归一 | `OpenAiChatModel.toUsage`、`Usage.freshInput/cacheHitRatio/partsSumDelta` | ✅ | `UsageJsonTest`、`OpenAiChatModelTest` |
| T1.7 传输层 | `mos4j-llm/.../llm/transport/{HttpTransport,SseLineReader}.java` | ✅ | `OpenAiChatModelTest`（429 → `StatusError`；取消 → `CancellationException`） |
| T1.8 Tool / Registry / Schema | `mos4j-tool/.../tool/{Tool,ToolParam,ToolRegistry,SchemaGenerator,FunctionTool,ToolArgs}.java` | ✅ | `ToolRegistryTest`（无 title/$defs；required 只由显式声明；注册期只反射一次；反射失败注册报错；重名报错） |
| T1.9 参数容错 | `ToolArgs.normalize` | ✅ | `ToolArgsTest`（`{…}{…}` 合并；非法 JSON 原样返回） |
| T1.10 事件 DTO + 手写 Serializer | `mos4j-session/.../session/{Event,EventJson,ToolResult,Approval,Compaction,Header}.java` | ✅ | `EventJsonTest`、`DtoZeroValueTest`、`EventFieldNameGuardTest`（真实 JSON 键集合 ⊆ 冻结清单） |
| T1.11 Session | `mos4j-session/.../session/Session.java` | ✅ | `SessionTest`（落盘失败 → 事件不进内存；seq 递增；用量折叠） |
| T1.12 JSONL + Restore | `mos4j-session/.../session/{JsonlPersistence,Persistence}.java` | ✅ | `JsonlPersistenceTest`（往返；尾部半行丢弃；中段损坏报错；孤儿 turn → `interrupted` 且不回写） |
| T1.13 投影自愈 | `mos4j-session/.../session/Projection.java` | ✅ | `ProjectionTest`、`L2ScenariosTest`（合成回执文案/序位/seq 继承；与遮蔽同生共死） |
| T1.14 LoopDriver / RunContext / Planner | `mos4j-driver/.../driver/{LoopDriver,Planner,Action,RunContext}.java` | ✅ | `ReActDriverTest` |
| T1.15 ReAct 驱动 | `mos4j-driver/.../driver/{ReActDriver,ReActPlanner}.java` | ✅ | `EndToEndTurnTest`、`ReActDriverTest` |
| T1.16 ExecPath | `mos4j-execpath/.../execpath/{ExecPath,Outcome}.java` | ✅ | `ExecPathTest`（回填序 = 调用序；错误即结果；逐条独立裁决；注册表缺失不崩；未声明并发 → 独占） |
| T1.17 Config / Agent / Describe | `mos4j-driver/.../agent/{Config,Agent,Describe}.java`（**不落 core**，见整体方案 §3.1 特例 4） | ✅ | `EndToEndTurnTest.describeStatesWhatIsNotInstalled` |
| T1.18 L1/L2 点亮 + 端到端 | `mos4j-driver/.../driver/EndToEndTurnTest.java` | ⚠️ 部分 | 见下表 |

## 场景覆盖（真实计数）

| 层 | 绿 | 跳过/未做 | 说明 |
|---|---|---|---|
| L1 | 5/5（OpenAI）+ 2 条语料完整性 | 14 跳过 | Anthropic 10 条 + Gemini 4 条：适配器属 J6（T6.x），骨架已就位（`@Disabled` + 指向 J6 的说明） |
| L2 | 13/16 | 3 未做 | L2-9 预算触发 / L2-11 切割点不落 tool 结果 / L2-12 连续两轮压缩：依赖**压缩器**（J4 的 T4.3–T4.6）。J1 已实现的是「已有 compaction 事件的投影遮蔽」 |
| L3 | 0/21 | 全部 | 属 J2（双轨制 / 审批 / 文案） |
| 端到端 | 1/1 | — | `EndToEndTurnTest`：读文件 → 作答一个 turn，事件序列 `turn/start → user → assistant → tool/call → tool/result → assistant → turn/end`，投影里 assistant 的 `toolCalls` 与 tool 消息按 CallID 成对 |

## 验证记录（真实输出）

```
$ mvn -Dmaven.repo.local=.m2repo clean verify
mos4j-core    Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
mos4j-llm     Tests run: 35, Failures: 0, Errors: 0, Skipped: 14
mos4j-tool    Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
mos4j-session Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
mos4j-execpath Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
mos4j-driver  Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

合计 **107 个用例通过 / 14 个跳过**（跳过的 14 条是 Anthropic + Gemini 的 L1 协议场景，适配器在 J6）。

依赖审计（`mvn dependency:tree -Dscope=runtime`）：全 reactor 外部依赖只有 `jackson-databind` / `jackson-annotations` / `re2j`（+ databind 传递的 `jackson-core`）——**I7 依赖纪律成立**。

## J1 期间修掉的「会静默出事」的问题

| 问题 | 后果 | 修复位置 |
|---|---|---|
| `ReActPlanner` 把 `tools` 丢掉 | 模型看不到工具 → 整条工具链静默失效（不报错，只是模型瞎编） | `ReActPlanner.request(...)` + `ReActDriverTest.toolDefinitionsReachTheModel` |
| `SchemaGenerator` 对 `Map.of(...)` 调 `put` | 任何带 `@ToolParam` 的参数类一生成就 `UnsupportedOperationException` | `SchemaGenerator.typeSchema` 全部改用可变 Map |
| `ToolArgs.normalize` 用 `readTree` 判合法 | `{…}{…}` 拼接**不触发**合并分支（Jackson 默认忽略尾随 token）→ 容错形同虚设 | `ToolArgs.normalize` 改为逐个 root 解码 + root 数 > 1 才合并 |
| `Session` 不写首行 Header | 日志首行是事件 → 恢复时第一行被当 Header 跳过 → **第一条事件丢失** | `Session.append` 首次落盘前写 Header |
| `Event.toolCalls` 未做防御性拷贝 | 调用方事后改 list → 日志里的 assistant 事件跟着变（I2 当场失效） | `Event` 紧凑构造器 `List.copyOf` |
| 决策器抛错不留 `turn/end` | 留下孤儿 turn，恢复时只能合成 `interrupted`，真实原因丢失 | `ReActDriver.decide` 包 try/catch → `abort()` |
| `HttpTransport` 只把 200 当成功 | 201/204 被误判为失败 | `HttpTransport.send` 改为「非 2xx 才算失败」 |
| OpenAI 音频按图片发 | 服务端拒或静默错解 | `OpenAiChatModel.audioPart` → `input_audio`（无 base64 数据时当场报错） |
| 事件类型里有 `skill/activate`（Go 侧没有、字段名清单里也没有） | 自己发明的词汇进了冻结清单之外 | 移除事件类型与 `Event.skill` 字段；**`SkillEvent` 类已删除**；并新增 `EventFieldNameGuardTest`：把真实 JSON 键集合逐个比对冻结清单（故意写入 `skill` 键 → 测试红 → 已复原） |

## 未完成 / 待决策（进 J2 前）

| 项 | 说明 | 影响 |
|---|---|---|
| G1 人工评审 | 机械项全绿；**端到端场景的语义正确性**需维护者确认 | G1 门未过 |
| G0 人工评审 | J0 的复选框仍未勾（等评审） | 文档口径不齐 |
| L1 的 Anthropic / Gemini | **已拍板：留到 J6**（T6.1–T6.3），G1 门不再因这 14 条跳过而挂起 | G6 才要求三协议全绿 |
| `CancellationToken` 未贯穿 driver | `ChatModel` 侧已支持取消（真中止 HTTP），但 `Agent.run` 还没有带令牌的入口 | 协作式取消目前只能在模型层生效 |
| 压缩器 | 预算触发 / 切割点 / previous-summary（J4 的 T4.3–T4.6） | **J4 已补齐**（见 `J4-STATUS.md`）：L2-9/11/12 已绿 |
| `mos4j-sdk` 门面 | 宿主装配 DSL（Builder）未实现；**已登记为 T6.10**（J6） | 目前只能直接用 `Agent.of(Config.builder()...)` |
| 边界检查（T-INF.3） | 模块边界与「共享值类型同一批类」的断言未写 | CI 的 C3 门缺位 |
| 真实 `read_file` 工具 | **已拍板：J1 用测试内工具验收**，生产工具归 J3 的 **T3.11**（`mos4j-fs` jail 双校验 M9 + 写前观察门 M7） | 垂直切片成立；生产可用的文件工具在 J3 写。**注**：工具名后于 B13-1 改为 `read`（2026-11-06，见 `J6-STATUS.md` §8） |
| ~~`SkillEvent` 文件删除~~ | **已完成**：类已删除（维护者确认「没用就删」）；删除后全量 `clean verify` 仍绿 | — |
