# MoS4J 任务列表

> 配套：[整体方案 `mos4j-port-plan.md`](mos4j-port-plan.md)（为什么这么设计）、[推进方案 `mos4j-execution.md`](mos4j-execution.md)（怎么推进）。
> 本文件是**执行清单**：可勾选、每条有产出物与验收。
> 路线已定：**A（全量 Java 移植）**，驱动力 = 团队技术栈（2026-11-03 拍板）。

---

## 0. 使用说明

**图例**：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 完成 · `[!]` 阻塞（须写明卡在哪）

**字段**：
- **产出**：这项做完，仓库里多了什么（文件 / 模块 / 语料）。
- **验收**：怎么算做完（可机械判定优先——`mvn -q verify` 绿、语料比对通过）。
- **估算**：净开发人日（不含评审、联调、返工）。日历换算见 §8。

**纪律**（每条任务都适用）：
1. **先读契约再写码**：动某个机制前先读整体方案对应小节 + Go 侧对应文件。
2. **每条任务自带测试**：没有测试的任务不算完成（MoS 的不变式是**合入门**，不是愿望）。
3. **凡涉及不变式的改动，PR 描述必须勾选对应不变式 + 对应场景测试**（PR 模板强制）。
4. **拿不准机制怎么做 → 看 MoS 怎么做、为什么这么做**，不自创。自创必须登记（理由 + 风险 + 对位分析）。

---

## 1. 贯穿全程：工程基建（不单列人日，含在各里程碑内）

- [x] **T-INF.1** Maven 多模块骨架（父 POM + 13 个 artifactId + `maven-compiler-plugin` release 25 + `maven-surefire`）
  - 产出：`pom.xml`（父）、各模块 `pom.xml`、`.mvn/`、`mvnw`
  - 验收：`mvn -q verify` 全绿；`mvn dependency:tree` 运行时依赖只有 `jackson-databind` / `jackson-annotations` / `re2j`
  - **落地**：父 POM + 12 个 SDK 模块（13 个 artifactId 含父聚合）；`--release 25`；Maven Wrapper 3.3.4；运行时依赖实测只有 Jackson（databind/annotations/core）+ RE2J。
- [x] **T-INF.2** 依赖审计进 CI（禁 Spring/Guava/ORM/Lombok 进 SDK 模块）
  - 产出：CI 步骤或 `maven-enforcer-plugin` 规则
  - 验收：故意加一个违规依赖 → CI 红
  - **落地**：父 POM 的 `maven-enforcer-plugin`（`bannedDependencies`：Spring / Guava / Lombok / Hibernate / MyBatis / Logback / SLF4J）+ CI 的 C2b 步骤（12 模块 `dependency:analyze -Dmdep.analyze.failOnWarning=true`）。
    **负向验证**：往 `mos4j-policy` 临时加 `org.slf4j:slf4j-api` → 构建在 enforcer 处变红（`org.slf4j:slf4j-api:jar:2.0.13 <--- banned via the exclude/include list`），已回滚。
- [x] **T-INF.3** 模块边界检查（ArchUnit 或等价扫描）
  - 产出：`BoundaryRulesTest`
  - 验收：能力层 import 驱动层/装配层 → 测试红；`mos4j-session` 与 `mos4j-llm` 的**共享值类型是同一批类**（断言类身份）
  - **落地**：`mos4j-sdk/src/test/java/net/hegq/mos4j/arch/BoundaryRulesTest`（7 条；不用 ArchUnit——引依赖会破依赖纪律，用 JDK 自带文件扫描 + XML 解析实现同等门禁）。覆盖：①能力层不得 import 驱动层/装配层；②驱动层不得 import 装配层；③pom 依赖方向与分层一致；④协议层不得依赖日志层；⑤共享值类型（Message/Role/ToolCall/Usage/InputTokenDetails/OutputTokenDetails）全局恰好一份定义；⑥投影产出的就是协议层的 Message/ToolCall 类（类身份断言）；⑦共享词汇表只在 `mos4j-core`。
    **负向验证**：临时给 `mos4j-policy` 加驱动层依赖 + `import net.hegq.mos4j.driver.RunContext` → ②③两条规则同时变红并指到具体文件，已回滚。
- [x] **T-INF.4** PR 模板（不变式勾选 + 场景测试勾选 + 自创机制登记）
  - 产出：`.github/pull_request_template.md`
  - 验收：空模板 → 提交时缺勾选项被指出
  - **落地**：`.github/pull_request_template.md`（不变式勾选 + 场景测试 + 自创机制登记三段）
- [x] **T-INF.5** 贡献指南（语义对齐 MoS 的判定流程 / 不做什么 / 如何提 PR）
  - 产出：`CONTRIBUTING.md`
  - 验收：新人按指南能独立完成一个「照契约重写」任务
  - **落地**：`CONTRIBUTING.md`（语义重写口径 / 依赖纪律 / fail-closed / 字段名冻结 / PR 流程）
- [x] **T-INF.6** 公共 API 的 Javadoc 质量门（写「不这么做会出什么事」，不只写「这是什么」）
  - 产出：Javadoc 约定 + 抽查清单
  - 验收：抽查 10 个公共类型，Javadoc 含「为什么/后果」段
  - **落地**：Javadoc 约定写进 `CONTRIBUTING.md`；抽查 10 个公共类型（`CancellationToken` / `ToolArgs` / `SandboxedFs` / `ExecPath` / `Session` / `ReActDriver` / `RetryingChatModel` / `OpenAiChatModel` / `Mos4j` / `Cli`），均含「为什么/不这么做会出什么事」段。
- [x] **T-INF.7** CI 流水线（verify + 依赖审计 + 边界检查 + 字段名守护 + 场景测试）
  - 产出：CI 配置
  - 验收：故意改错一个事件字段名 → CI 红（**这是整个防漂移体系的核心门**）
  - **落地**：`.github/workflows/ci.yml`（JDK 25 矩阵）；C1 `verify` / C2a enforcer / C2b 依赖审计（12 模块）/ **C3 边界检查（单跑 `BoundaryRulesTest`）** / C4 字段名守护 / C5 场景语料 / C6 源码扫描。

---

## 2. J0 契约冻结 + 自建语料（~1 周）

**验收门 G0**：§2 的 G0 清单全部通过；`mvn -q verify` 在**空实现**下能跑。

> 定位（整体方案 §0.0）：参考实现，无跨语言对拍。L1 报文从 Go 测试字面量抄，期望值 Java 自算；L2/L3 Java 自建；无导出器。

- [x] **T0.1** 七条不变式契约定稿
  - 产出：`docs/contract/INVARIANTS.md`（每条：守住什么 / 不这么做会出什么事 / Java 侧约束 / 验证方式）
  - 验收：七条全覆盖，每条 ≥1 条后果说明 + ≥1 条验证方式；评审通过
  - 估算：1.5 人日 ｜ 依赖：无
  - **落地**：`docs/contract/INVARIANTS.md`（I1–I7，各含后果说明与验证方式）
- [x] **T0.2** 十个机制契约定稿
  - 产出：`docs/contract/MECHANISMS.md`（含整体方案 §2 表格 + 文案契约引用）
  - 验收：十条全覆盖；每条有「Java 侧保住的方式」与「怎么验证」
  - 估算：1 人日 ｜ 依赖：T0.1
  - **落地**：`docs/contract/MECHANISMS.md`（M1–M10）
- [x] **T0.3** 接口契约定稿（事件字段名表 + 关键类型签名 + 文案表）
  - 产出：`docs/contract/API.md`
  - 验收：字段名与静态抽取清单一致（T0.4 产出，不靠人眼）
  - 估算：1 人日 ｜ 依赖：T0.1
  - **落地**：`docs/contract/API.md`（含 §3.7 三协议 + 硬化 + 门面）
- [x] **T0.4** 字段名静态抽取 + 冻结清单（一次性，不随 Go 演进重抽）
  - 产出：`docs/contract/fieldnames.json`（从 Go 源码抽 `json:"..."` tag）+ `tools/fieldname-extract/`（脚本）
  - 验收：清单生成成功；冻结后变更走 PR 评审
  - 估算：0.5 人日 ｜ 依赖：无
  - **落地**：`docs/contract/fieldnames.json`（冻结清单，SHA-256 `fd7b6c81…`）；抽取脚本在 Go 仓库只读未获批时以手录 + 守护测试代替（诚实边界见 J6-STATUS §5）——**2026-11-06 授权后已用 Go 源码重跑一遍抽取并逐项比对**：75 条名单与 SHA 不变，发现 4 处漂移（Go 新增 `skill`/`description`/`dir` 三个技能字段、`callId` 的 `options` 过期），**名称不受影响**，明细见 `INVARIANT-TESTS.md` §3.5
- [x] **T0.5** L1 协议场景测试（报文照抄 Go 测试字面量，期望值 Java 自算）
  - 产出：`src/test/resources/llm/*.txt`（SSE 报文）+ 场景测试骨架（先标 `@Disabled`，实现后在 J1 点亮）
  - 用例：正常流 / **只发 message_stop** / **真截断** / thinking / 工具调用流 / usage 分项和=总计 / usage delta 赢过 start / OpenAI 分片累加 / OpenAI 只发 [DONE] / Gemini 流
  - 验收：每份报文与 Go 测试里的字面量一致；断言语义与 Go 测试一致（期望值 Java 算）
  - 估算：1 人日 ｜ 依赖：T0.3
  - **落地**：三协议语料 `mos4j-llm/src/test/resources/llm/{openai,anthropic,gemini}/*.sse` + `L1OpenAiScenariosTest`(5) / `L1AnthropicScenariosTest`(12) / `L1GeminiScenariosTest`(6)，`@Disabled` 已归零
