# J6 状态（权威口径）

> 里程碑：**J6 三协议 + 硬化**。本文件是 J6 的验收权威口径；与其它文档冲突时以本文为准。
> 基线日期：2026-02-06（工作区本地时间）。

## 1. 交付物清单

### 生产代码（新增）

| 模块 | 文件 | 说明 |
|---|---|---|
| mos4j-llm | `protocol/anthropic/AnthropicConfig.java` | 连接配置（`x-api-key` + `anthropic-version` 头；`/v1/messages` 端点） |
| mos4j-llm | `protocol/anthropic/AnthropicDtos.java` | 线格式 DTO（响应侧 `@JsonIgnoreProperties`；`system` 是顶层字段；assistant 工具调用 ↔ tool_use 块；工具回执 ↔ tool_result 块） |
| mos4j-llm | `protocol/anthropic/AnthropicStreamDtos.java` | 流事件 DTO（`content_block` 载荷、`input_json_delta.partial_json`、`message_delta.stop_reason`） |
| mos4j-llm | `protocol/anthropic/AnthropicChatModel.java` | 适配器：流完整性（M5）+ 两侧 usage 合并 delta 赢（M6）+ thinking 分离通道 + 工具调用按 index 累加 |
| mos4j-llm | `protocol/gemini/GeminiConfig.java` | 连接配置（`x-goog-api-key` 头；`/models/{model}:generateContent` / `:streamGenerateContent?alt=sse`） |
| mos4j-llm | `protocol/gemini/GeminiDtos.java` | 线格式 DTO（Part 开放形状：text/functionCall/inlineData/functionResponse） |
| mos4j-llm | `protocol/gemini/GeminiChatModel.java` | 适配器：`finishReason` 即结束标记（更严）+ `require_finish` 开关（缺省开）+ functionCall args 对象→字符串 + 同 id 跨 chunk 合并 |
| mos4j-llm | `RetryPolicy.java` | 重试策略（次数/退避/分类器；`backoff(attempt, random)` 有界指数 + ±20% 抖动） |
| mos4j-llm | `RetryPolicies.java` | 缺省瞬时分类器（429/5xx/408/网络/空响应可重试；取消/中断/截断/宿主中断一律终止性） |
| mos4j-llm | `RetryingChatModel.java` | 重试装饰器（默认不装配即透传；流式只在未吐内容时重试；退避等待分片检查取消） |
| mos4j-llm | `RetryExecutors.java` | 缺省睡眠（Thread.sleep；包内细节） |
| mos4j-sdk | `net/hegq/mos4j/sdk/Mos4j.java` | 门面 DSL（协议枚举 + 环境变量缺省 + 文件工具缺省装配 + `session(id)`） |
| mos4j-sdk | `net/hegq/mos4j/cli/Main.java` | CLI `mos4j`（单轮执行；`--help` 用法；退出码 0/2） |

### 生产代码（修改）

| 文件 | 修改 | 证据 |
|---|---|---|
| `mos4j-execpath/ExecPath.java` | 新增 `executeBatch(..., CancellationToken)` 重载：preflight 前检查取消（剩余全跳过）、组间检查（整组跳过）、组内 acquire 前检查；未执行的调用回填「调用已取消，未执行」（错误结果，保持配对） | `ExecPathTest` 取消 3 条 |
| `mos4j-session/JsonlPersistence.java` | `load()` 改字节读入 + `new String(..., UTF_8)`（替换式解码）：半行停在多字节字符中间不再抛 `MalformedInputException` | fuzz 抓到，见 §4 |
| `mos4j-tool/ToolArgs.java` | `normalize()` catch 增加 `RuntimeException`：Jackson `MappingIterator` 把非法转义包成 RuntimeException 泄漏 | fuzz 抓到，见 §4 |
| `mos4j-llm/OpenAiDtos.java`、`OpenAiStreamDtos.java` | 响应/流式侧全部 record 加 `@JsonIgnoreProperties(ignoreUnknown = true)`：服务商加字段（如 `id`/`object`/`model`）不再炸掉适配器 | CLI 集成测试抓到，见 §4 |
| `mos4j-llm/support/FakeLlmServer.java`（测试基座） | 注册 `/` 兜底 context（Anthropic/Gemini 端点可回放）；新增 `lastHeader(name)`/`lastPath()` | `L1AnthropicScenariosTest` 依赖 |

