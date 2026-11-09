# J4 推进状态（上下文工程）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 更新：2026-11-05。J4 = 输出限流 + 截断落盘 + 上下文压缩 + 提示词分节 + 上下文文件 + 任务状态审计门（任务列表 §6）。
> 配套：[任务列表](../mos4j-tasks.md)、[推进方案](../mos4j-execution.md)、[契约目录](.)、[J1 状态](J1-STATUS.md)、[J2 状态](J2-STATUS.md)、[J3 状态](J3-STATUS.md)。

## 一句话状态

**T4.1–T4.9 全部完成并有测试；G4 三项机械验收全绿。**
限流与压缩已接进 `ReActDriver`（写 `tool/result` 前限流、每轮决策前压缩），
`Describe()` 会声明限流与压缩装没装、缺口是什么。
本里程碑的**机械项**全部在本机真实跑过，没有“写了但没跑”的用例；
剩下的只有**人工语义评审**（G4 门）与下表列出的未完成项。

## 产出文件

| 模块 | 文件 | 说明 |
|---|---|---|
| mos4j-context | `context/{Truncation,TruncatedOutput}.java` | 行/字节双限截断（默认 2000 行 / 50KB）；按工具定向保留；UTF-8 字符边界 |
| mos4j-context | `context/{SpillStore,SpillResult}.java` | 截断落盘逃生通道：目录必须在 jail 可读根内；原子写；失败诚实降级 |
| mos4j-context | `context/ToolOutputLimiter.java` | 先截断、再落盘；回执给出完整内容路径 |
| mos4j-context | `context/CompactionPolicy.java` | 触发条件：`contextTokens > contextWindow - reserveTokens`；未配置窗口 = 永不触发 |
| mos4j-context | `context/CutPointFinder.java` | 切割点搜索：保留段永不以 tool 结果开头 |
| mos4j-context | `context/Transcript.java` | 摘要输入格式（冻结标签 + `<conversation>` / `<previous-summary>` 包裹） |
| mos4j-context | `context/{SummaryGenerator,ChatModelSummaryGenerator}.java` | 摘要器接口 + 模型实现（不带工具、禁止续写） |
| mos4j-context | `context/{CompactionService,CompactionResult}.java` | 压缩编排：遮蔽、旧摘要折叠、压缩事件落库 |
| mos4j-context | `context/{PromptSection,PromptSections}.java` | 提示词分节装配：中心顺序 + add/set/complete/clone；装配期渲染一次 |
| mos4j-context | `context/{ContextFileReader,ContextFile,ContextFiles}.java` | 上下文文件发现（框架不碰文件系统） |
| mos4j-context | `context/{TaskState,Auditor}.java` | 任务状态 + 审计门（封闭白名单） |
| mos4j-driver | `driver/RunContext.java` | 新增 `compaction()` / `outputLimiter()`（可空 = 未装配） |
| mos4j-driver | `driver/ReActDriver.java` | 每轮决策前压缩；写 tool/result 前限流 |
| mos4j-driver | `agent/{Config,Agent}.java` | 装配入口 + `Describe()` 声明限流/压缩装没装 |

## 任务完成情况

| 任务 | 状态 | 证据（真实测试） |
|---|---|---|
| T4.1 截断 | ✅ | `TruncationTest`（9 条：未超限逐字不变 / read 留头 / shell 留尾 / 其余两头各半 / 字符边界 / 代理对不切开 / 硬上限含标记 / 标记写清丢了多少 / 更小的字节上限也能守住） |
| T4.2 落盘逃生通道 | ✅ | `SpillStoreTest`（3 条：落盘一致 / 越界构造期报错 / 失败诚实降级）+ `ToolOutputLimiterTest`（5 条：最终内容守上限 / 落盘是全文 / 未装落盘仍守上限 / 失败明说）+ `ContextFlowTest.spilledOutputPointsToTheFile` |
| T4.3 压缩触发（两段式估算） | ✅ | `CompactionBudgetTest.overBudgetTriggersCompaction` / `withinBudgetDoesNotCompact` / `withoutContextWindowNeverCompacts` |
| T4.4 切割点合法性 | ✅ | `CompactionCutPointTest`（3 条：回退到 assistant / 压缩后投影始终合法 / 保留段以 assistant 开头时不动） |
| T4.5 摘要生成 | ✅ | `PreviousSummaryTest.transcriptFormatIsFrozen` / `summaryRequestCarriesNoTools` |
| T4.6 压缩落库 + 投影遮蔽 | ✅ | `CompactionBudgetTest.maskingDoesNotRewriteTheLog` / `emptySummaryDoesNotMaskTheHistory` / `summaryFailurePropagates` / `compactionUsageIsCounted` + `ContextFlowTest.compactionRunsBeforeTheModelCall` / `compactionFailureClosesTheTurn` |
| T4.7 提示词分节装配 | ✅ | `PromptSectionsTest`（9 条：中心顺序 / add 重名报错 / set 不存在报错 / complete 整段替换 / clone 独立 / 逐字节一致 / 自定义段排后） |
| T4.8 上下文文件 | ✅ | `ContextFilesTest`（9 条：候选名优先级 / 向上递归 / 同目录 override / 真实路径去重 / 字节预算裁剪 + warnings / 读失败降级 / render / 裁剪不切开 UTF-8 字符 / 路径拼接由宿主决定） |
| T4.9 `TaskState` + `Auditor` | ✅ | `AuditorTest`（5 条：白名单收 / 非白名单拒 / 禁通配符 / 默认只读 / 状态不可变） |

