# J2 推进状态（安全纵深）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 更新：2026-11-05。J2 = 载荷冻结 + Hook/Guard 双轨制 + 人工审批 + 错误只可加重 + 名字/参数级策略（任务列表 §4）。
> 配套：[任务列表](../mos4j-tasks.md)、[推进方案](../mos4j-execution.md)、[契约目录](.)、[J1 状态](J1-STATUS.md)。

## 一句话状态

**T2.1–T2.10 已完成并有测试；T2.11 已于 2026-11-06 补齐**（Java 侧逐条无空项；Go 侧原测试名已逐条抄录，共 137 个，机器复核全部存在）。**G2 门尚未人工评审。**

## 产出文件

| 模块 | 文件 | 说明 |
|---|---|---|
| mos4j-core | `core/Texts.java` | 拒绝文案的**唯一出处**（含「不许绕」半句） |
| mos4j-core | `core/{ApprovalRequest,ApprovalResult,ApprovalOutcome,ApprovalAudit}.java` | 审批共享词汇表（hook 与 session 都不依赖对方） |
| mos4j-hook | `hook/{GuardEvent,GuardDecision,Guard,GuardBus}.java` | 守卫轨：串行、单调、首个拒绝短路 |
| mos4j-hook | `hook/{HookEvent,Hook,HookBus,HookRejectedException}.java` | 钩子轨：waterfall、短路、替换、null 显式报错 |
| mos4j-hook | `hook/{ApprovalChannel,Answerer,ApprovalService}.java` | 四档审批；通道异常归 `unavailable` |
| mos4j-hook | `hook/PostHook.java` | 执行后钩子：**接口层面只有加重能力** |
| mos4j-policy | `policy/{ToolNamePolicy,ParamPolicy}.java` | 名字级 / 参数级策略（RE2J） |
| mos4j-session | `session/SessionApprovalAudit.java` | 审批事件落日志（只进日志，不进投影） |
| mos4j-execpath | `execpath/ExecPath.java`（`Security` 内部类） | 安全编排：守卫 → 钩子 → 冻结 → 审批 → 执行 → 回填 → post |
| mos4j-driver | `agent/Config.java`、`driver/ReActDriver.java` | 装配入口（guards / hooks / approvals / postHook）+ 每轮审计出口 |

## 任务完成情况

| 任务 | 状态 | 证据（真实测试） |
|---|---|---|
| T2.1 HookBus | ✅ | `HookBusTest`（8 条：顺序 / 短路 / 替换 / null 显式报错 / 拒绝传播 / 坏插件转拒绝 / 快照独立） |
| T2.2 GuardBus | ✅ | `GuardBusTest`（7 条）+ `DualTrackStructureTest`（3 条） |
| T2.3 载荷冻结 | ✅ | `ExecPathSecurityTest.l3_3_*`（4 条） |
| T2.4 名字级策略 | ✅ | `ToolNamePolicyTest`（8 条） |
| T2.5 参数级策略 | ✅ | `ParamPolicyTest`（10 条） |
| T2.6 人工审批（四档） | ✅ | `ApprovalServiceTest`（5 条）+ `ExecPathSecurityTest`（通道异常 / 每次都问） |
| T2.7 审批审计事件 | ✅ | `SessionApprovalAuditTest`（5 条）+ `ExecPathSecurityTest.auditWriteFailureAbortsTheCall` |
| T2.8 拒绝文案固定 | ✅ | `ExecPathSecurityTest.l3_5_*` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence` |
| T2.9 错误只可加重 | ✅ | `ExecPathSecurityTest.l3_8_*`（2 条） |
| T2.10 零开销降级 | ✅ | `ExecPathSecurityTest.nullBusesBehaveLikeNotInstalled` / `emptyBusesBehaveLikeNotInstalled` / `noSecurityMeansSameBehaviorAsBefore` |
| T2.11 Go 不变式逐条译 | ⚠️ 部分 | `docs/contract/INVARIANT-TESTS.md`：Java 侧逐条无空项；**Go 侧原测试名待授权后抄录** |

## 场景覆盖（真实计数）

| 层 | 绿 | 未做 | 说明 |
|---|---|---|---|
| L3 D1（J2 范围） | **10/10**（L3-1 … L3-10） | — | L3-7 的回填序由 J1 的 `ExecPathTest.resultsFollowCallOrderNotCompletionOrder` 承接 |
| L3 D2（文件与沙箱） | 0/9 | 全部 | 属 J3（`mos4j-fs` / `mos4j-sandbox`） |
| L3 D3（Java 差异降级声明） | 0/2 | 全部 | 属 J3（进程组击杀 / 版本令牌） |
| L3-19 截断工具调用拒执行 | ✅ | — | J1 已实现（`ReActDriverTest`），文案出自 `Texts.truncatedToolCall` |

## 验证记录（真实输出）

```
$ mvn -Dmaven.repo.local=.m2repo clean verify
mos4j-core     Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
mos4j-llm      Tests run: 35, Failures: 0, Errors: 0, Skipped: 14
mos4j-tool     Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
mos4j-session  Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
mos4j-hook     Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
mos4j-execpath Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
mos4j-driver   Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
mos4j-policy   Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