### 测试（新增）

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `mos4j-llm/L1AnthropicScenariosTest.java` | 12 | A1–A10 语料逐条 + 非流式解析 + 请求形状（模型名/max_tokens/system 顶层/x-api-key 头） |
| `mos4j-llm/L1GeminiScenariosTest.java` | 6 | G1/G2/G5a/G5b 语料 + 非流式 + 请求形状（路径带模型名/x-goog-api-key 头/usage 在 finishReason 之后不丢） |
| `mos4j-llm/RetryingChatModelTest.java` | 9 | 默认透传/瞬时重试成功/4xx 不重试/分类器口径/耗尽抛最后错/退避中取消终止/退避边界/流式已吐内容不重试/未吐内容可重试 |
| `mos4j-llm/SseParserFuzzTest.java` | 3 | 噪声行跳过（event:/注释/空行/CRLF/garbage）+ 200 轮截断 + 200 轮随机字节 |
| `mos4j-execpath/ExecPathTest.java`（增 8 条） | +8 | Semaphore 上限 / 屏障隔离 / panic 不炸整批 / 虚拟线程验证 / 零反射清单稳定（T6.8）/ 取消 3 条（T6.4） |
| `mos4j-session/JsonlTruncationFuzzTest.java` | 1 | 200 轮字节级随机截断：恢复数 == 完整事件行数（精确逐字节期望值计算） |
| `mos4j-tool/ToolArgsFuzzTest.java` | 2 | 500 轮随机字节/字符 + 合法单根 JSON 逐字不变 |
| `mos4j-sdk/Mos4jTest.java` | 5 | 缺省装配/缺密钥 fail-fast 报变量名/协议切换/session 往返/fileTools(false) |
| `mos4j-sdk/MainTest.java` | 5 | 真实 HTTP 跑一轮 + 会话落盘/Header/assistant 事件 + 用法与退出码 |
| `mos4j-sdk/test/FakeLlmServer.java` | — | sdk 模块测试基座（llm 版是 test scope 不可跨模块共享，复制口径） |

## 2. 最终基线（wrapper `clean verify`，mvn 与 wrapper 双跑一致）

> 本表是 **J6 收口时**的基线（416/1）。之后的「加固批次」见 §7，「Go 对拍与工具面对齐批次（B13）」见 §8，收口后当前基线为 **449/1**（默认 reactor；含按需 starter 为 463/1）。

| 模块 | 通过 | 跳过 |
|---|---|---|
| core | 7 | 0 |
| llm | **51**（+16） | **0**（原 14 条 `@Disabled` 全点亮） |
| fs | 37 | 0 |
| sandbox | 28 | 1（Seatbelt 环境限制，历史遗留） |
| tool | **79**（+2） | 0 |
| session | **43**（+1） | 0 |
| hook | 23 | 0 |
| execpath | **40**（+8） | 0 |
| context | 53 | 0 |
| driver | 27 | 0 |
| policy | 18 | 0 |
| sdk | **10**（新增） | 0 |
| **合计** | **416** | **1** |

- 13 个 reactor 项（12 模块 + 父聚合）全 SUCCESS。
- G6 三门全过：①L1 三协议全绿（Anthropic 12 + OpenAI 5 + Gemini 6）；②取消/超时无泄漏（HTTP 真取消 + ExecPath 无空洞 + 重试退避分片检查）；③依赖审计 11 模块 + sdk 全 `No dependency problems found`，运行时依赖仍只有 Jackson/RE2J/JDK。
- 源码扫描：`import java.util.regex` = 0（G5 门延续）、`org.springframework` = 0。