## 场景覆盖（真实计数）

| 层 | 绿 | 未做 | 说明 |
|---|---|---|---|
| L2 S3（压缩） | **6/6**（L2-9 … L2-14） | — | L2-13 审批事件不进投影在 J1 已绿；L2-14 用量折叠本里程碑补齐 |
| G4 机械验收 | **3/3** | — | 压缩遮蔽 / 切割点合法 / 逐字节一致 |
| 接线级（driver） | **6/6** | — | `ContextFlowTest`：截断进回执 / 未装配原样 / 落盘给路径 / 压缩先行 / 压缩失败收尾 / Describe 声明装没装 |

## 验证记录（真实输出）

```
$ mvn -Dmaven.repo.local=.m2repo -B clean verify
mos4j-core     Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
mos4j-llm      Tests run: 35, Failures: 0, Errors: 0, Skipped: 14
mos4j-fs       Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
mos4j-sandbox  Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
mos4j-tool     Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
mos4j-session  Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
mos4j-hook     Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
mos4j-execpath Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
mos4j-context  Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
mos4j-driver   Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
mos4j-policy   Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

合计 **340 个用例通过 / 15 个跳过**（共 355 个跑过）。
跳过的 15 条 = Anthropic + Gemini 的 L1 协议场景 14 条（适配器在 J6）+ Seatbelt 强制效果 1 条（当前环境不允许应用）。

同一套用例用 Maven Wrapper 重跑，计数完全一致：

```
$ MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

依赖门（与 CI 的 C2b 同一条命令，wrapper 版已跑通）：

```
$ MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -am \
    -pl mos4j-core,mos4j-llm,mos4j-fs,mos4j-sandbox,mos4j-tool,mos4j-session,\
mos4j-hook,mos4j-execpath,mos4j-context,mos4j-driver,mos4j-policy \
    dependency:analyze -Dmdep.analyze.failOnWarning=true
11 个已实现模块全部 No dependency problems found
（mos4j-sdk 仍是空骨架，CI 的 C2b 门显式排除）
BUILD SUCCESS

$ mvn -Dmaven.repo.local=.m2repo dependency:tree -pl mos4j-context,mos4j-driver
外部依赖只有 jackson-databind（test scope）/ junit（test scope）—— I7 成立

$ grep -rn "import org.springframework" --include="*.java" mos4j-*/src/main   → OK: no spring
$ grep -rn "import java.util.regex"        --include="*.java" mos4j-*/src/main   → OK: no java.util.regex
```

CI 的 C2b 门已把 `mos4j-context` 纳入扫描范围。

## J4 期间改掉的「会静默出事」的问题

