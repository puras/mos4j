# MoS4J

参考 [MoS](https://github.com/puras/mos)（Go）的语义重写——用 Java 实现的智能体基础框架。**SDK 是本体**，Spring Boot Starter 只是上层可选薄封装（按需模块，见 [`docs/spring-boot-starter.md`](docs/spring-boot-starter.md)）。

> 当前状态：**J1–J8 全部落地 + 加固批次 + Go 对拍批次（B13）+ Jackson 3 升级（B16）收口**——J1（OpenAI 协议 + 工具 + 会话 + ReAct 驱动）、J2（安全纵深：双轨制/载荷冻结/审批/审计）、J3（文件 jail + 写前观察门 + 命令沙箱 + Describe 边界快照）、J4（输出限流 + 截断落盘 + 上下文压缩 + 提示词分节装配）、J5（grep/glob/ls/current_time/grant_access + tool_search 延后加载 + web_search/web_fetch SSRF 四防线 + Guideline）、J6（**Anthropic + Gemini 协议适配、取消贯通、重试装饰器、并发硬化、fuzz、SDK 门面 + CLI**）；加固批次补齐**取消贯通 driver→planner→agent**、**模块边界门（C3）**、**依赖审计负向验证**；B13 批次把工具面与 Go 参考版对齐（文件工具名 `read`/`write`/`edit`、`edit` 改 `edits[]`、`grep`/`glob`/`ls`/`shell` 补参数、`tool_search` 参数名改 `limit`），并用 `ToolContractTest` **机器守护工具名与参数名清单**；**J7 批次**实现 Go 的**技能（skill）机制**（`SKILL.md` 目录发现 + 渐进式披露 + 条件注册的 `skill` 工具 + `skill/activate`·`skill/allowed-tools-mismatch` 审计事件 + 原子重扫 + `/name` 手势），前置的 **B16 Jackson 3 升级**也已落地。三协议 L1 场景全绿（Anthropic 12 + OpenAI 5 + Gemini 6，`@Disabled` 归零）、L2 16/16、L3 D1–D4 全绿，默认 reactor 基线 **537 通过 / 6 跳过**（含按需 starter 则 **554/6**）。**J8 批次**把**路径强制**按平台补齐：macOS Seatbelt 与 Go 对齐（`/dev/null` 放行、写根软链规范化、`sandbox-exec` 缺失即 fail-closed）、Linux 新增 **Landlock LSM 后端**（JDK 25 FFM 直调，零依赖零 JNI）、SDK **缺省装配**（写根 = 工作区，对齐 Go `deriveScope`）；Windows 未实现且如实 fail-closed。**G1–G6 门待人工评审**；macOS 的 Seatbelt 内核强制在本机仍被拒（`sandbox_apply: Operation not permitted`，退出码 71）、Linux 后端本机跑不了（`LandlockScopeTest` 5 条在非 Linux 上跳过），用例如实标为跳过。进度见 [`docs/contract/J1-STATUS.md`](docs/contract/J1-STATUS.md)、[`J2-STATUS.md`](docs/contract/J2-STATUS.md)、[`J3-STATUS.md`](docs/contract/J3-STATUS.md)、[`J4-STATUS.md`](docs/contract/J4-STATUS.md)、[`J5-STATUS.md`](docs/contract/J5-STATUS.md)、[`J6-STATUS.md`](docs/contract/J6-STATUS.md)（含 §7 加固批次、§8 Go 对拍批次）、[`J7-STATUS.md`](docs/contract/J7-STATUS.md)（技能机制 + Jackson 3 升级）与 [`J8-STATUS.md`](docs/contract/J8-STATUS.md)（三平台路径强制）。

## 模块

| 模块 | 内容 |
|---|---|
| `mos4j-core` | 共享词汇表（`CancellationToken`）、事件键常量（字段名契约锚点）、扩展点接口（`SkillAudit`） |
| `mos4j-llm` | ChatModel 抽象、共享值类型、三协议适配（OpenAI / Anthropic / Gemini，流完整性 + usage 口径归一）、重试装饰器（默认不装配即透传） |
| `mos4j-tool` | 工具抽象、注解+反射 Schema、注册表、内置工具（文件/命令/grep/glob/ls/current_time/grant_access）、工具面延后加载（tool_search/activate）、联网工具（web_search/web_fetch）、**技能（`tool/skill`：目录发现 + `SKILL.md` frontmatter + 清单渲染 + `skill` 工具）**、工具规范（Guideline） |
| `mos4j-session` | 事件日志（append-only）、投影、自愈、审计实现（`SessionApprovalAudit` / `SessionSkillAudit`） |
| `mos4j-driver` | 回合驱动（ReAct 等）+ `net.hegq.mos4j.agent`（Config / Agent / Describe 快照）+ 取消贯通（`Agent.run(..., CancellationToken)`） |
| `mos4j-execpath` | 工具调用编排（守卫→钩子→冻结→执行→回填）、取消贯通（`executeBatch(..., CancellationToken)` 重载，取消不留空洞） |
| `mos4j-hook` | Guard/Hook 双轨制、载荷冻结接线、审批通道（四档结局） |
| `mos4j-policy` | 名字级 / 参数级安全策略（RE2J） |
| `mos4j-fs` | 文件 jail（Lexical + Realpath 双校验，读也走 Realpath）、活根集合、写前观察门、版本令牌 |
| `mos4j-sandbox` | 本地运行器（宿主固定工作目录 / 超时上界 / 输出上限 / 环境裁剪）、资源限制、进程组击杀降级、路径强制（macOS Seatbelt / Linux Landlock（FFM 直调、零外部依赖）/ Windows 未实现即 fail-closed） |
| `mos4j-context` | 上下文预算与压缩（切割点合法性 / 旧摘要折叠）、输出限流与落盘逃生通道、提示词分节（含 `skills` 段与 `PromptSections.insert`）、上下文文件发现、任务状态审计门 |
| `mos4j-sdk` | 门面：`Mos4j.builder()...build()` 一次依赖拿到可跑 Agent（协议缺省 OpenAI，文件工具缺省装配，技能按 `skills(dirs)` 可选装配）+ CLI `mos4j`（单轮最小形态） |
| `mos4j-spring-boot-starter` | **按需模块（不进默认 reactor）**：Spring Boot 自动装配（属性化 + 条件装配 + 缺配置启动失败）；用法见 [`docs/spring-boot-starter.md`](docs/spring-boot-starter.md) |

运行时依赖只有 Jackson 家族 / RE2J / SnakeYAML 系 / JDK（I7）。JDK 25（`--release 25`）。Spring 只允许出现在按需的 starter 里。

## 契约文档（`docs/contract/`）

| 文档 | 内容 |
|---|---|
| `INVARIANTS.md` | 七条不变式（合入门） |
| `MECHANISMS.md` | 十个机制（不变式的代码落法） |
| `API.md` | 事件字段名 / 签名 / 文案表 |
| `fieldnames.json` | 字段名冻结清单（守护测试基线；**80 条 / 71 名 / SHA-256 `3d35911d…`**） |
| `L1/L2/L3-SCENARIOS.md` | 三层场景清单 |
| `INVARIANT-TEST-MAP.md` | 不变式 × 测试映射（无空项门） |
| `INVARIANT-TESTS.md` | 不变式逐条台账（Go 原测试 ↔ Java 守护测试） |
| `REVIEW-SHEET.md` | **人工语义评审表（G1–G6）**：七条不变式 × 十机制 × 三协议口径 × 自创机制 × 诚实边界，逐行签字 |
| `SELF-INVENTED.md` | 自创机制登记 |
| `J1-STATUS.md` … `J6-STATUS.md` / `J7-STATUS.md` / `J8-STATUS.md` | 各里程碑 / 批次的实现与验证状态（权威口径） |

整体方案见 `docs/mos4j-port-plan.md`，任务列表见 `docs/mos4j-tasks.md`，推进方案见 `docs/mos4j-execution.md`。

## 构建

```bash
./mvnw verify          # 或 mvn verify（需 JDK 25）：12 个 SDK 模块

./mvnw -Pstarter verify # 额外构建按需模块 mos4j-spring-boot-starter
```

沙箱内不能写 `~/.m2` 时用本地仓库：`mvn -Dmaven.repo.local=.m2repo verify`。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。核心纪律：事件字段名只从 `FieldNames` 引用；`ToolCall.args` 保持原始 JSON 字符串；SDK 不 import Spring；正则一律 RE2J；技能正文只走 `skill` 工具不走 `read`；fail-closed 不放宽。

## License

TBD