## 3. 关键语义决定（实现口径，不得擅改）

1. **Anthropic 流完整性（M5 双条件对称）**：结束标记（`message_stop`）与结束原因（`message_delta.stop_reason`）任一到即完整；两者皆无才是截断；结束原因为空 ≠ 截断（A5 语料：只有 message_stop 也完整，`finishReason=""`）。
2. **usage 两侧合并 delta 赢（M6，A10 真实事故回归）**：`message_start` 与 `message_delta` 都带 usage 时逐字段 delta 赢。内网网关的 `message_start.cache_read_input_tokens` 报「总输入」而非「命中子集」——读反会得命中 1113/total 2232 的对不上账；正确读法命中 188、freshInput 925、total 1307。
3. **Anthropic 口径归一**：`input_tokens` 不含缓存 → `freshInput = inputTokens`；total = input + cache_write + cache_read + output（A9：900+186+3+84=1173）。
4. **Gemini 无终止哨兵**：不发 `[DONE]` 也没有终止事件——读到 EOF 为止；`finishReason` chunk 之后的 `usageMetadata` data 行必须继续消费（g1 语料即此形状），提前停止会丢用量。
5. **Gemini 结束判定更严**：`finishReason` 即结束标记；`require_finish=true`（缺省）时流读完仍无 finishReason = 截断（g5a）；开关关掉放行（g5b，容忍上游网关丢 finishReason）。
6. **Gemini functionCall 同 id 跨 chunk 合并**：g2 语料同一 `fc_1` 先 `{}` 再全量——按 id 合并、后到覆盖（最后一块是最终完整对象），不是两次调用。
7. **重试口径**：装饰器模式（`RetryingChatModel` 包 `ChatModel`），默认不装配 = 零重试（调用次数语义可预测，审计「日志即事实」只记真实调用）；缺省分类器只认 429/5xx/408/网络/空响应；取消与宿主中断是终止性（重试它们等于违抗调用方）；`StreamTruncatedException` 不重试（流完整性被破坏，重试掩盖不了——M5）；流式已吐内容后不再重试（内容重复比失败更糟）。
8. **退避**：`min(maxBackoff, initial * 2^(attempt-1)) ± 20% 抖动`；指数位上限 20 防溢出；等待按 100ms 分片检查取消（长退避也能及时响应取消）。
9. **ExecPath 取消语义（协作式，诚实声明）**：取消后「未开始」的调用不执行（回填错误结果保持配对）；「正在执行」的不中断（工具各自响应令牌/中断）——取消是信号不是强杀。
10. **SDK 门面只做装配**：执行语义全部复用 agent/driver（门面自实现语义 = 第二份真相）；缺省工具面只有文件四件套（工具面是宿主的决定，门面不替宿主扩大能力）。
11. **Gemini 鉴权用 `x-goog-api-key` 头**（不是 `?key=` 查询参数）：密钥不进 URL（日志/代理/审计里 URL 会留痕）。

## 4. fuzz 抓到并修复的真实缺陷（T6.7 的价值证明）

| # | 缺陷 | 抓它的 fuzz | 修复 |
|---|---|---|---|
| 1 | `JsonlPersistence.load` 用 `Files.readString`（严格 UTF-8）：崩溃半行停在多字节字符中间 → `MalformedInputException`，违反「尾部半行可丢」契约 | `JsonlTruncationFuzzTest`（第 3 轮即中） | 字节读入 + `new String(bytes, UTF_8)`（替换式解码，残缺序列→U+FFFD，只可能出现在丢弃区） |
| 2 | `ToolArgs.normalize` 只 catch `IOException`：Jackson 对非法转义（如 `\T`）以 RuntimeException 包装抛出 → 泄漏到调用方 | `ToolArgsFuzzTest`（随机字节） | catch 增加 `RuntimeException`（非法 JSON 契约 = 原样返回让工具侧显式报错） |
| 3 | OpenAI 响应/流式 DTO 不忽略未知字段：非流式 JSON 带 `id`/`object`（官方 API 必带）→ `UnrecognizedPropertyException` | `MainTest` CLI 集成（真实 HTTP 全链路） | 响应侧全部 record 加 `@JsonIgnoreProperties(ignoreUnknown = true)`（与 Anthropic/Gemini DTO 同纪律；请求侧保持精确序列化） |