合计 **172 个用例通过 / 14 个跳过**（共 186 个跑过；跳过的 14 条是 Anthropic + Gemini 的 L1 协议场景，适配器在 J6）。

同一套用例用 Maven Wrapper 重跑了一遍（wrapper 固定 Maven 3.9.9）：

```
$ MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify
BUILD SUCCESS（计数与上面完全一致）
```

> 沙箱备注：wrapper 的 `mktemp -d` 在本沙箱不可写（系统临时目录被禁，与 JUnit `@TempDir` 同一个限制），
> 因此先手动把 Maven 3.9.9 发行包放进 wrapper 缓存目录（`.m2repo/wrapper/dists/apache-maven-3.9.9/3477a4f1/`）再跑。
> CI 上是干净的下载路径（`distributionUrl` 指向 Central）。

依赖门：

```
$ mvn -Dmaven.repo.local=.m2repo dependency:tree -Dscope=runtime
外部依赖只有 jackson-databind / jackson-annotations / jackson-core / re2j —— I7 成立

$ mvn -Dmaven.repo.local=.m2repo -am -pl <8 个非空模块> dependency:analyze -Dmdep.analyze.failOnWarning=true
BUILD SUCCESS（零告警：声明与使用一致，不靠传递依赖）
```

> `dependency:analyze` 必须用 **3.9.0**：3.7.0 / 3.8.1 自带的 ASM 不认 class file 69（JDK 25），会直接报 `Unsupported class file major version 69`。

## J2 期间改掉的「会静默出事」的问题

| 问题 | 后果 | 修复位置 |
|---|---|---|
| 编排顺序把钩子排在守卫前面 | 已被守卫拒掉的调用，扩展代码仍然会跑（钩子有副作用时 = 绕过守卫的可乘之机） | `ExecPath.preflight` 改为 **守卫 → 钩子 → 冻结 → 审批**，与 `INVARIANTS.md` I5 的落点一致；`ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks` |
| 审批放在冻结检查之前 | 会被载荷冻结拒掉的调用也会去打扰人 | 审批移到冻结检查之后 |
| 审批通道抛异常直接冒泡 | 整批执行被异常打断，且日志里留下「问了但没有结论」的洞 | `ExecPath.approve` 归一为 `unavailable` 并照样落 `approval/decided`；`ExecPathSecurityTest.throwingApprovalChannelBecomesUnavailableAndStillWritesTheDecision` |
| `SessionApprovalAudit.decided` 重复携带 `reason` | 违反 `API.md`「reason 只在 asked 出现」 | `SessionApprovalAudit.append` 按事件种类决定是否带 reason；`SessionApprovalAuditTest.reasonOnlyAppearsOnTheAskedEvent` |
| `dependency:analyze` 在 JDK 25 上直接报错 | 依赖纪律的机械门形同虚设 | 父 POM 加 `maven.dependency.plugin.version=3.9.0`（第一个能读 class file 69 的版本），CI 加 C2b 硬门 |
| 模块靠传递依赖用 Jackson / 内部模块 | 换上游版本就断；依赖树不可审计 | 按各模块**实际 import** 重写 POM 依赖（core 不再依赖 Jackson；tool / session / policy 显式声明 Jackson） |

## 未完成 / 待决策

| 项 | 说明 | 影响 |
|---|---|---|
| G2 人工评审 | 机械项全绿；**语义正确性**（顺序、文案、策略严格度）需维护者确认 | G2 门未过 |
| Go 侧原测试名逐条抄录 | ✅ 已完成（2026-11-06，授权已获）：137 个真实 Go 测试名，机器复核全部存在 | T2.11 已完成；见 `INVARIANT-TESTS.md` §1 |
| `mos4j-fs` / `mos4j-sandbox` | 空骨架 | L3 D2 / D3、M7 / M9 仍无 Java 守护测试（J3）—— **已在 J3 完成，见 `J3-STATUS.md`** |
| `mos4j-context` | 当时为空骨架 | M8 与 L2-9/11/12 仍无守护测试（J4）—— **已在 J4 完成，见 `J4-STATUS.md`** |
| `CancellationToken` 未贯穿 driver → planner | 取消目前只在模型 / HTTP 层生效 | 协作式取消的边界仍写在 `Describe()` 里 |
| 模块边界断言（T-INF.3） | 共享值类型类身份、模块边界检查未写 | CI 的 C3 门仍缺位 |
| `mos4j-sdk` 门面 | 仍是空模块（T6.10） | 宿主只能直接用 `Agent.of(Config.builder()...)` |
| 压缩器 | 预算触发 / 切割点 / previous-summary | **J4 已补齐**（见 `J4-STATUS.md`）：L2 16/16 全绿 |