- [x] **T0.6** L2 会话场景设计（Java 自建）
  - 产出：场景测试骨架 + 场景说明（正常多轮 / 含压缩 / 含审批 / 孤儿 turn / 孤儿 tool/call / 迭代压缩）
  - 验收：场景清单与整体方案 §6.2 一致
  - 估算：0.5 人日 ｜ 依赖：T0.3
  - **落地**：`docs/contract/L2-SCENARIOS.md`（16 条）
- [x] **T0.7** L3 决策场景设计（Java 自建）
  - 产出：场景测试骨架 + 场景说明（未知工具 / 守卫拒绝 / 载荷冻结违约 / 审批人工拒绝 / 无审批通道 ask / 参数非法 / 并发反序 / 错误只可加重）
  - 验收：拒绝文案断言照抄 §4.3 文案表
  - 估算：0.5 人日 ｜ 依赖：T0.3
  - **落地**：`docs/contract/L3-SCENARIOS.md`（D1–D4 段）
- [x] **T0.8** 字段名 Java 守护测试
  - 产出：`FieldNameContractTest`
  - 验收：序列化键集合与冻结清单一致；**故意改错一个 → 红 → 改回**
  - 估算：0.5 人日 ｜ 依赖：T0.4
  - **落地**：`mos4j-core/FieldNameContractTest` + `mos4j-session/EventFieldNameGuardTest`（双层守护：常量层 + 序列化层）
- [x] **T0.9** 不变式测试映射表（Go 测试 → Java 测试）
  - 产出：`docs/contract/INVARIANT-TESTS.md`
  - 验收：Go 侧每条不变式相关测试都有 Java 对应项；译不过来的写明原因
  - 估算：0.5 人日 ｜ 依赖：T0.1
  - **落地**：`docs/contract/INVARIANT-TESTS.md` + `INVARIANT-TEST-MAP.md`（无空项门）；Go 侧原测试名已于 2026-11-06 逐条补齐（137 个，机器复核全部存在）
- [x] **T0.10** 构建骨架 + CI 门接上（T-INF.1/2/3/7 的最小版）
  - 产出：空实现的 `mvn -q verify` 通过；CI 跑起来
  - 验收：`mvn -q verify` 绿；CI 门存在
  - **落地**：T-INF.1/2/3/7 已全部落地（含 C3 边界门与 CI 接线）
  - 估算：1 人日 ｜ 依赖：T-INF.1

**G0 验收清单**：
- [x] `mvn -q verify` 在空实现下绿（当前基线 537 通过 / 6 跳过，13 个 reactor 项全 SUCCESS；含按需 starter 为 554/6、14 个 reactor 项）
- [x] L1 报文已抄入测试资源；L2/L3 场景骨架就绪（`L1-SCENARIOS.md` / `L2-SCENARIOS.md` / `L3-SCENARIOS.md`）
- [x] 字段名守护测试能**发现**故意改错的字段名（`FieldNameContractTest` + `EventFieldNameGuardTest` 双层守护）
- [ ] D1/D2/D6 三份文档定稿并评审通过（**人工评审未记录**，见 J6-STATUS §5.5 诚实边界）
  - **评审辅助已备好**：`docs/contract/REVIEW-SHEET.md`——七条不变式 × 十机制 × 三协议口径 × 25 条自创机制 × 12 条诚实边界 × G1–G6 里程碑门，逐行含「契约句 / 违反后果 / Java 落点 / 守护测试 / 机械门 / 判定」与签署栏；表内引用的 `类.方法` 已逐条核实在仓库中真实存在。**填完表即可勾此项**（本项只差人填，不差产物）。

---

## 3. J1 最小垂直切片（3–4 周）

**目标**：跑通一个「读文件 → 作答」的完整 turn；L1 + L2 场景测试通过。

- [x] **T1.1** `mos4j-llm` 共享值类型（Message / ToolCall / Usage / ContentPart）
  - 产出：`net.hegq.mos4j.llm.model.*`
  - 关键：`ToolCall.args` 保持 **String 原始 JSON 文本**（不解析成对象）；JSON tag 与冻结清单一致（照抄 Go）
  - 验收：T0.8 的字段名守护测试通过
  - 估算：1 人日 ｜ 依赖：T0.3
- [x] **T1.2** ChatModel 抽象（Capability / Request / Response / StreamChunk / ErrStreamTruncated）
  - 产出：`net.hegq.mos4j.llm.*`
  - 估算：0.5 人日 ｜ 依赖：T1.1
- [x] **T1.3** OpenAI 协议 DTO（请求 / 响应 / 流式事件）
  - 产出：`net.hegq.mos4j.llm.protocol.openai.*`
  - 估算：1 人日 ｜ 依赖：T1.1
- [x] **T1.4** OpenAI 非流式 `chat`
  - 验收：L1 语料的非流式用例通过
  - 估算：0.5 人日 ｜ 依赖：T1.3
- [x] **T1.5** OpenAI 流式 `chatStream`
  - 关键：`[DONE]` 结束标记判定；**`tool_calls.arguments` 按 index 累加后再解析**；截断 → `ErrStreamTruncated`；**结束原因为空 ≠ 截断**
  - 验收：L1 全部 OpenAI 用例通过
  - 估算：1.5 人日 ｜ 依赖：T1.3
- [x] **T1.6** usage 归一化（`freshInputTokens` 去缓存口径 / `cacheHitRatio` 分母用去缓存口径 / **分项和 = 总计**按各协议口径）
  - 验收：不变式测试「分项和 = 总计」绿
  - 估算：0.5 人日 ｜ 依赖：T1.4
- [x] **T1.7** 传输层（共享 HTTP envelope + 结构化 `StatusError` + 状态码穿透）
  - 估算：0.5 人日 ｜ 依赖：T1.2
- [x] **T1.8** `mos4j-tool`：Tool 接口 + Registry + Schema 生成
  - 关键：`@ToolParam(desc, required)` + 反射，**注册时缓存**；`required` **只由显式声明决定**；反射失败 **fail-closed 报错**，不静默生成空 schema；支持直接给 Schema（Map）
  - 验收：守护测试——① 无 `title`/`$defs` ② 连续两轮 `List()` 不再反射 ③ 反射失败 → 注册抛错
  - 估算：1.5 人日 ｜ 依赖：T1.1
- [x] **T1.9** 参数容错解析（模型偶发输出 `{…}{…}` 拼接 → 合并顶层对象）
  - 估算：0.5 人日 ｜ 依赖：T1.8
- [x] **T1.10** `mos4j-session` 事件 DTO + **手写 Jackson Serializer**
  - 关键：Go 的 `omitempty` 对 int 0 也省略 → Jackson `NON_EMPTY` 不等价 → **必须手写**（`Event` / `ToolResult` / `Approval` / `Compaction` / `Usage` / `UsageDetails`）
  - 验收：守护测试——`turn=0` 的事件序列化后**不含** `turn` 键；空 `details` 不出现
  - 估算：1.5 人日 ｜ 依赖：T1.1
- [x] **T1.11** Session（`append` fail-fast / `events` / `messages` / `view` / `nextTurn` / `totalUsage` / `contextTokens`）
  - 关键：**落盘失败 → 事件不进内存**（顺序不能反）
  - 验收：写失败测试——持久化抛错 → `append` 抛错且 `events()` 不含该事件
  - 估算：1.5 人日 ｜ 依赖：T1.10
- [x] **T1.12** JSONL Persistence + `Restore` + 孤儿 turn 修复
  - 关键：Jackson 默认不转义 HTML（对齐 Go 的 `SetEscapeHTML(false)`）；尾部半行丢弃；中段损坏报错
  - 验收：孤儿 turn → `Restore` 后合成 `turn/end{interrupted}`
  - 估算：1 人日 ｜ 依赖：T1.11
- [x] **T1.13** 投影自愈（`projectSeq` + 合成中断回执）
  - 关键：合成回执**按声明序补在首个非 tool/result 事件之前**，序号继承声明它的 assistant 事件；**日志不动**
  - 验收：L2 场景全绿（含孤儿 tool/call 用例）
  - 估算：1 人日 ｜ 依赖：T1.11
- [x] **T1.14** `mos4j-driver`：`LoopDriver` 接口 + `RunContext` + 决策器（planner）
  - 估算：0.5 人日 ｜ 依赖：T1.1
- [x] **T1.15** ReAct 驱动
  - 关键：事件序列 `turn/start → user → (assistant → tool/call* → tool/result*)* → assistant → turn/end`；**先全部落 tool/call 再执行**；截断工具调用 fail-closed 拒绝；连续相同调用提醒；能力缺口 fail-closed 且**不写 assistant 事件**
  - 验收：端到端测试——「读文件 → 作答」一个 turn 完整跑通
  - 估算：1.5 人日 ｜ 依赖：T1.11, T1.14
- [x] **T1.16** `mos4j-execpath`：preflight / dispatch / finalize + `Outcome`
  - 关键：**回填序 = 调用序**；错误即结果（不返回 error）；逐条独立裁决；注册表为 nil 整批判失败不 panic
  - 验收：并发组内反序完成 → 输出顺序 == 调用顺序
  - 估算：1.5 人日 ｜ 依赖：T1.8