## 5. 诚实边界（未做 / 部分做）

1. **审批阻塞迁虚拟线程（T6.4 的一部分）未单独立项验证**：审批发生在 ExecPath preflight 串行段（平台线程内同步回调），宿主审批回调在哪个线程属宿主域——SDK 侧没有「审批阻塞虚拟线程」这个可阻塞点（Go 侧语义是 channel 等待）。已在 `INVARIANT-TEST-MAP` 口径下说明，不虚报完成。
2. **投影自愈 fuzz 未单独立项**：孤儿 turn 合成收尾已有确定性测试（`SessionTest`/`JsonlPersistenceTest`），随机化投影自愈与 JSONL 截断 fuzz 高度重叠，未重复造轮子。
3. **T6.8 的「基准」是最小基准**（顺序稳定 + 零反射断言），没有 JMH 级微基准——注册路径的反射消除在结构上是编译期事实（schema 注册即缓存），不值得为此引 JMH 依赖（违反依赖纪律）。
4. **CLI 是单轮形态**：多轮 REPL、审批交互（tty 审批通道）、`--session` 续聊的多轮循环未做（任务口径即「最小形态：装配 + 跑一轮」）。
5. **历史遗留（非 J6 范围）**：G1–G5 人工语义评审未单独记录；T2.11 Go 侧原测试名仅登记 1 条（Go 仓库只读未授权）；~~`CancellationToken` 在 driver→planner 层未贯穿~~ **已收口（见 §7）**；~~CI C3 门缺位~~ **已收口（见 §7）**。
6. **人工语义评审未做，但评审辅助已备好**：`docs/contract/REVIEW-SHEET.md` 已把七条不变式 × 十机制 × 三协议口径 × 25 条自创机制 × 12 条诚实边界 × G1–G6 门摊成可逐行签字的表，每行给出「契约句 / 违反后果 / Java 落点 / 守护测试 / 机械门」；表内引用的 `类.方法` 已逐条核实在仓库中真实存在（写完当场机器核过一遍，避免评审人追假名）。**缺的是人填判定，不是产物。**

## 6. 验收对照（G6）

| 门 | 结论 | 证据 |
|---|---|---|
| L1 全场景通过（三协议） | ✅ | `L1AnthropicScenariosTest`(12) + `L1OpenAiScenariosTest`(5) + `L1GeminiScenariosTest`(6)；`@Disabled` 归零 |
| 取消/超时无泄漏 | ✅ | `ExecPathTest` 取消 3 条（无空洞/全跳过/null 零开销）+ `RetryingChatModelTest` 退避中取消终止 + `HttpTransport` 真取消（既有） |
| 依赖审计仍只有 3 个运行时依赖 | ✅ | 11 模块 + sdk 全 `No dependency problems found`；`java.util.regex`/Spring 扫描 = 0 |

## 7. 加固批次（J6 收口后的遗留项收口）

> 范围：把 J6 诚实边界里列出的、**能在本仓库内完成**的两项遗留收掉，并补上工程基建的三个待办。
> 不做：人工语义评审（G1–G6 评审记录）、Go 侧原测试名补登（仓库只读未授权）——这两项需要人。

### 7.1 取消贯通 driver→planner→agent（SI-1 收口）

