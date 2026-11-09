# MoS4J

参考 [MoS](https://github.com/puras/mos)（Go）的语义重写——用 Java 实现的智能体基础框架。**SDK 是本体**，Spring Boot Starter 只是上层可选薄封装（按需模块，见 [`docs/spring-boot-starter.md`](docs/spring-boot-starter.md)）。

> 当前状态：**J1–J8 全部落地 + 加固批次 + Go 对拍批次（B13）+ Jackson 3 升级（B16）收口**——J1（OpenAI 协议 + 工具 + 会话 + ReAct 驱动）、J2（安全纵深：双轨制/载荷冻结/审批/审计）、J3（文件 jail + 写前观察门 + 命令沙箱 + Describe 边界快照）、J4（输出限流 + 截断落盘 + 上下文压缩 + 提示词分节装配）、J5（grep/glob/ls/current_time/grant_access + tool_search 延后加载 + web_search/web_fetch SSRF 四防线 + Guideline）、J6（**Anthropic + Gemini 协议适配、取消贯通、重试装饰器、并发硬化、fuzz、SDK 门面 + CLI**）；加固批次补齐**取消贯通 driver→planner→agent**、**模块边界门（C3）**、**依赖审计负向验证**；B13 批次把工具面与 Go 参考版对齐（文件工具名 `read`/`write`/`edit`、`edit` 改 `edits[]`、`grep`/`glob`/`ls`/`shell` 补参数、`tool_search` 参数名改 `limit`），并用 `ToolContractTest` **机器守护工具名与参数名清单**；**J7 批次**实现 Go 的**技能（skill）机制**（`SKILL.md` 目录发现 + 渐进式披露 + 条件注册的 `skill` 工具 + `skill/activate`·`skill/allowed-tools-mismatch` 审计事件 + 原子重扫 + `/name` 手势），前置的 **B16 Jackson 3 升级**也已落地。三协议 L1 场景全绿（Anthropic 12 + OpenAI 5 + Gemini 6，`@Disabled` 归零）、L2 16/16、L3 D1–D4 全绿，默认 reactor 基线 **846 通过 / 7 跳过**（含按需 starter 则 **870/7**；`-Pexamples` **871/7**、`-Pexamples,mcp` **874/7**，其中示例自身 25 与 28 条）。**MCP 批次**新增 **MCP 客户端接入**（官方 MCP Java SDK 2.1.0，不手写 JSON-RPC；**2026-11-08 起并入 `mos4j-tool`，官方 SDK 为 optional 依赖，宿主须自己显式声明官方 SDK 坐标**，包路径仍是 `net.hegq.mos4j.mcp`），已落地契约层、公开名、工具适配、**真 stdio 子进程 E2E**、**多服务器管理器**（两阶段换代 / 失败隔离 / scope 隔离）、**三档曝光**（direct / deferred / hidden）与**服务器说明每会话注入一次**；**M3 批次**补上**认证**（`McpTokenProvider` 每次请求取一次、401/403 归认证类）、**MCP 资源工具三个**（`mcp_resource_list` / `mcp_resource_templates` / `mcp_resource_read`，工具面恒为 3）、**policy / guard 联动**（被拒的 MCP 工具不进模型清单也调不动）、**`mcp_servers` 提示词分节**与**Describe 快照 / `Agent.close()`**（长连接与 stdio 子进程一并关掉）；Starter 接入**有意不做**（Starter 不引官方 SDK）。**本轮还补齐与 Go 的主要缺口**：**policy 可见性接线**（可见性与运行时守卫读同一份名单，不出现「看得见却调不动」）、**ChatModel 装饰器**（`Logging` / `Timeout` / `Fallback`，显式嵌套；Cache 仍后置）与**运行期模型目录**（`ModelOption` 目录、下一轮生效、凭据不进快照）。**J8 批次**把**路径强制**补上，口径是**「先过 Mac 的」**：macOS Seatbelt 与 Go 逐条对齐（profile 形态与规则顺序一致、`/dev/null` 放行、写根软链规范化、SBPL 转义、绝对路径校验、`sandbox-exec` 缺失即 fail-closed），且**已被内核的 profile 解析器接受**（本机真机用例）；SDK **缺省装配**（写根 = 工作区，对齐 Go `deriveScope`），并补齐 Go 的**动态 Scope**（会话里 `grant_access` 授权的新目录，shell 跟着能写）。Linux 的 **Landlock LSM 后端代码在位**（JDK 25 FFM 直调，零依赖零 JNI）但**未在 Linux 上验证 → 默认不装配**；Windows 未实现且如实 fail-closed。**G1–G6 门的人工评审表已预填，Q1–Q8 已拍板并实施**（门面显式出口 / 装配期可用性探测 / 动态写根 / 协议旋钮可达）；**评审表的逐行签字仍待人做**；macOS 的 Seatbelt **强制效果**在本机仍拿不到（`sandbox_apply: Operation not permitted`，退出码 71，那 1 条用例如实标为跳过），Linux/Windows 按口径暂不验证。**真机验收包已备好**（[`tools/seatbelt-acceptance/README.md`](tools/seatbelt-acceptance/README.md)：一条命令跑三条用例 + 可复制的探针脚本 + 结果回填清单）。**与 Go 参考实现的差异已逐行复核并登记**（[`docs/contract/GO-DIFF.md`](docs/contract/GO-DIFF.md)）：**13 项差异已全部补齐**（2026-11-08）——**子代理**（独立会话 + 收窄工具面 + 固定 JSON 回执）、**续跑**（沿用原轮次 + 载荷原样重放 + 无可续保持挂起）、**审批先验**（同轮同调用同工具且仍悬空才复用）、**会话派生读**（五个纯读方法）、**协议注册表**（名字 → 工厂）、**可注入提示词构建器**（与整串互斥 + 宿主副本不被改）、**示例集**（7 个示例 + 共享 testkit）、**取消原因**、**运行期可调参数**、**本地缓存装饰器**，连同此前的**压缩三开关**、**上下文文件装配**、**运行期根目录管理**；差异的语义与 API 逐条登记在 [`docs/contract/API.md`](docs/contract/API.md) §3.15 与 [`docs/contract/SELF-INVENTED.md`](docs/contract/SELF-INVENTED.md)（SI-43…SI-51），Go 原测试名逐字对位在 [`docs/contract/INVARIANT-TESTS.md`](docs/contract/INVARIANT-TESTS.md) §3.9；**机器测试 ≠ 跨语言回放**（B25，需人做）。进度见 [`docs/contract/J1-STATUS.md`](docs/contract/J1-STATUS.md)、[`J2-STATUS.md`](docs/contract/J2-STATUS.md)、[`J3-STATUS.md`](docs/contract/J3-STATUS.md)、[`J4-STATUS.md`](docs/contract/J4-STATUS.md)、[`J5-STATUS.md`](docs/contract/J5-STATUS.md)、[`J6-STATUS.md`](docs/contract/J6-STATUS.md)（含 §7 加固批次、§8 Go 对拍批次）、[`J7-STATUS.md`](docs/contract/J7-STATUS.md)（技能机制 + Jackson 3 升级）与 [`J8-STATUS.md`](docs/contract/J8-STATUS.md)（路径强制，macOS 优先）。

## 模块

| 模块 | 内容 |
|---|---|
| `mos4j-core` | 共享词汇表（`CancellationToken` / `ToolContext`）、事件键常量（字段名契约锚点）、零依赖扩展点接口（`SkillAudit` / `DescribeContributor`） |
| `mos4j-model` | ChatModel 抽象、共享值类型（`Message` / `Role` / `ToolCall` / `Usage`）、三协议适配（OpenAI / Anthropic / Gemini，流完整性 + usage 口径归一）、传输、装饰器（Logging / Timeout / Fallback / Cache）、协议注册表（名字 → 工厂） |
| `mos4j-runtime` | 文件 jail（Lexical + Realpath 双校验，读也走 Realpath）、活根集合、写前观察门、版本令牌、本地运行器（宿主固定工作目录 / 超时上界 / 输出上限 / 环境裁剪）、进程组击杀降级、路径强制（**macOS Seatbelt（已验证，缺省装配）** / Linux Landlock（FFM 直调、代码在位但未验证 → 默认不装配）/ Windows 未实现即 fail-closed） |
| `mos4j-tool` | 工具抽象、注解+反射 Schema、注册表、内置工具（文件 / 命令 / grep / glob / ls / current_time / grant_access）、工具面延后加载（tool_search / activate）、联网工具（web_search / web_fetch）、**技能（`tool/skill`：目录发现 + `SKILL.md` frontmatter + 清单渲染 + `skill` 工具）**、**子代理（`tool/subagent`：独立会话 + 收窄工具面 + 固定 JSON 回执）**、Guard/Hook 双轨制、载荷冻结接线、审批通道（四档结局）、名字级 / 参数级安全策略（RE2J）、工具规范（Guideline）、**MCP 客户端接入**（官方 MCP Java SDK 2.1.0，**optional 依赖**：宿主要用 MCP 须自己显式声明官方 SDK；契约层 + 公开名 + 工具适配 + 真 stdio 子进程 E2E + streamable-http 真服务器 E2E + 多服务器管理器 + 三档曝光 + 说明每会话注入一次 + 认证 + 资源工具三个 + policy 联动 + 结果投影口径）→ 详见 [`docs/contract/MCP-STATUS.md`](docs/contract/MCP-STATUS.md) |
| `mos4j-session` | 事件日志（append-only）、投影、自愈、审计实现（`SessionApprovalAudit` / `SessionSkillAudit`）、上下文预算与压缩（切割点合法性 / 旧摘要折叠）、输出限流与落盘逃生通道、提示词分节（含 `skills` 段与 `PromptSections.insert`）、上下文文件发现、任务状态审计门 |
| `mos4j-agent` | 回合驱动（ReAct 等）+ `net.hegq.mos4j.agent`（Config / Agent / Describe 快照）+ 工具调用编排（守卫→钩子→冻结→执行→回填）+ 取消贯通（`Agent.run(..., CancellationToken)`）+ 门面 `Mos4j.builder()...build()`（一次依赖拿到能跑的 Agent）+ CLI `mos4j`（单轮最小形态，含 `--skills DIR`） |
| `mos4j-spring-boot-starter` | **按需模块（不进默认 reactor）**：Spring Boot 自动装配（属性化 + 条件装配 + 缺配置启动失败）；用法见 [`docs/spring-boot-starter.md`](docs/spring-boot-starter.md) |

运行时依赖只有 Jackson 家族 / RE2J / SnakeYAML 系 / JDK（I7）。JDK 25（`--release 25`）。Spring 只允许出现在按需的 starter 里。

## 契约文档（`docs/contract/`）

| 文档 | 内容 |
|---|---|
| `INVARIANTS.md` | 七条不变式（合入门） |
| `MECHANISMS.md` | 十个机制（不变式的代码落法） |
| `API.md` | 事件字段名 / 签名 / 文案表 |
| `fieldnames.json` | 字段名冻结清单（守护测试基线；**80 条 / 71 名 / SHA-256 `3d35911d…`**——口径是「<b>唯一名字集合</b>按字典序用 `\n` 拼接后的摘要」，<b>不是文件字节哈希</b>；由 `FieldNameContractTest` 钉死绝对值） |
| `L1/L2/L3-SCENARIOS.md` | 三层场景清单 |
| `INVARIANT-TEST-MAP.md` | 不变式 × 测试映射（无空项门） |
| `INVARIANT-TESTS.md` | 不变式逐条台账（Go 原测试 ↔ Java 守护测试） |
| `REVIEW-SHEET.md` | **人工语义评审表（G1–G6）**：七条不变式 × 十机制 × 三协议口径 × 自创机制 × 诚实边界，逐行签字（**已预填；Q1–Q8 已拍板实施，逐行签字仍待人做**） |
| `REVIEW-QUESTIONS.md` | 评审表中需要维护者拍板的问题清单（Q1–Q8，**已全部拍板并实施**，每条带「裁决与实施」栏） |
| `SELF-INVENTED.md` | 自创机制登记 |
| `GO-DIFF.md` | **Go↔Java 差异登记表**（13 项已全部补齐；逐条语义 + 落点 + 有意差异） |
| `J1-STATUS.md` … `J6-STATUS.md` / `J7-STATUS.md` / `J8-STATUS.md` | 各里程碑 / 批次的实现与验证状态（权威口径） |

整体方案见 `docs/mos4j-port-plan.md`，任务列表见 `docs/mos4j-tasks.md`，推进方案见 `docs/mos4j-execution.md`。
发布相关见 [`docs/release.md`](docs/release.md)（发布清单与步骤；**发布目标：内部私服**（2026-11-07 裁决，Maven Central 暂缓）；**发布配置部分就绪**：`release` profile 已配源码 / javadoc 附件 + GPG 签名，`internal-publish` profile 已配私服地址（**由 `-D` 注入，不写死**），POM 已补 `<scm>` 与 `<licenses>`（MIT）；**仍缺**：私服地址与账号、GPG 密钥、人工签字、Seatbelt 真机验收）与 [`CHANGELOG.md`](CHANGELOG.md)（0.1.0 发布说明草稿）。对外版本号取自 `net.hegq.mos4j.core.Version`（与 Maven 坐标同步，CLI `--version`）。

## 构建

```bash
./mvnw verify            # 或 mvn verify（需 JDK 25）：6 个 SDK 模块
                         #   （core / model / runtime / tool / session / agent）

./mvnw -Pstarter verify  # 额外构建按需模块 mos4j-spring-boot-starter
./mvnw -Pexamples verify # 额外构建示例集（7 个示例 + testkit）
```

沙箱内不能写 `~/.m2` 时用本地仓库：`mvn -Dmaven.repo.local=.m2repo verify`。

## 示例

[`examples/`](examples/README.md) 是 7 个可复制运行的示例（`minimal` / `chat` / `agents` / `skills` / `subagent` / `recovery` / `mcp`）：
它们是「给人读的装配说明书」——每个目录一个 README，讲清**怎么装、装出什么、边界在哪**；
用例用共享的脚本化模型替身（`examples/testkit`），**不碰网络**，因此能进 CI。

```bash
./mvnw -Pexamples verify         # 7 个示例 + testkit
./mvnw -Pexamples,mcp verify     # 额外带 MCP 示例（自带 stdio 回声服务器）
```

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。核心纪律：事件字段名只从 `FieldNames` 引用；`ToolCall.args` 保持原始 JSON 字符串；SDK 不 import Spring；正则一律 RE2J；技能正文只走 `skill` 工具不走 `read`；fail-closed 不放宽。

## License

[MIT](LICENSE)（版权 `Copyright (c) 2026 puras`）。