- [x] **T1.17** `Config` / `Agent` / `Describe()` 快照（**落在 `mos4j-driver` 的 `net.hegq.mos4j.agent`**，见整体方案 §3.1 特例 4）
  - 关键：`Describe()` 写明**装了什么 + 没装什么**
  - 估算：1 人日 ｜ 依赖：T1.15, T1.16
- [~] **T1.18** L1/L2 场景点亮 + 端到端回归
  - 验收：**OpenAI L1 全部 + L2 除压缩器相关外全部**场景通过（口径见 G1 清单）
  - 现状（2026-11-05）：OpenAI L1 5/5 + 语料完整性 2 条绿；Anthropic 10 / Gemini 4 仍 `@Disabled`（**明确留到 J6**，不再计入 J1 门）。L2 13/16；L2-9/11/12 依赖压缩器（J4）。端到端 1/1。详见 `docs/contract/J1-STATUS.md`
  - 估算：1 人日 ｜ 依赖：T1.5, T1.13, T1.17

**G1 验收清单**（现状：2026-11-05；**G1 人工评审未做**）：
- [x] 一个完整 turn 端到端跑通（读文件 → 作答）——`mos4j-driver` 的 `EndToEndTurnTest`。**口径（2026-11-05 维护者拍板）**：J1 的「读文件」允许用**测试内工具**验收，生产级文件工具属 **J3 的 T3.11**（jail 双校验 + 写前观察门），不在 G1 门内
- [x] L1 全绿——口径（2026-11-05 维护者拍板）：**G1 的「L1 全绿」= OpenAI L1 全绿**（5/5）+ 语料完整性 2 条；**Anthropic / Gemini 的适配器与 L1 场景明确留到 J6**（T6.1–T6.3），G1 不再因这 14 条跳过而挂起
- [~] L2 全绿——13/16；缺 L2-9/11/12（依赖压缩器，属 J4）；「压缩遮蔽」「合成中断回执」已绿
- [x] 依赖审计只有 3 个运行时依赖——2026-11-05 重跑 `dependency:tree -Dscope=runtime`：全 reactor 外部依赖只有 `jackson-databind` / `jackson-annotations` / `re2j`（+ databind 传递的 jackson-core）

---

## 4. J2 安全纵深（2–3 周）

**目标**：载荷冻结、双轨制、fail-closed 全部有测试；L3 场景测试通过。

- [x] **T2.1** HookBus（waterfall：注册顺序逐层委托 / 短路 / 替换；执行前快照链；`ErrNilHookEvent`；未表态 = allow）
  - 验收：钩子返回 `(null, null)` → 显式报错，链不崩
  - 产出：`mos4j-hook` 的 `HookBus` / `HookEvent` / `HookRejectedException`
  - 证据：`HookBusTest`（8 条：顺序 / 短路 / 替换 / null 显式报错 / 拒绝传播 / 坏插件转拒绝 / 快照独立）
  - 估算：1.5 人日 ｜ 依赖：T1.1
- [x] **T2.2** GuardBus（serial 单调：首个拒绝即整体拒绝并短路）
  - 关键：**GuardBus 与 HookBus 的 API 互不出现对方类型**（结构性保证「钩子无法绕过守卫」）
  - 产出：`GuardBus` / `GuardEvent` / `GuardDecision`（ALLOW / DENY / ASK / CANCEL）
  - 证据：`GuardBusTest`（7 条）+ `DualTrackStructureTest`（3 条：类型不同 + 双向不出现对方类型）
  - 估算：1 人日 ｜ 依赖：T1.1
- [x] **T2.3** 载荷冻结（preflight + execute 两处校验）
  - 关键：钩子改写 name/args/id 任一 → 该调用被拒，理由含「载荷冻结」
  - 验收：L3「载荷冻结违约」场景通过（理由与 §4.3 一致）
  - 产出：`ExecPath` 的 preflight 冻结检查 + execute 前二次校验
  - 证据：`ExecPathSecurityTest.l3_3_*`（4 条：三个字段各一条 + 违约不执行）
  - 估算：1 人日 ｜ 依赖：T2.1, T1.16
- [x] **T2.4** 策略层（名字级）：`Allow`/`Deny` 名单 + `*` 通配（整串锚定）+ 空集全禁 + `Visible` 与 `Check` 同一份名单
  - 关键：`Deny` 优先；`Allow` **未配置 = 不限制**，配置了空集 = **全禁**（两个不同的空语义）
  - 产出：`mos4j-policy` 的 `ToolNamePolicy`（RE2J，整串锚定）
  - 证据：`ToolNamePolicyTest`（8 条）
  - 估算：1.5 人日 ｜ 依赖：T2.2
- [x] **T2.5** 策略层（参数级）：`ParamPolicy` + 三档裁决（allow/deny/ask）+ **只读载荷快照** + 参数解析失败 = 拒绝
  - 产出：`ParamPolicy`（三档裁决取最严；默认规则只作用于无单独规则的参数）
  - 证据：`ParamPolicyTest`（10 条）
  - 估算：1.5 人日 ｜ 依赖：T2.2
- [x] **T2.6** 人工审批：四档结果（approved/rejected/cancelled/unavailable）+ `Answerer` + `Service`
  - 关键：**未装配 Answerer → ask 降级为拒绝**（fail-closed，不「先执行再问」）；批准**只对本次调用生效**（不缓存）；通道报错 ≠ 人拒绝（归一到 unavailable 但带原因）
  - 产出：`mos4j-hook` 的 `ApprovalChannel` / `Answerer` / `ApprovalService`；`mos4j-core` 的 `ApprovalRequest` / `ApprovalResult` / `ApprovalOutcome`
  - 证据：`ApprovalServiceTest`（5 条）+ `ExecPathSecurityTest`（通道异常归 unavailable / 每次调用都问）
  - 估算：1.5 人日 ｜ 依赖：T1.16
- [x] **T2.7** 审批审计事件（`approval/asked` + `approval/decided`，同 id 配对）
  - 关键：**审计写失败即中止**
  - 产出：`mos4j-core` 的 `ApprovalAudit` + `mos4j-session` 的 `SessionApprovalAudit`（reason 只在 asked）
  - 证据：`SessionApprovalAuditTest`（5 条）+ `ExecPathSecurityTest.auditWriteFailureAbortsTheCall`
  - 估算：0.5 人日 ｜ 依赖：T2.6