| 改动 | 位置 | 口径 |
|---|---|---|
| `Agent.run(session, input, token)` | `mos4j-driver/net/hegq/mos4j/agent/Agent` | 新增重载；旧重载等价于 `CancellationToken.none()`（零开销） |
| `LoopDriver.run(rt, session, input, token)` | `mos4j-driver/driver/LoopDriver` | default 方法：**只在开始前检查一次**，并明说「不响应中途取消」（第三方驱动不可能都改一遍） |
| `Planner.nextAction(history, tools, token)` | `mos4j-driver/driver/Planner` | default 方法：调用前检查一次；`ReActPlanner` **覆写**并把令牌直传到 `model.chat(request, token)` |
| `ReActDriver.run(..., token)` | `mos4j-driver/driver/ReActDriver` | 四个取消检查点（详见下）+ `turn/end{interrupted}` 收尾 |

**取消语义（实现口径，不得擅改）**：

1. **取消先于落盘**：已取消的调用在写 `turn/start` 之前就抛 `CancellationException`——日志里不留半个 turn。
2. **令牌直传到模型**：这是唯一能**真中止**的一环（HTTP 交换真的断开，不是等它回来）。
3. **工具批次**：未开始的调用不执行，回填「调用已取消，未执行」（保持回填序与 assistant↔tool/result 配对）。
4. **取消优先于完成**：模型已返回最终答案但令牌已取消时，本轮**不**产出 `completed`，而是 `turn/end{interrupted}` + 抛 `CancellationException`——否则「叫停」会被静默吞掉（宿主以为停了，其实拿回了旧结果）。答案仍落在日志里（事实不丢）。
5. **reason 取值不新增**：复用已冻结的 `interrupted`（原本用于崩溃残留的孤儿 turn 合成收尾），具体原因写在 `error` 字段（「操作已取消：宿主请求中止本轮（协作式取消）」）——审计能分清「是人叫停的」而不是「跑挂了」。
6. **正在执行的工具不中断**（协作式，诚实声明）；`token == null` 零开销。

**守护测试**：`mos4j-driver/src/test/java/net/hegq/mos4j/driver/CancellationFlowTest`（5 条：取消先于落盘 / 令牌到达模型调用 / 取消后工具不执行但回执补齐 / 无孤儿 turn / 无令牌即无取消）。

### 7.2 T-INF.3 模块边界门（CI 门 C3）

`mos4j-sdk/src/test/java/net/hegq/mos4j/arch/BoundaryRulesTest`（7 条）。**不用 ArchUnit**——引第三方依赖会破依赖纪律（运行时依赖只允许 Jackson / RE2J / JDK），改用 JDK 自带的文件扫描 + `DocumentBuilderFactory` 解析 pom。

| 规则 | 守什么 |
|---|---|
| 能力层不得 import 驱动层/装配层 | 反向依赖会把底层能力被上层拖住，没法单独复用 |
| 驱动层不得 import 装配层 | 门面是可选外壳，驱动不能反过来依赖它 |
| pom 依赖方向与分层一致 | 源码里没 import 也能靠 pom 引入 |
| 协议层不得依赖日志层 | 方向反了就会出现「日志与协议两套 DTO」 |
| 共享值类型全局恰好一份定义 | `Message`/`Role`/`ToolCall`/`Usage`/`InputTokenDetails`/`OutputTokenDetails` 各一份 |
| 投影产出的就是协议层的类（类身份断言） | 日志与协议同形（不变式 I2 的落地前提） |
| 共享词汇表只在 `mos4j-core` | 各模块各抄一份 = 两份真相 |

**负向验证（已做）**：临时给 `mos4j-policy` 加驱动层依赖 + `import net.hegq.mos4j.driver.RunContext` → 源码规则与 pom 规则**同时变红**并指到具体文件；已回滚。

### 7.3 T-INF.2 / T-INF.7 CI 门补齐

- **T-INF.2 负向验证**：临时给 `mos4j-policy` 加 `org.slf4j:slf4j-api` → 构建在 enforcer 处变红（`banned via the exclude/include list`）；已回滚。
- **CI**：`.github/workflows/ci.yml` 补 C3 步骤（单跑 `BoundaryRulesTest`）；C2b 依赖审计从 11 模块扩到 **12 模块**（sdk 已有实现，不再是空骨架）。
- **T-INF.6**：抽查 10 个公共类型的 Javadoc，补齐 3 个缺显式「为什么/不这么做会出什么事」段的（`SandboxedFs` / `RetryingChatModel` / `Main`）。