| 问题 | 后果 | 修复位置 |
|---|---|---|
| 截断标记写在外头、不占预算 | 「最多 50KB」实际变成 50KB + 标记长度，硬上限名存实亡 | `Truncation.cutByBytes()` 迭代收敛：标记长度计入上限（含极端小预算的短标记） |
| 字节裁剪只按 `char` 切 | 切出半个代理对 = 乱码；模型会把乱码当内容继续推理 | `cutHead` / `cutTail` 按**码点**推进（`codePointAt` / `codePointBefore`） |
| 保留行数把标记算成一行 | 上下文的行数统计与模型实际看到的不一致 | `TruncatedOutput.keptLines` 只数内容行 |
| 切割点落在 tool 结果上 | 投给服务商的请求非法（有回执无调用）→ 整个对话被拒收（真实表现是「莫名其妙 400」） | `CutPointFinder`：回退到声明它的 assistant；找不到声明者则孤儿回执一起遮蔽 |
| 摘要失败仍遮蔽历史 | 遮蔽了却没摘要 = 旧历史**彻底消失**，日志还在但模型再也看不到 | `CompactionService`：摘要为空 / 生成异常 → 不遮蔽；异常向上抛并由驱动 `turn/end{error}` 收尾 |
| 旧摘要以对话行重复出现 | 摘要滚雪球：第二轮摘要里含第一轮，第三轮含前两轮 | `CompactionService` 识别 `[历史摘要]` 前缀并折叠成 `<previous-summary>` |
| 摘要请求带工具 | 摘要这一步变成一次新的任务执行（模型去调工具） | `ChatModelSummaryGenerator` 的 `Request` 不带 `tools`；指令冻结为「不要续写对话 / 不要调用工具」 |
| 落盘目录不在 jail 内 | 多一条绕过 jail 的旁路（模型能读到 jail 外的文件） | `SpillStore.of()` 构造期就 `PathDeniedError`（fail-closed） |
| 落盘失败假装成功 | 模型去找一个不存在的文件，然后报告「文件不存在」——把降级说成事实 | `SpillResult.failed()`：内容原样返回 + `[落盘失败：完整输出未能保存]` 明说 |
| 落盘用普通 `writeString` | 模型可能在写入中途读到半截内容，把半截当事实 | `SpillStore` 改同目录临时文件 + `ATOMIC_MOVE`（不支持才降级普通 move） |
| 未配置窗口也压 | 用**猜的**窗口做决定：轻则白压（模型丢信息），重则压晚了（被服务商拒） | `CompactionPolicy.enabled()`：未配置 = 永不触发 |
| 审计门白名单用通配符 | `*` 等于「基本上什么都能进」——等于没有门 | `Auditor.of()` 拒绝含 `*` / `?` / `[` 的白名单项 |
| 上下文文件读取失败被吞 | 模型以为项目没有这条约定 | `ContextFiles`：读失败 / 去重 / 裁剪 / 丢弃全部写进 `warnings()` |
| 上下文文件发现直接碰文件系统 | `mos4j-context` 被绑死在本地文件系统上（服务端宿主没法用） | `ContextFileReader` 注入（读 / 真实路径 / 父目录三个口） |
| 压缩事件挂到「下一轮」 | 压缩发生在某轮之内，事件却记成下一轮的事——审计看着像凭空多一轮 | `CompactionService.compactionTurn()`：进行中的轮次用当前轮，没有进行中的轮次才用下一轮 |
| `mos4j-context` 把 `mos4j-core` 声明成 compile | 依赖分析报「Non-test scoped test only dependencies」：主代码根本不用它，只在测试里用 | `mos4j-context/pom.xml`：`mos4j-core` 改成 `test` scope（依赖按实际 import 声明，不靠传递） |
| 落盘提示不占预算 | 模型看到的内容 = 50KB + 提示，「最多 50KB」被提示顶破 | `ToolOutputLimiter`：先算提示字节数，再把剩下的预算分给内容（`Truncation.apply(..., byteLimit)`），提示与内容加起来不超上限；守护 `ToolOutputLimiterTest` |
| 上下文文件的路径拼接写死 `/` | 虚拟文件系统宿主（对象存储 / 容器挂载）用别的分隔符时，框架替宿主猜 → **静默读不到文件** | `ContextFileReader.join(dir, name)`：宿主可覆写；守护 `ContextFilesTest.pathJoiningIsTheHostsBusiness` |
| `ContextFiles.realPaths()` 名不副实 | 返回的是文件路径不是真实路径，看代码的人会以为去重键是这个 | 改名 `paths()`（去重仍在发现阶段用真实路径做） |
| `ContextFiles.cutHead` 越界隐患 | `limit >= content.length` 时会读 `content[end]` 越界（当前调用点不会触发，但那是运气不是设计） | 条件加上 `end < content.length`；并补 emoji 边界用例 |

## 未完成 / 待决策

| 项 | 说明 | 影响 |
|---|---|---|
| G4 人工语义评审 | 机械项全绿；**语义正确性**（截断方向是否真的符合任务场景、切割点回退策略是否可接受）需维护者确认 | G4 门未过 |
| Go 侧原测试名逐条抄录（T2.11） | ✅ 已完成（2026-11-06）：137 个真实 Go 测试名，机器复核全部存在 | T2.11 已完成 |
| 摘要格式与 Go 侧逐字对照 | 同上（Go 仓库已可读，逐行比对**仍未做**） | 冻结的标签格式（`[User]:` 等）是 Java 侧自定；若 Go 侧不同则需在 `API.md` §3.5 记录并改 |
| `contextWindow` 的来源 | 本框架**不猜**窗口，由宿主配置；宿主要自己知道服务商窗口 | 未配置 = 长会话不会被压缩（已在 `Describe()` 里声明） |
| `PromptSections` 与模型请求的接线 | 提示词分节目前是独立 API，尚未由 `ReActPlanner` 使用 | 宿主可自行组装；J5/J6 再决定是否内置 |
| `ContextFiles` 与模型请求的接线 | 同上（发现结果目前不自动进 system 提示） | 宿主可自行 `render()` 后塞进 `context` 段 |
| `TaskState` / `Auditor` 与执行链的接线 | 审计门目前是独立 API，未由 `ReActDriver` 自动申报事实 | 宿主可自行在拿到工具结果时申报 |
| `CancellationToken` 未贯穿 driver → planner | 取消目前只在模型 / HTTP 层生效 | 协作式取消的边界仍写在 `Describe()` 里 |
| 模块边界断言（T-INF.3） | 共享值类型类身份、模块边界检查未写 | CI 的 C3 门仍缺位 |
| `mos4j-sdk` 门面 | 仍是空模块（T6.10） | 宿主只能直接用 `Agent.of(Config.builder()...)` |
| Seatbelt 强制效果的真机验证 | 当前环境 `sandbox_apply: Operation not permitted`；用例如实标为跳过 | 属 J3 的遗留项，J4 未触碰 |