- [x] **T2.8** 拒绝文案固定（含「不许绕」那句）
  - 产出：文案常量（实际类名 `net.hegq.mos4j.core.Texts`，统一出口）+ L3 场景断言
  - 关键：文案**照抄 Go**（§4.3）；语义等价的小差异允许，须 PR 说明
  - 验收：L3「审批人工拒绝」理由与文案表一致
  - 证据：`ExecPathSecurityTest.l3_5_*` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence`
  - 估算：0.5 人日 ｜ 依赖：T2.6, T0.7
- [x] **T2.9** 错误只可加重不可减轻（post-execute 可把成功改失败，不可把失败洗成成功）
  - 验收：L3「错误只可加重」场景通过
  - 产出：`PostHook`（接口层面只有加重能力）+ `ExecPath` finalize 单向判定
  - 证据：`ExecPathSecurityTest.l3_8_*`（2 条）
  - 估算：0.5 人日 ｜ 依赖：T1.16
- [x] **T2.10** 零开销降级测试（null 总线 ≡ 未注入；未装配扩展点时行为完全一致）
  - 证据：`ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled` / `emptyBusesBehaveLikeNotInstalled` / `noSecurityMeansSameBehaviorAsBefore`
  - 估算：1 人日 ｜ 依赖：T2.1, T2.2
- [x] **T2.11** Go 不变式测试逐条译成 Java 测试（含 `TestGuardVerdictNotBypassableByHooks`）
  - 产出：`docs/contract/INVARIANT-TESTS.md`（逐条台账，Go 侧列已逐条抄录真实测试名）+ Java 测试
  - 验收：映射表里每条都有 Java 对应测试且绿 —— **J2 范围内全绿**；Go 侧原测试名已于 2026-11-06（Go 只读授权后）逐条抄录，共 137 个，**机器复核全部真实存在**（`grep -rn "^func Test" <Go 包>`）
  - 估算：1.5 人日 ｜ 依赖：T0.9
- [x] **T2.12** Go ↔ Java 首轮逐项对拍（2026-11-06 起）
  - 产出：`docs/contract/INVARIANT-TESTS.md` §3（对拍发现登记表，每条带 Go 文件行号证据）
  - 已核：七条不变式 + 十条机制的 Go 原测试名（137 个）；三协议流完整性/usage 口径；文件 jail 双校验；写前观察；截断/压缩；工具名清单
  - **已发现并已裁决（2026-11-06）**：① 工具名 `read_file`/`write_file`/`edit_file` vs Go `read`/`write`/`edit`；② 参数形状：`edit`（Go `edits[]`）、`grant_access`（Go `path`+`mode`）、`grep`（Go 多 `glob` 过滤）、`shell`（Go 多 `description`）
  - **裁决与落地**：**B13-1** 工具名改为 `read`/`write`/`edit`（Java 类名不变）；**B13-2** `grep.glob` / `glob.limit` / `ls.limit` / `shell.description` / `tool_search.limit`；**B13-3** `edit` 改 `edits[]`（数组/单对象/JSON 字符串、按原文定位、重叠报错、原子落盘）；**B13-4** `grant_access` 保留 Java 形状、**B13-5** 保留 deferred 两步 = 有意差异；**B14** skill 机制推迟到 J7；**B15** 误报已撤销
  - **机器门**：工具名与参数名清单由 `mos4j-tool` 的 `ToolContractTest` 守护（**B13 时 13 个**；J7 后升为 **14 个**，`skill` 条件注册 + 参数名 + 必填参数）
  - **额外发现（同一轮）**：① Go 侧模型可见工具 13 个 vs Java 13 个（Go 独有 `skill`，Java 独有 `activate`；三个文件工具名不一致）；② Go 的 `agent/skill` **技能机制** Java 完全没有（机制级缺口，不是命名差异）；③ `fieldnames.json` 与当前 Go 源码有 4 处漂移（3 个 skill 相关字段缺失 + `callId` 的 options 过期；**名称与 SHA 不受影响**）
  - **已更正的误报**：曾把 `http` 列为 Go 的模型可见工具——实为 `HTTPFetcher` 的实现标识（`web.Tools()` 只产出 `web_search` + `web_fetch`），无缺口
  - 估算：1 人日 ｜ 依赖：Go 仓库只读授权（已有）
- [x] **T2.13** Go 的「技能（skill）」机制：**已裁决**——本轮只登记，**推迟到 J7** 单独实现（新增模块与字段名清单变更须另行显式批准）；**J7 已落坐落地**（见 §12 T7.2–T7.11）
  - Go 侧证据：`agent/skill/{skill.go,tool.go,state.go,render.go}`；工具名 `skill`；事件 `skill/activate`（载荷 `skill{name,description,dir}`）；清单渲染 `<available_skills>` 带字节预算三级降级；`allowed-tools` 只解析不生效
  - Java 侧现状：`grep -rn "skill" mos4j-*/src/main` = **0 命中**；Java 的 `activate` 是 deferred 工具激活，语义不同
  - **为什么不能默认当「等价」**：一个是「技能正文进上下文 + 审计留痕」，一个是「把延迟加载的工具定义放进清单」
  - 补齐则需：新模块 `mos4j-skill`（目录发现 + `SKILL.md` frontmatter 解析 + 清单渲染 + `skill` 工具 + `skill/activate` 事件）+ 字段名清单增补（`skill`/`name`/`description`/`dir`）+ 事件类型新增
  - 估算：2 人日（补齐）/ 0.2 人日（登记差异）｜ 依赖：人工裁决

**G2 验收清单**：
- [x] L3 全绿（拒绝文案与 §4.3 一致）—— D1 段 L3-1 … L3-10 全绿；D2 / D3 段属 J3（已在 J3 点亮：D2 段 9/9、D3 段 2/2）
- [x] 「钩子无法绕过守卫」有结构性测试（不是约定，是类型层面的）—— `DualTrackStructureTest`
- [x] 载荷冻结违约被拒
- [x] 审批未装配 → 拒绝
- [~] Go 不变式测试逐条译过（映射表无空项）—— Java 侧无空项；Go 侧原测试名待授权后补齐（见 `INVARIANT-TESTS.md` §3）

---

## 5. J3 文件与沙箱（2–3 周）

- [x] **T3.1** `mos4j-fs` jail：**Lexical + Realpath 双校验**
  - 关键：**读也走 Realpath**（否则根内 symlink 指向 `/etc/passwd` 就能读出边界）
  - 估算：2 人日 ｜ 依赖：无
  - 证据：`mos4j-fs` 的 `SandboxedFs`（逐段解析）+ `SandboxedFsTest`（18 条）
- [x] **T3.2** 新建路径：向上找**最深存在祖先**再拼回后缀
  - 关键：`root/escape/new.txt`（叶子不存在但中间组件是越界链接）必须被拒
  - 估算：1 人日 ｜ 依赖：T3.1
  - 证据：`SandboxedFsTest.l3_13_leafMayNotExistButMiddleComponentStillChecked` / `newFileUnderNormalDirectoryIsAllowed`
- [x] **T3.3** symlink 逃逸三形态 + 深度上限 32（防环，提前拒不死循环）
  - 验收：目标是链接 / 中间组件是链接 / 链接环 → 三类各有测试
  - 估算：1 人日 ｜ 依赖：T3.1
  - 证据：`SandboxedFsTest.l3_12_symlinkTargetOutsideRootIsDenied` / `l3_13_symlinkAsMiddleComponentIsDenied` / `l3_14_symlinkLoopIsDeniedInsteadOfHanging`
- [x] **T3.4** `RootSet`（活根集合：并发安全 + 锚点稳定 + 加根对**下一次调用**立即生效 + 变更留痕）
  - 关键：加根是**追加**，锚点不变（锚点漂移比「新根只能用绝对路径」更危险）
  - 估算：1.5 人日 ｜ 依赖：T3.1
  - 证据：`RootSet`（读写锁 + 不可变快照 + 挂载首段消费）+ `RootSetTest`（8 条）
- [x] **T3.5** 结构化拒绝（`PathDeniedError` 带「该操作允许的根」）
  - 关键：**说「哪些根允许」比说「什么模式」有用**
  - 估算：0.5 人日 ｜ 依赖：T3.1
  - 证据：`PathDeniedError`（`requestedPath` / `allowedRoots` / `reason`）+ `SandboxedFsTest.denialCarriesTheAllowedRoots`
- [x] **T3.6** `mos4j-sandbox`：`Local` Runner（工作目录 + 超时缺省/上界 + 输出上限 + 环境裁剪）
  - 关键：工作目录**由宿主固定，不交给模型指定**；超时 = `min(请求 ?? 缺省, 上界)`
  - 估算：1.5 人日 ｜ 依赖：无
  - 证据：`LocalRunner` + `LocalRunnerTest`（14 条）
- [x] **T3.7** 资源限制（`ulimit -t/-v/-f`）+ 平台差异（macOS 跳过 `-v`）
  - 估算：1 人日 ｜ 依赖：T3.6
  - 证据：`ResourceLimits`（`applied()` / `skipped()` 显式报告生效与跳过项）
- [x] **T3.8** `ProcessGroupKiller` 接口 + `DescendantsKiller`（默认）+ **降级声明**
  - 关键：**非原子 = 尽力而为**，文档与 `Describe()` 必须写明；**不许假装等价**
  - 验收：起会 fork 孤儿的脚本 → 断言主进程被杀（**不断言孙子一定死**）
  - 估算：1 人日 ｜ 依赖：T3.6
  - 证据：`DescendantsKiller`（快照后逐个杀、先深后浅）+ `SandboxBoundaryTest.descendantsKillerDeclaresItsWeakness`
- [x] **T3.9** 路径强制隔离：`PathScope`（只收窄不放开）+ macOS Seatbelt 后端
  - 关键：写根含 `/` = 显式全开（逃生门）；不支持的平台**明确报错**，不退化成「没隔离的假装隔离」
  - 估算：1.5 人日 ｜ 依赖：T3.6, T3.4
  - 证据：`PathScope` / `PathScopes` / `SeatbeltScope` + `SandboxBoundaryTest`（13 条）
  - ⚠️ **强制效果未在本环境验证**：当前环境不允许应用 Seatbelt（`sandbox_apply: Operation not permitted`），运行时用例如实标为**跳过**
- [x] **T3.10** 写前观察门 + 版本令牌
  - 关键：令牌降级为 `dev:ino:size:mtimeNs`（用 `Files.getAttribute(path,"unix:dev"/"unix:ino")`，非 Unix 退化 `size:mtimeNs`）；**新建文件不需先读**（createIfAbsent）；**edit 一律要求先读**
  - 验收：三类场景各有测试（没见过就写 / 见过但已改 / 见过且未改）
  - 估算：1.5 人日 ｜ 依赖：T3.1
  - 证据：`ObserveGate`（结构化原因码）+ `VersionToken` + `ObserveGateTest`（11 条）
- [x] **T3.11** 文件工具 `read` / `write` / `edit`（原子写 + 观察门接线 + 行寻址 + 回执字段）
  - 关键：`edit` 唯一性命中（多处命中报错，不做 replace_all）；行尾归一 + BOM 保留；拒绝二进制
  - 估算：1.5 人日 ｜ 依赖：T3.10, T1.8
  - 证据：`ReadFileTool` / `WriteFileTool` / `EditFileTool` / `TextContent` + `FileToolsTest`（22 条）；`ShellTool` + `ShellToolTest`（6 条）

**G3 验收清单**：
- [x] 路径越界 / symlink 逃逸 / 丢失更新 三类事故各有测试
      → `SandboxedFsTest`（L3-11/12/13/14）+ `ObserveGateTest`（L3-15/16/17）+ `FileToolsTest`（读写越界与 symlink 中间组件）
- [x] 进程组降级写进文档 + `Describe()` 快照
      → `DescendantsKiller.description()` + `SandboxBoundary.caveats()` + `DescribeSandboxTest.describeReportsFileAndSandboxBoundaries`
- [x] 写前观察门三类场景绿
      → `ObserveGateTest`（没见过就写 / 见过但已改 / 见过且未改 / 已删 / 新建免读）

---

## 6. J4 上下文工程（2 周）

- [x] **T4.1** 截断（行/字节双限，默认 2000 行 / 50KB；按工具定向保留：`read` 留头、`shell` 留尾、其余两头各半；UTF-8 安全截断）
  - 估算：1.5 人日 ｜ 依赖：无
  - 落点：`mos4j-context` 的 `Truncation` / `TruncatedOutput`；守护 `TruncationTest`（9 条）+ `ToolOutputLimiterTest`（5 条）
  - 口径：**字节硬上限含截断标记本身**；不超限时逐字原样返回；保留行数只数内容行
  - 终检：支持更小的字节上限（`apply(toolName, content, byteLimit)`），给框架自己的注解留预算
- [x] **T4.2** 截断落盘逃生通道（宿主指定目录，必须在文件 jail 可读根内；落盘失败诚实降级）
  - 估算：0.5 人日 ｜ 依赖：T4.1, T3.1
  - 落点：`SpillStore` / `SpillResult` / `ToolOutputLimiter`；守护 `SpillStoreTest`（3 条）+ `ToolOutputLimiterTest`（5 条）+ `ContextFlowTest.spilledOutputPointsToTheFile`
  - 口径：越界**构造期**就 `PathDeniedError`；落盘原子写（同目录临时文件 + rename）；失败时内容原样返回并明说「未能保存」
  - 终检：模型看到的最终内容 = 截断内容 + 落盘提示，**提示也占字节预算**（两者加起来不超上限）
- [x] **T4.3** 压缩触发：两段式 token 估算（**基座是真实值**：最后一次模型调用的用量；其后新增事件按 chars/4）
  - 关键：`contextTokens > contextWindow - reserveTokens`；未配置 contextWindow 时**永不触发**（fail-closed）
  - 估算：1 人日 ｜ 依赖：T1.11
  - 落点：`Session.contextTokens()`（两段式）+ `CompactionPolicy`；守护 `CompactionBudgetTest`（7 条）
- [x] **T4.4** 压缩切割点（从尾部累计到 `keepRecentTokens`；取**超预算处之后最近的合法切割点**）
  - 关键：**切割点永不落在 tool 结果上**（保留段不能以孤儿回执开头）
  - 验收：构造 tool 结果位于切割点附近的用例 → 切割点自动前移/后移到合法位置
  - 估算：1.5 人日 ｜ 依赖：T4.3
  - 落点：`CutPointFinder`；守护 `CompactionCutPointTest`（3 条）
  - 口径：优先**回退到声明它的 assistant**（调用方与回执一起遮蔽）；找不到声明者则孤儿回执也遮蔽
- [x] **T4.5** 摘要生成（序列化格式 `[User]:/[Assistant]:/[Assistant tool calls]:/[Tool result]:` + `<conversation>` 包裹 + `<previous-summary>` 迭代 + 禁止续写对话 + 禁止调用工具）
  - 估算：1.5 人日 ｜ 依赖：T4.4
  - 落点：`Transcript` / `SummaryGenerator` / `ChatModelSummaryGenerator`；守护 `PreviousSummaryTest`（3 条）
- [x] **T4.6** 压缩事件落库 + 投影遮蔽（摘要进投影作 user 消息；被遮蔽事件**留在日志里不删**）
  - 关键：旧摘要在迭代压缩中被折叠进新摘要，不重复出现
  - 验收：L2 压缩场景通过
  - 估算：1 人日 ｜ 依赖：T4.5, T1.13
  - 落点：`CompactionService` / `CompactionResult` + 驱动接线（每轮决策前调 `maybeCompact`）；守护 `ContextFlowTest`（6 条接线：截断进回执 / 未装配原样 / 落盘给路径 / 压缩先行 / 压缩失败收尾 / Describe 声明装没装）
  - 口径：压缩事件归属**进行中的那一轮**（`CompactionService.compactionTurn()`），不挂到下一轮
- [x] **T4.7** 提示词分节装配（`Section` / `Builder`：Add 严格插入 / Set 覆写 / Complete 整段替换 / Clone 派生 + 中心顺序常量）
  - 关键：**装配期渲染一次**（保 prompt cache）；连续两轮 system 段逐字节一致
  - 估算：1.5 人日 ｜ 依赖：无
  - 落点：`PromptSection` / `PromptSections`；守护 `PromptSectionsTest`（9 条）
- [x] **T4.8** 上下文文件（候选名优先级 + 向上递归 + 同目录 override + 真实路径去重 + 字节预算裁剪 + 降级写进 warnings）
  - 关键：**框架不碰文件系统**（I/O 以 Reader 接口注入）
  - 终检：路径拼接也由 Reader 注入（`ContextFileReader.join`），虚拟文件系统宿主可覆写分隔符
  - 估算：1.5 人日 ｜ 依赖：T4.7
  - 落点：`ContextFileReader` / `ContextFiles` / `ContextFile`；守护 `ContextFilesTest`（9 条）
- [x] **T4.9** `TaskState` + `Auditor`（审计门白名单：只收环境自证的只读工具输出）
  - 估算：1 人日 ｜ 依赖：T1.11
  - 落点：`TaskState` / `Auditor`；守护 `AuditorTest`（5 条）
  - 口径：白名单是**封闭集合**（不允许通配符）；非白名单工具的输出不进状态并写明理由

**G4 验收清单**：
- [x] 压缩遮蔽行为正确（摘要在前、被遮蔽事件留日志、旧摘要折叠）—— `CompactionBudgetTest.maskingDoesNotRewriteTheLog` / `PreviousSummaryTest.previousSummaryIsFoldedNotAccumulated`
- [x] 切割点永不落在 tool 结果上（有测试）—— `CompactionCutPointTest.cutPointNeverLandsOnAToolResult` / `projectionStaysValidAfterCompaction`
- [x] 连续两轮 system 段逐字节一致 —— `PromptSectionsTest.renderIsStable`

---

## 7. J5 工具全集与联网（2 周）

- [x] **T5.1** `grep`（**RE2J**，非法正则直接报错不静默当字面量；`limit` 默认 100 / 硬上限 1000；二进制跳过；单文件 1MB / 单次 2 万文件上限）
  - 落点：`mos4j-tool/builtin/GrepTool`；守护 `BuiltinSearchToolsTest`（grep 5 条）
- [x] **T5.2** `glob`（`*` / `**` / `?` / `[...]` / `{a,b}`；不含 `/` 按文件名匹配、含 `/` 按相对根路径匹配）
  - 落点：`GlobPattern`（模式 → RE2J 正则，括号不配对报错）+ `GlobTool`；守护 `BuiltinSearchToolsTest`（glob 5 条）
- [x] **T5.3** `ls`（含隐藏项、按名排序、每项 `{name,type,size}`）
  - 落点：`LsTool`（不递归，上限 5000）；守护 `BuiltinSearchToolsTest`（ls 2 条）
- [x] **T5.4** `current_time`（无安全副作用）
  - 落点：`CurrentTimeTool`（时钟注入可测）；守护 `BuiltinSearchToolsTest.currentTimeUsesInjectedClock`
- [x] **T5.5** `grant_access`（加根走审批；**不自己判「能不能加」**；模型只有「申请」的能力）
  - 落点：`GrantAccessTool`（`AccessApprover` 宿主回调）；守护 `GrantAccessToolTest`（6 条：批准加根 / 拒绝不加根含「不许绕」/ 回调异常 fail-closed / 挂载名单段 / 目录缺失 / 批准不缓存）
- [x] **T5.6** `tool_search`（BM25 k1=1.2 b=0.75 + camelCase 分词 + 停用词 + 简单词干 + **CJK bigram 扩展**）
  - 落点：`searchtools/Bm25Index` + `ToolSearchTool`；守护 `DeferredToolsTest`（BM25 3 条）
- [x] **T5.7** Registry deferred 支持（`registerDeferred` / `deferredTools` / `activate`）+ 驱动「历史工具名并入」兑底
  - 关键：deferred 集非空才自动注册 `tool_search`；激活态**下一轮生效**
  - 落点：`ToolRegistry.registerDeferred` / `DeferredToolCatalog` / `ActivateTool` + `ReActDriver`（每轮决策前重组装清单 + `admitHistoricalTools`）+ `Agent.describe()` 声明；守护 `DeferredToolsTest`（5 条注册表/目录）+ `DeferredFlowTest`（2 条接线）
- [x] **T5.8** `web_search`（provider 接口 + 归一化词汇表 + 不可信内容标注 + 非 2xx 是结果不是错误）
  - 落点：`web/SearchProvider` / `WebSearchTool`（`UNTRUSTED_NOTE` 冻结文案）；守护 `WebToolsTest`（3 条）
- [x] **T5.9** `web_fetch`（SSRF 四条硬防线：公网地址校验 / **地址钉住**防 DNS rebinding / 只跟同源重定向 / 体积与超时上限 + 只解 UTF-8 + 非文本类型响亮失败）
  - 落点：`web/PublicAddresses` / `PinnedConnector` / `WebFetchTool`；守护 `WebToolsTest`（7 条：回环 / 内网字面量 / IPv4 映射伪装 / 链路本地 / 协议与主机 / 公网判定边界）
- [x] **T5.10** 工具规范（`Guideline` 接口 + 跨调用指引进系统提示词）
  - 关键：单次调用怎么传参归 Description，跨调用约定归 Guideline
  - 落点：`tool/Guideline` / `Guidelines`（聚合渲染逐字节稳定）；守护 `WebToolsTest.guidelinesRenderStably`

**G5 验收清单**：
- [x] 十个内置工具行为与 MoS 语义一致（按 Java 侧冻结口径自建；工具名 `grep/glob/ls/current_time/grant_access/tool_search/activate/web_search/web_fetch` + J3 的 4 个文件/命令工具；**2026-11-06 已逐字核对 Go 侧 builtin**：十个非文件工具名逐字一致，文件工具三名 + 四处参数形状不一致 → **已裁决并落地 B13-1/2/3**，详见 `INVARIANT-TESTS.md` §3）
  → `BuiltinSearchToolsTest`（17 条）+ `GrantAccessToolTest`（6 条）+ `DeferredToolsTest`（9 条）+ `DeferredFlowTest`（2 条）+ `WebToolsTest`（10 条）+ `ToolContractTest`（4 条：工具名与参数名清单守护）
- [x] grep 用 RE2J（**代码里不得出现 `java.util.regex`**）
  → `mos4j-tool/pom.xml` 直接依赖 `com.google.re2j`；全仓源码扫描 `import java.util.regex` = 无（CI C6 门）
- [x] SSRF 四条防线各有绕过测试
  → 防线一（公网校验）4 条绕过用例 + 边界 1 条（`WebToolsTest`）；防线三（同源重定向）判定在 `WebFetchTool` 循环内；防线二/四（钉住 / 体积超时）为传输层实现，真机 rebinding 对拍不可行——边界已登记（`L3-SCENARIOS.md` D4 说明 + `SELF-INVENTED.md`）

---

## 8. J6 三协议 + 硬化（2–3 周）

- [x] **T6.1** Anthropic 适配（请求/响应 + 流式 + **usage 分两处到达逐字段合并，delta 赢**）
  - 落地：`mos4j-llm/protocol/anthropic/{AnthropicConfig,AnthropicDtos,AnthropicStreamDtos,AnthropicChatModel}.java`；`L1AnthropicScenariosTest` 12 条全绿（A1–A10 语料 + 非流式 + 请求形状）
  - 估算：2.5 人日 ｜ 依赖：T1.5
- [x] **T6.2** Anthropic 流完整性（`message_stop` 结束标记；只发 stop 不发 delta → **判完整**）
  - 落地：A5（只有 message_stop → 完整、finishReason 空合法）/ A4/A6（无结束信号 → `StreamTruncatedException`）全绿
  - 估算：0.5 人日 ｜ 依赖：T6.1
- [x] **T6.3** Gemini 适配（`finishReason` 判定 + `require_finish` 开关）
  - 落地：`mos4j-llm/protocol/gemini/{GeminiConfig,GeminiDtos,GeminiChatModel}.java`；`L1GeminiScenariosTest` 6 条全绿（G1/G2/G5a/G5b + 非流式 + 请求形状）；Gemini 无终止哨兵，读到 EOF 为止，usageMetadata 在 finishReason 之后到达不丢
  - 估算：1.5 人日 ｜ 依赖：T1.5
- [x] **T6.4** 取消/超时贯通（`CancellationToken` 接入全部可阻塞点 + **可阻塞点清单**逐项核对 + 审批阻塞用虚拟线程 + 取消不留空洞）
  - 落地：①HTTP（`HttpTransport` 已有真取消，future.cancel 中止交换）；②ExecPath 新增 `executeBatch(...,token)` 重载：取消后未开始的调用回填「已取消，未执行」，回填序与配对保持（无空洞），null 令牌零开销；③重试退避等待分片检查取消（T6.5 协同）。可阻塞点清单与测试见 `J6-STATUS.md`。审批阻塞迁虚拟线程未单独立项（审批在 preflight 串行段，宿主回调线程属宿主域）——诚实边界记录于 `J6-STATUS.md`
  - 估算：2 人日 ｜ 依赖：T1.16, T2.6
- [x] **T6.5** 重试（默认不装配就没有重试；只重试瞬时错误；**取消是终止性的**；退避有界指数 + 抖动；流式只在未吐出内容时重试）
  - 落地：`llm/{RetryPolicy,RetryPolicies,RetryingChatModel,RetryExecutors}.java`；`RetryingChatModelTest` 9 条；装饰器模式，默认不装配即透传
  - 估算：1.5 人日 ｜ 依赖：T1.7
- [x] **T6.6** 并发硬化（虚拟线程 + `Semaphore` + 屏障分组 + 独占调用自成屏障 + 并发判定 fail-closed 含 panic 归独占）
  - 落地：结构 J1/J2 已成立；补硬化测试 5 条（Semaphore 上限、屏障隔离、panic 不炸整批、虚拟线程验证、分类器异常归独占）
  - 估算：1.5 人日 ｜ 依赖：T1.16
- [x] **T6.7** 硬化 fuzz（参数容错解析 / SSE 解析 / JSONL 尾行 / 投影自愈）
  - 落地：`ToolArgsFuzzTest`（500 轮随机输入）/ `SseParserFuzzTest`（400 轮）/ `JsonlTruncationFuzzTest`（200 轮字节级截断）。**抓到并修复 3 个真实缺陷**：①`JsonlPersistence.load` 严格 UTF-8 解码对多字节字符中间截断抛异常（违反「尾部半行可丢」）；②`ToolArgs.normalize` 只接 IOException，Jackson 包装的 RuntimeException 泄漏；③OpenAI 响应/流式 DTO 不忽略未知字段（服务商加字段即炸，G6 之外的真实健壮性缺陷）
  - 估算：1 人日 ｜ 依赖：无
- [x] **T6.8** 性能（注册表循环内零反射 + 工具清单顺序稳定 + 基准）
  - 落地：注册期缓存 schema 已成立（`ToolRegistry` 注册即 `resolveSchema` 缓存）；`registryListIsZeroReflectionAndStableOrder` 验证清单顺序 = 注册顺序、跨调用稳定、schema 来自缓存
  - 估算：0.5 人日 ｜ 依赖：T1.8
- [x] **T6.9** CLI `mos4j` 最小形态（装配 + 跑一轮）
  - 落地：`mos4j-sdk/net/hegq/mos4j/cli/Main.java`（`--protocol/--api-key/--base-url/--model/--workspace/--log-dir/--session/--no-file-tools/--help`；单轮执行，诚实边界：多轮 REPL/审批交互未做）；`MainTest` 5 条（真实 HTTP 跑一轮 + 落盘校验 + 用法/退出码）
  - 估算：0.5 人日 ｜ 依赖：T1.17
- [x] **T6.10** `mos4j-sdk` 门面：宿主装配 DSL（Builder）+ 默认装配（2026-11-05 补登记：J1 只建了空模块骨架，模块骨架来自骨架阶段）
  - 落地：`net.hegq.mos4j.sdk.Mos4j.builder()...build()`；协议缺省 OpenAI（环境变量 `OPENAI_API_KEY` 等，缺密钥 fail-fast 报变量名）；缺省装配文件四件套（jail 锚工作目录）；`session(id)` 打开/恢复；`Mos4jTest` 5 条；门面零新依赖（依赖审计 sdk 模块亦通过）
  - 验收：一次依赖即可拿到可跑的 Agent；门面不引入任何 SDK 之外的依赖
  - 估算：1 人日 ｜ 依赖：T1.17

**G6 验收清单**：
- [x] L1 全场景通过（三协议）——Anthropic 12 + OpenAI 5 + Gemini 6 全绿，`@Disabled` 归零（原 14 条跳过点亮）
- [x] 取消/超时无泄漏（线程、进程、文件句柄）——HTTP 真取消（future.cancel）、ExecPath 取消不留空洞、重试退避中取消立即终止；fuzz/取消测试全绿（证据见 `J6-STATUS.md`）
- [x] 依赖审计仍只有 3 个运行时依赖——11 模块 + sdk 全部 `No dependency problems found`；`import java.util.regex` = 0、Spring = 0

---

## 9. 任务汇总与估算口径

| 里程碑 | 任务数 | 净开发人日 | 折算日历（1 人，×1.0） |
|---|---|---|---|
| J0 | 10 | 8.0 | 1.6 周 |
| J1 | 18 | 18.0 | 3.6 周 |
| J2 | 11 | 12.0 | 2.4 周 |
| J3 | 11 | 14.0 | 2.8 周 |
| J4 | 9 | 11.0 | 2.2 周 |
| J5 | 10 | 11.0 | 2.2 周 |
| J6 | 10 | 12.5 | 2.5 周 |
| **合计** | **79** | **86.5** | **~17 周（1 人）** |

> 数字由任务条目的「估算」字段机械累加得出（可对 `docs/mos4j-tasks.md` 的估算行直接求和核对）。

**与上一版（对拍口径）的差异**：定位从「逐字节对拍的移植」放宽为「参考实现」（整体方案 §0.0），卸掉了 Go 导出器与跨语言对拍，J0 从 10.5 人日降到 8.0 人日；starter 移出 J6 进按需清单（T6.9 保留为 CLI）。**2026-11-05 补登记 T6.10（SDK 门面）**，合计 **86.5 人日 ≈ 17 周（1 人）**，仍高于原方案的 14–16 周——差额来自原方案未计入的工程基建与对齐细节（见下）。

| 差额项 | 人日 | 能否裁 |
|---|---|---|
| 工程基建（CI / 边界检查 / PR 模板 / 贡献指南 / Javadoc 门） | ~3 | **不可裁**——裁掉就是防线没了 |
| JSON `omitempty` 对齐 + 手写 Serializer（Go 侧是自定义 MarshalJSON） | ~2 | 不可裁（JSONL 日志形态正确的前提） |
| J5 联网工具（web_search/web_fetch SSRF） | ~3.5 | **可裁**（推迟，工具面先不含联网） |
| J6 Gemini 适配 | ~1.5 | 可裁（先两协议） |
| J6 CLI + fuzz + 性能 | ~2 | 可裁（推迟） |

**裁剪后的最短路径**：86.5 − 3.5（联网）− 1.5（Gemini）− 2（CLI/fuzz/性能）− 1（SDK 门面）= **约 78.5 人日 ≈ 15.7 周（1 人）**。

**并行**（若有 2 人）：约 10–12 周。铁律：J1 之前不能有第二个人同时改协议层；`mos4j-execpath` 与 `mos4j-session` 的事件契约任何时候只能一人改。

---

## 10. 按需清单（不在本次承诺内）

| 项 | 触发条件 | 估算 | 状态 |
|---|---|---|---|
| ~~**Spring Boot starter**（自动装配：`ChatModel` / `Registry` / `Persistence` / 审批通道；配置属性化；条件装配；无自动装配时**不静默降级**；starter 能自动装配跑通一个 turn）~~ | 首个真实 Spring 宿主需求出现 | ~1.5 人日 | ✅ **已交付（2026-11-06）**——见下方 T-SB.1…T-SB.6 |
| 与 Go 版的跨语言对拍（导出器 + 三层 golden） | 需要与 Go 版互操作（消费 Go 版会话日志 / 跨语言验收） | ~4 人日 + 长期维护 | 未做 |
| M3+ 功能（多智能体 / MCP / Skills / 插件化） | 单独立项 | — | 未做 |

### 10.1 Spring Boot starter 任务分解（全部已交付）

- [x] **T-SB.1 模块骨架**：新增 `mos4j-spring-boot-starter`，**不进默认 reactor**（父 POM 的 `starter` profile，`./mvnw -Pstarter verify` 才构建）；父 POM 集中 `spring-boot.version`（3.5.16）+ 导入 Spring Boot BOM。
- [x] **T-SB.2 属性化装配**：`Mos4jProperties`（前缀 `mos4j`，20 个属性，配置元数据由 `spring-boot-configuration-processor` 生成）；`Mos4jAutoConfiguration` 产出 `Mos4j` / `Agent` / `JsonlPersistence` / `Mos4jRunner` 四个 Bean，均带 `@ConditionalOnMissingBean`（宿主可整体覆盖）。
- [x] **T-SB.3 条件装配 + 不静默降级**：`mos4j.enabled` 缺省 true；启用但缺密钥 → **启动失败**并指名环境变量；`mos4j.approvals.required=true` 但无 `ApprovalChannel` Bean → **启动失败**；显式 `enabled=false` → 一个 Bean 都不给（状态可见）。
- [x] **T-SB.4 宿主能力接线**：容器里的 `ChatModel` / `ToolRegistry` / `ApprovalChannel` / `GuardBus` / `HookBus` / `PostHook` 直接接进装配；输出限流缺省装、压缩按 `context-window` 装（未配置不装）。
- [x] **T-SB.5 端到端验收**：`Mos4jStarterTurnTest` 真跑一轮（真实 HTTP 回放 OpenAI 响应 → 拿到答案 → 会话落盘 + Header + assistant 事件）；`Mos4jAutoConfigurationTest` 13 条覆盖上述口径（缺省装配、显式关闭、缺密钥 fail-fast、环境变量兜底、宿主自带模型不要密钥、审批必装/接上、限流缺省装、压缩按窗口装/不装、落盘目录在 jail 内可用 / 越界失败 / 无可读工具失败）。
- [x] **T-SB.6 依赖纪律**：Spring 只出现在 starter（SDK 12 模块运行时依赖仍只有 Jackson / RE2J / JDK）；父 POM 的 enforcer 门在 starter 里**改窄**（只放行 Spring，Guava / ORM / Lombok / SLF4J / Logback 照旧禁；已做负向验证——临时加 `org.slf4j:slf4j-api` 即构建变红，已回滚）；CI 的 C6 扫描从 `mos4j-*/src/main` 通配改为**显式 12 模块列表**（通配会把 starter 合法的 Spring import 判成违规——已实测：旧口径命中 8 处、新口径 0 处）；CI 新增独立 `starter` job（`-Pstarter verify`）。
- [x] **顺带修的门面缺陷**：`Mos4j` 门面此前装配了文件工具却**没把 jail 边界写进 `Config`**，导致 `Agent.describe()` 谎报「未装配文件 jail」（工具明明被 jail 着）——已修（`Mos4jTest.describeReportsTheJailThatIsActuallyInstalled`）；门面另新增可选能力装配（`guards` / `hooks` / `approvals` / `postHook` / `outputLimiter` / `compaction` / `tools` / 边界快照）与公开的 `Mos4j.defaultModel(...)`（starter 与门面共用一份「协议 → 环境变量 → 默认地址」真相，不写第二遍）。


---

## 11. 追溯矩阵（不变式 → 守护任务 → 场景测试）

| 不变式 / 机制 | 守护任务 | 场景测试 |
|---|---|---|
| fail-closed | T1.16, T2.6, T3.8, T5.5 | L3-未知工具 / L3-参数非法 / L3-无审批通道 |
| 日志即事实 | T1.11, T1.12 | L2-孤儿 turn |
| 审计不变式 | T4.9 | （白名单单测） |
| 上下文是稀缺资源 | T4.3–T4.6 | L2-含压缩事件 / L2-迭代压缩 |
| Hook/Guard 双轨制 | T2.1–T2.3, T2.11 | L3-载荷冻结违约 |
| 零开销降级 | T2.10 | （等价性单测） |
| 依赖纪律 | T-INF.2, T-INF.3 | — |
| 载荷冻结 | T2.3 | L3-载荷冻结违约 |
| 回填序 = 调用序 | T1.16 | L3-并发组反序完成 |
| 错误只可加重 | T2.9 | L3-错误只可加重 |
| 投影自愈 | T1.13 | L2-孤儿 tool/call |
| 流完整性按结束标记 | T1.5, T6.1, T6.2, T6.3 | L1-只发 message_stop / L1-真截断 / L1-只发 [DONE] |
| 用量口径统一 | T1.6 | L1-usage 分项和=总计 / L1-delta 赢过 start |
| 写前观察 | T3.10 | （三类场景单测） |
| 路径双校验 | T3.1–T3.3 | （三形态单测） |
| 非零退出码不是错误 | T3.6, T5.10 | （shell 单测） |
| 拒绝文案带「不许绕」 | T2.8 | L3-审批人工拒绝 |

---

## 12. J7 技能机制（Go `agent/skill` 的 Java 语义重写）

> 已获用户批准（设计 / 契约变更 1–5 / Starter 第 6 项 / 审计落点方案 A）。
> **不新增模块**：能力落在既有 `mos4j-tool` / `mos4j-core` / `mos4j-session` / `mos4j-driver` / `mos4j-sdk`，保持 12 模块边界。

- [x] **T7.1 B16：Jackson 3 升级批次**（J7 的前置）
  - 坐标：`tools.jackson.core:jackson-databind/core:3.2.4` + `tools.jackson.dataformat:jackson-dataformat-yaml:3.2.4`；注解仍是 `com.fasterxml.jackson.core:jackson-annotations:2.22`（注解不随 databind 大版本走）
  - API 迁移：`ObjectMapper.configure` → `JsonMapper.builder()`；`JsonSerializer` → `ValueSerializer`；`SerializerProvider` → `SerializationContext`；`JsonProcessingException` → `JacksonException`（**继承 RuntimeException**，不再受检）；`fields()/fieldNames()` → `properties()/propertyNames()`；`writeXxxField` → `writeXxxProperty`
  - **行为差异 1 处（已修）**：Jackson 3 默认打开 `FAIL_ON_TRAILING_TOKENS` → 参数 JSON 后面还有内容现在**报错**（Jackson 2 会只读第一个值、把尾巴静默丢掉）。这与 Go 的 `json.Unmarshal` 一致，也与机制 M9「不是合法 JSON 就显式报错」一致 → 修正 `FileToolsTest.editReplaceAllIsNotOffered`（原本靠畸形 JSON 夹带额外字段），并新增 `BuiltinSearchToolsTest.trailingContentAfterTheObjectIsRejected` 钉住新口径
  - I7 条文同步修订为「Jackson 家族 / RE2J / SnakeYAML 系 / JDK」（**机械门不动**：Enforcer 的 `bannedDependencies` 列表不变）
  - 验证：默认 reactor 13 项 SUCCESS（450 通过 / 1 跳过）；`-Pstarter` 14 项 SUCCESS（464 通过 / 1 跳过）；12 模块 + starter `dependency:analyze` 全 `No dependency problems found`；C6 源码扫描 12 模块 Spring=0 / `java.util.regex`=0
- [x] **T7.2 技能目录发现 + frontmatter 解析**（`mos4j-tool` 的 `net.hegq.mos4j.tool.skill`）
  - 关键：`SKILL.md` + YAML frontmatter（`name` / `description` 必需）；`name` kebab-case ≤ 64；`description` ≤ 1024；**坏技能跳过并诊断，不静默截断**；同名技能先发现先得并产生诊断
  - YAML 用成熟方案（Jackson 的 `jackson-dataformat-yaml` → `snakeyaml-engine`），不手写 YAML 子集；裸 `~` 要对齐 Go `yaml.v3`（Go 是 null，Jackson 3 是字符串）
  - **落地**：`SkillMetadata` / `Skill` / `SkillReader` / `OsSkillReader` / `SkillFrontmatter` / `SkillCatalog`（`SkillCatalogTest` 33 条：发现序、裸 `.md` 不算、同名先到先得、三类坏技能各一条诊断、目录不存在只诊断、`name` 缺失回退目录名、BOM、重复键、裸 `~`、`disable-model-invocation`、`allowed-tools` 不生效诊断）
- [x] **T7.3 清单渲染 + 字节预算降级**（`<available_skills>`）
  - 关键：完整清单 → 仅名称 → 部分条目并说明省略数 → 最小提示 → 不注入；默认预算 8 KiB
  - **落地**：`SkillCatalog.render()` 五级（全量带描述 → 只列名 → 逐条塞到位 → 最小形态 → 空串）；渲染纯函数逐字节稳定（两次一致有测试）
- [x] **T7.4 `skill` 工具 + 审计**（模型可见工具面 13 → 14）
  - 关键：参数只有 `name`；**正文调用时现读**（不驻留快照）；仅用户可调用的技能（`disable-model-invocation: true`）模型不得加载；**激活审计先于状态变更**（审计写失败 → 本次调用失败、正文不外泄）
  - 审计落点 = 方案 A：工具侧 `AtomicReference<SkillAudit>` 槽，driver 每轮装入当前会话审计器；**不改 `ExecPath.Security`**
  - **落地**：`SkillTool` + `SkillState` + `SkillAudit`（core）+ `SessionSkillAudit`（session）；`SkillToolTest` 15 条（正文与目录、未知技能提示目录可能已更新、仅用户可调用 fail-closed、审计失败不返回正文、mismatch 只诊断、mismatch 写失败不拦加载、无审计器也能用、每轮换状态、重复激活幂等、正文损坏报错、工具元数据、返回键序）
- [x] **T7.5 技能目录重扫**（`Agent.reloadSkills()`）
  - 关键：显式、幂等、**全量**重扫；新快照**原子整体替换**（不出现半个目录的新旧混合）；digest 只含元数据不含正文；差异明细 `Added/Removed/Updated/Notice`
  - **落地**：`SkillCatalog.reload()` + `SkillReloadResult`；`SkillCatalogTest` 与 `SkillFlowTest.reloadPicksUpNewSkills`（新增技能进清单、静态提示词不重渲染）
- [x] **T7.6 用户显式调用**（`/name`）
  - 关键：只有首个 token 形如 `/name` 且命中已知技能才展开；尾部参数追加在正文后
  - **落地**：`SkillCatalog.expandInvocation()` + `Agent.expandSkill()`（句中出现 `/deploy` 不展开、`null` 入参不炸）
- [x] **T7.7 事件与字段名清单**
  - 事件新增 `skill/activate`（载荷 `skill{name,description,dir}`）与 `skill/allowed-tools-mismatch`；**skill 事件不进消息投影**（与 `approval/*` 同口径）
  - `mos4j-core` 新增一个公开接口 `SkillAudit`（`activate(...)` + `allowedToolsMismatch(...)`）
  - 字段冻结清单升为 **80 条 / 71 名 / SHA-256 `3d35911d810641c25b8ac0da032ac6f8c1d80dd90c240a4aafe5e95d4714912c`**（只并入 `skill`/`name`/`description`/`dir`/`missingAllowed` 五个必要差异）
  - **落地**：`EventKind` +2 / `SkillEvent` / `Event.skill` / `EventJson.writeSkill` / `SessionSkillAudit`；`SessionSkillAuditTest` 5 条 + `EventJsonTest` 2 条（`missingAllowed` 空省略、`name` 无条件）
- [x] **T7.8 提示词接线**：`PromptSections.CANONICAL_ORDER` 插入 `skills`（`base/environment/context/skills/instructions/tools`）；技能清单位置 = 项目上下文之后、工具指引之前
  - **落地**：另加 `PromptSections.insert(rendered, name, content)`（宿主把 prompt 当整串交过来时不经过 `Builder`，位置仍必须落在中心顺序位置，否则 prompt cache 失效）；`PromptSectionsTest` 现 16 条（含新增的位置/纯函数/空内容/空段名用例）
- [x] **T7.9 SDK / Starter 接线**：`Mos4j.Builder.skills(dirs)`；Starter 配置元数据 20 → 23
  - **落地**：`Mos4j.Builder.skills(Path...)` + `skillLimits(int,int)`；Starter `mos4j.skills.dirs/max-body-bytes/max-catalog-bytes`（相对路径按 `mos4j.workspace` 解析，不以进程 CWD 为锚）；元数据实测 23 条；`Mos4jTest` +3 条、`Mos4jAutoConfigurationTest` +3 条
- [x] **T7.10 `allowed-tools` 只诊断不生效**：装配期提示「当前不生效」；激活时与可见工具集求交并留痕 `missingAllowed`；**不改变执行工具面**（不造第二套安全真相）
  - **落地**：`SkillFrontmatter` 解析 + 装配期 warning；`SkillTool` 激活时求交（`ToolRegistry.visibleNames()` 探针，每轮现查）→ `allowedToolsMismatch`（吞异常）
- [x] **T7.11 测试**：目录发现 / 同名先到先得 / 坏技能降级 / 正文调用时现读 / 清单多级降级 / 重扫幂等与差异明细 / 原子替换 / `/name` 展开 / 仅用户可调用 / 审计失败即失败 / mismatch 只诊断不拦加载 / 条件注册 / 提示词位置 / YAML 标量口径 / E2E
  - **落地**：2026-11-07 全量验证 —— 默认 reactor **525 通过 / 1 跳过**（13 项 SUCCESS）；`-Pstarter` **542 通过 / 1 跳过**（14 项 SUCCESS）；12 模块 + starter 依赖审计全 `No dependency problems found`；C6 扫描 `import org.springframework`=0、`import java.util.regex`=0（含 `src/test`）、裸 NUL 字节 0（日志 `.j7log2/`）

**明确不做**：技能级工具收窄（`allowed-tools` 不生效）；技能正文进上下文摘要；运行期组件/插件热加载（技能目录是**数据面刷新**，不是组件替换）。

---

## 13. J8 三平台路径强制（Go `sandbox/` 的 Linux/Windows 后端对齐）

- 口径：用户裁决 **「与 MoS」** —— 缺省装配 + fail-closed（不是宿主 opt-in）
- [x] **T8.1 Seatbelt 对齐**：profile 补 `(literal "/dev/null")`；写根 `toRealPath` 规范化（软链根不再拦自己的写）；SBPL 转义；`sandbox-exec` 缺失在 `wrap` 阶段就报错
- [x] **T8.2 `PathScopes` 语义修正**：`supported()` = 「后端可用」而非「是不是 macOS」；新增 `derive(workDir)`（对齐 Go `deriveScope`）；无后端平台抛 `UnsupportedOperationException` 并点明缺什么
- [x] **T8.3 Linux Landlock 后端**：`LandlockScope`（JDK 25 FFM 直调 4 支 syscall/libc，ABI V1/V2/V3 分档，`PR_SET_NO_NEW_PRIVS` 前置）+ `LandlockLauncher`（重新执行自己的 java 启动器：先套约束再起命令，失败 `exit 125`）
- [x] **T8.4 fail-closed 管道**：`LocalRunner` 把 `wrap` 抛错转成 `RunResult.notStarted("路径强制不可用: …")`——命令一个字节都不跑
- [x] **T8.5 SDK 缺省装配**：`Mos4j.fileTools()` 在 `PathScopes.supported()` 为真时 `pathScope(PathScopes.derive(anchor))`（写根 = 工作区）
- [x] **T8.6 测试**：`SandboxBoundaryTest` 14 → 21（`/dev/null`、软链规范化、wrap 失败即 fail-closed、`derive` 写根口径、**Landlock wrap 形状与写根往返（不碰 native，任何平台可验）**、`/` 逃生门、收窄不能放开）；新增 `LandlockScopeTest` 5 条（`@EnabledOnOs(OS.LINUX)`，非 Linux 上**跳过而非通过**）
- [ ] **T8.7 Windows 后端**：未开工（机制不同：低完整性 token + Low 标签/写 ACE + Job Object；且 `LocalRunner.SHELL="/bin/sh"`、`ResourceLimits.shellSnippet()` 的 `ulimit` 前缀都是 Unix 假设 → Windows 上 shell 工具当前根本跑不起来）。**需要先有 Windows 验证环境**，8–12 人日量级
- [ ] **T8.8 真机验收**：macOS 强制效果（需允许 `sandbox-exec` 的主机）、Linux 强制效果（需 Linux ≥ 5.13；建议加 `runner.os == 'Linux'` 的 CI job）
- **落地**：2026-11-07 全量验证 —— 默认 reactor **537 通过 / 0 失败 / 6 跳过**（12 个测模块）；`-Pstarter` **554 / 0 / 6**；12 模块依赖审计 `BUILD SUCCESS`（FFM 是 JDK 自带，**零新增依赖**）；C6 扫描均 0；真实行为探针见 `docs/contract/J8-STATUS.md` §5。**测试自身抓到两个真 bug**：packed 结构体用 `withByteAlignment(1)` 会在类初始化时抛异常；libc 绑定写在类静态初始化里会在 macOS 上直接炸（`prctl` 不存在）→ 改为嵌套懒加载 holder

**明确不做**（本轮）：Windows 后端；沙箱内的读限制（只收窄写）；`grant_access` 与路径强制的联动（与 Go 一致，两者各管一摊）。