### 7.4 依赖审计的一个真发现

把 sdk 纳入 C2b 审计后暴露：`mos4j-sdk` 声明了 5 个「自己不用、但要对外暴露」的模块依赖（core / execpath / hook / policy / context）→ 审计报「声明了却没用」。**不删**：删了等于让用户自己补依赖，门面的「一次依赖拿到全部模块」承诺就破了。改为在 `mos4j-sdk/pom.xml` 里显式豁免并写明理由（`ignoredUnusedDeclaredDependencies` + `combine.children="append"`，避免覆盖父 POM 里 junit 的豁免）。审计回到 12 模块全 `No dependency problems found`。

### 7.5 加固后的基线

**428 通过 / 1 跳过**（J6 收口时 416/1；driver +5、sdk +7）。13 个 reactor 项全 SUCCESS；12 模块依赖审计全绿；`java.util.regex` / Spring 扫描仍为 0。

> 后续增量：Spring Boot starter（按需模块，不在默认 reactor）落地后，默认 reactor 基线为 **430/1**（门面新增 2 条：Describe 如实报告 jail + 可选能力接线），含 starter 为 **444/1**（starter 14 条）；starter 细节见 `docs/spring-boot-starter.md` 与 `docs/mos4j-tasks.md` §10.1。

---

## 8. Go 对拍与工具面对齐批次（B13，2026-11-06）

### 8.1 落地了什么

| 子项 | 内容 | 落点 |
|---|---|---|
| **B13-1** | 三个文件工具的**模型可见名**改为 Go 的 `read` / `write` / `edit`（Java 类名 `ReadFileTool` / `WriteFileTool` / `EditFileTool` 不变） | `mos4j-tool/builtin/*`；连带 `Auditor.DEFAULT_TRUSTED_TOOLS` 与 `Truncation` 的 head 集合对齐 Go（`read`/`current_time`/`grep`/`glob`/`ls`） |
| **B13-2** | `grep` 增 `glob` 过滤（先按相对路径、再按文件名匹配，与 Go 同口径）；`glob` 增 `limit`（默认 1000 / 上限 10000）；`ls` 增 `limit`（默认 500 / 上限 5000）；`shell` 增可选 `description`（回执前置「说明：」，退出码标记仍在末尾）；`tool_search` 模型可见参数名 `topK` → `limit` | `GrepTool` / `GlobTool` / `LsTool` / `ShellTool` / `ToolSearchTool` |
| **B13-3** | `edit` 参数改为 `edits`（数组 / 单个对象 / JSON 字符串三种形状）；每处 `old_string` 仍须唯一命中；**全部按原文定位**（顺序不影响定位）；重叠编辑报错；原子落盘，失败不留半改状态 | `mos4j-tool/builtin/EditFileTool` |

### 8.2 机器门：工具名清单守护

新增 `mos4j-tool/src/test/java/net/hegq/mos4j/tool/ToolContractTest.java`（4 条）：冻结 **13 个模型可见工具名**（`read`/`write`/`edit`/`shell`/`grep`/`glob`/`ls`/`current_time`/`grant_access`/`tool_search`/`activate`/`web_search`/`web_fetch`）+ 每个工具的**参数名**与**必填参数** + schema 不含 `title`/`$defs`。

**为什么要有这道门**：工具名与参数名是模型可见的契约，改一个字母就会让模型继续用旧名字调用（提示词里写着旧名字、旧名字进了历史会话），表现为「工具不存在」这类从模型侧冒出来的错。文档不会自己变红，清单才会。

### 8.3 明确保留为有意差异的两处

- **B13-4**：`grant_access` 保留 Java 的 `mount`+`dir`+`reason`（Go 的 `path`+`mode` 表达不了 `<挂载名>/...` 寻址，改过去会弱化挂载、审批与文案语义）。
- **B13-5**：deferred 保留 Java 的 `tool_search` + `activate` 两步（Go 一步式反而不能显式激活没搜到的工具；Java 是能力超集）。

代价已写进 `SELF-INVENTED.md` SI-26：**Go 侧会话日志里的 `grant_access` 与 deferred 激活调用不能直接在 Java 回放**（其余工具可回放）。

### 8.4 顺带修掉的连带问题

- `Auditor.DEFAULT_TRUSTED_TOOLS` 机械改名后曾出现重复项，已修正为 Go 同款五元集；
- `Truncation` 的 head 集合曾保留 `read_file`，已对齐 Go（`TruncationTest.headToolsMatchTheGoReference` 机器守住）；
- `edit` 旧用例（顶层 `old_string`/`new_string`）已迁移到 `edits` 形状，并补 9 条新用例（批量 / 原文定位 / 重叠拒绝 / 三种形状 / old==new / 失败不留半改 / schema 形状）。

### 8.5 B14 / B15

- **B14（Go 的 skill 机制）**：本轮**只登记**，实现推迟到 **J7**（新增模块 + 字段名清单变更须另行显式批准）。→ **J7 已落地**，见 [`J7-STATUS.md`](J7-STATUS.md)（不新增模块）。
- **B15**：`http` 不是模型可见工具，误报已撤销，无需处理。

### 8.6 本批次后的基线

默认 reactor **449 通过 / 1 跳过**（13 个 reactor 项全 SUCCESS）；含按需 starter **463/1**（14 个 reactor 项）。13+1 个模块依赖审计全 `No dependency problems found`；C6 显式 12 模块扫描 `import org.springframework` = 0、`import java.util.regex` = 0。

> 之后 **B16（Jackson 3 升级）+ J7（技能机制）** 又把基线推到 **525 / 1**（默认 reactor 13 项）与 **542 / 1**（含 starter 14 项），当前权威口径见 [`J7-STATUS.md`](J7-STATUS.md) §2。

模块计数：core 7 / llm 51 / fs 37 / sandbox 28（1 skip）/ tool 98 / session 43 / hook 23 / execpath 40 / context 54 / driver 32 / policy 18 / sdk 19 / starter 14。

### 7.6 人工评审辅助（评审表）

新增 `docs/contract/REVIEW-SHEET.md`，把 G1–G6 需要人判定的部分摊成逐行签字表：

- **§1 七条不变式**：每条给契约句、违反后果、Java 落点、守护测试（`类.方法`）、机械门、判定栏；
- **§2 十条机制**：机制要点 × 落点 × 证据 × 判定；
- **§3 三协议口径对照**：结束标记 / 结束原因 / 截断判定 / 空结束原因 / 输入 token 口径 / `freshInput` / 总计 / 鉴权 / 工具调用与回执形状，逐协议判定「一致 / 有异议」；
- **§4 Java 侧 25 条自创机制（SI-1 … SI-25）**：逐条判「接受 / 需改 / 需讨论」，评审重点是**代价是否被诚实声明**；
- **§5 12 条诚实边界**：逐条判「接受 / 需补 / 需人做」（含 Go 只读授权、Seatbelt 真机、单轮 CLI、Spring Starter 等）；
- **§6 G1–G6 里程碑门** + **§7 签署栏** + 附评审前建议跑的命令。

**表内所有 `类.方法` 引用已用脚本逐条核实在仓库中真实存在**（含条数声明：`ObserveGateTest` 11、`SandboxedFsTest` 18、`RootSetTest` 8、`CompactionBudgetTest` 7、`CompactionCutPointTest` 3、`PreviousSummaryTest` 3），核实时改掉两处错引（`FileToolsTest.l3_15_*` 展开为 4 个真实方法名、`registryListIsZeroReflectionAndStableOrder` 实际在 `ExecPathTest` 而非 `ToolRegistryTest`）。**评审人不会追到假名字。**
