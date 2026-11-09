# Changelog

本项目的版本号真源是父 POM 的 `<version>`（`net.hegq:mos4j`）。

## [0.1.0] — 未发布

首个发布版。内容按交付批次排列（每批都有守护测试，文末列机械门基线）。

### 框架本体

- **J1 最小垂直切片**：OpenAI 协议 + 工具 + 会话（JSONL）+ ReAct 驱动；字段名冻结清单（80 条 / 71 名）。
- **J2 安全纵深**：双轨制（Hook / Guard 分离）、载荷冻结、审批四档（含 `UNAVAILABLE`）、审计事件不进投影。
- **J3 文件与沙箱**：文件 jail（词法 + Realpath 双校验）、写前观察门（read-before-write）、命令沙箱、Describe 边界快照。
- **J4 上下文工程**：输出限流（行/字节双限 + 定向保留）、截断落盘逃生通道、压缩（两段式 token 估算）、提示词分节装配。
- **J5 工具面**：`grep` / `glob` / `ls` / `current_time` / `grant_access` / `tool_search` + `activate`（延后加载）/ `web_search` / `web_fetch`（SSRF 四条防线）。
- **J6 协议与硬化**：Anthropic + Gemini 协议适配、取消贯通、**重试装饰器**、并发硬化、fuzz、SDK 门面 + CLI。

### 技能机制（J7）

- `SKILL.md` 目录发现 + 渐进式披露 + 条件注册的 `skill` 工具（工具面 13 → 14）。
- 两个审计事件（`skill/activate` / `skill/allowed-tools-mismatch`）+ `SkillAudit`。
- 原子重扫（数据面刷新，不是组件热加载）；Starter 相对目录以 `mos4j.workspace` 为锚。

### 路径强制（J8，macOS 优先）

- macOS Seatbelt：profile 形态与规则顺序与 Go 一致（`deny file-write*` + `/dev/null` + 逐写根放行）。
- SDK **缺省装配**（写根 = 工作区）+ **动态写根**（`grant_access` 授权后下一次 shell 调用生效）。
- 装配期可用性探测（不通就报错，不退化成「没隔离的假装隔离」）+ 显式出口 `noPathScope()`。
- Linux Landlock 后端代码在位（JDK 25 FFM，零依赖零 JNI）但**未在 Linux 验证 → 默认不装配**；Windows 未实现。

### 能力缺口收口（与参考实现逐项对位后）

- **policy 可见性接线**：可见性与运行时守卫读同一份名单（`PolicySlot`），不再出现「看得见却调不动」。
- **ChatModel 装饰器**：`Logging` / `Timeout` / `Fallback` / `Caching`（显式嵌套；`Caching` = **本地**结果缓存，与服务端 prompt cache 不是一回事）。
- **运行期模型目录**：`ModelOption` / `SwitchableChatModel` / `ModelCatalog`（切换下一轮生效，凭据不进快照）。
- **Describe 补充与关闭**：`DescribeContributor` 槽 + `Agent implements AutoCloseable`（幂等、逐个关、关不掉不拖垮其余）。

### MCP 客户端（2026-11-08 起并入 `mos4j-tool`）

- 官方 MCP Java SDK **2.1.0**（不手写 JSON-RPC）；官方 SDK 在 `mos4j-tool` 里是 **optional 依赖**（不传递给下游宿主），要用 MCP 的宿主自己显式声明官方 SDK 坐标；包路径仍是 `net.hegq.mos4j.mcp`。
- 真 stdio 子进程 E2E、多服务器管理器（两阶段换代 / 失败隔离 / scope 隔离）、三档曝光（direct / deferred / hidden）。
- 服务器说明每会话注入一次（32KB 预算、user 侧）；**认证**（每次请求取一次 token，401/403 归认证类）；**资源工具三个**。
- `mcp_servers` 提示词分节；Describe 快照；`Agent.close()` 一并关掉连接与子进程。
- MCP 工具与资源工具**不进**内置 14 工具冻结清单。

### 其他

- **Jackson 3**（`tools.jackson.core:*:3.2.4`）；YAML 用 SnakeYAML Engine。
- **Spring Boot Starter**（按需模块，26 个配置属性）：路径强制开关、`require-finish`、`max-tokens`、技能目录等。
- **对外版本 API**：`net.hegq.mos4j.core.Version`（与 Maven 坐标同步，由 `VersionTest` 对齐）+ CLI `--version`。
- **许可证**：MIT（仓库根 `LICENSE` + POM `<licenses>`）。
- **发布配置**：`release` profile（源码 / javadoc 附件 + GPG 签名）、`internal-publish` profile（内部私服地址，**由 `-D` 注入**）、`<scm>` 元数据；**发布目标：内部私服**（Maven Central 暂缓），私服地址与凭证待填。
- **CLI**：`--skills DIR`（可重复，技能目录）。
- **CI**：新增 `examples` job（`-Pexamples verify` + `-Pexamples,mcp verify` + examples 依赖审计）；C6 源码扫描范围覆盖 **6 个 SDK 模块的 `src/main` + `src/test`**；根工程的 `-Pmcp` job 已删（MCP 进默认 reactor）。
- **MCP**：streamable-http 真服务器 E2E（测试作用域嵌入式 Jetty）、结果投影按类型占位、deferred 工具同轮再声明（MCP 层已证明）。

### SDK 由十二模块合并为六模块（2026-11-08）

- **模块清单**：`mos4j-core` / `mos4j-model` / `mos4j-runtime` / `mos4j-tool` / `mos4j-session` / `mos4j-agent`。
  合并前是十二个过细的模块（`llm` / `fs` / `sandbox` / `tool` / `session` / `hook` / `execpath` / `context` / `driver` / `policy` / `sdk` / `mcp`）。
- **改名**：`mos4j-llm` → **`mos4j-model`**（包路径 `net.hegq.mos4j.llm.*` → `net.hegq.mos4j.model.*`；
  原 `llm.model` 子包上提，消除 `model.model`）；只承载**对话补全形状**的模型（ASR / TTS / 图像生成将来另立按需模块）。
- **MCP 并入 `mos4j-tool`**：包路径仍是 `net.hegq.mos4j.mcp`（不破坏既有 import）；
  官方 MCP SDK（`mcp-core` / `mcp-json-jackson3`）在 `mos4j-tool` 里是 **optional 依赖**，不传递给下游宿主；
  根工程的 `-Pmcp` profile **取消**（MCP 进默认 reactor），examples 侧只留局部 `mcp` profile（`-Pexamples,mcp`）。
- **边界门从模块级改为包前缀级**：合并后同层能力住进同一模块，依赖方向改由
  `BoundaryRulesTest` 守（能力层包不得 import `agent` / `driver` / `sdk` / `cli`），并新增「默认 reactor 恰为六个模块」的门。
- **`DescribeContributor` 下沉到 `net.hegq.mos4j.core`**：解决 tool → agent 与 agent → tool 的 Maven 环（零依赖共享 SPI）。
- **依赖纪律**：父 POM 禁列不变；官方 MCP SDK 需要的 `org.slf4j*` 例外由原 `mos4j-mcp` 改挂到 `mos4j-tool`
  （窄 enforcer 覆盖，**范围不变**：Spring / Guava / ORM / Lombok / Logback 仍禁）——登记为 SI-52。

### Go 差异 13 项全部补齐（2026-11-08）

> 逐条语义见 [`docs/contract/GO-DIFF.md`](docs/contract/GO-DIFF.md)，公开 API 见 [`docs/contract/API.md`](docs/contract/API.md) §3.15。

- **子代理（#1）**：普通工具模型——独立会话、收窄工具面（声明白名单 ∩ 父可见集）、固定 JSON 回执、清单进静态系统提示词；子代理失败仍是**普通工具结果**，不让父轮次失败。
- **续跑（#2）**：`ResumeOptions` + 专用 `NothingToResumeException`；沿用原轮次号、载荷原样重放、走普通安全链、无可续时保持挂起。
- **审批先验（#3）**：同轮 + 同调用 + 同工具且仍悬空才复用；只有 `APPROVED` / `REJECTED` 可复用；复用仍写完整审计对。
- **会话派生读（#4）**：五个纯读方法（待答审批 / 审批结果 / 同轮决定 / 中断轮次 / 悬空调用）。
- **协议注册表（#6）**：`ModelRegistry`（名字 → 工厂，`Map<String,String>` 配置，未知名字 fail-closed）。
- **提示词构建器（#8）**：宿主可显式注入分节构建器（与整串互斥）；框架在宿主 Builder 的**副本**上装配自己的分节。
- **取消原因（#11）**：`CancellationToken.cancel(cause)` / `cause()`（首次原因生效），原因进 `turn/end.error`。
- **运行期可调参数（#12）**：`Agent.setConcurrency` / `setMaxDecisionRounds` / `setRules`（每轮现取；参数级规则需装配期先装）。
- **本地缓存装饰器（#13）**：`CachingChatModel`（只缓存非流式成功；缓存键覆盖完整请求上下文）。
- **示例集（#10）**：7 个示例模块 + 共享 testkit（脚本化模型替身，**不碰网络**）；独立 `examples` profile，不进默认 reactor。
- 有意差异登记为 **SI-43…SI-51**（装配形状与失败时机不同，语义等价）。

### Go 差异补齐与复核（2026-11-07）

- **补齐三项**（源码级初查选定的 #5 / #7 / #9）：
  - **压缩三开关**：`CompactionFailurePolicy`（fail-closed / skip-and-continue）、摘要超时（缺省 3 分钟，`Duration.ZERO` = 不限，负值拒绝）、`disableCompaction` 与已装压缩器冲突时 fail-fast；压缩失败**不写**半截事件（冻结清单无 `failed` 字段），只经回调报告。
  - **上下文文件装配**：候选名单与扫描顺序与 Go 同口径（全局目录 → 由向外内 → 工作目录）、真实路径去重、BOM 去除、32KB 预算、行边界裁剪、缺目录 fail-closed、坏文件降级为 warning；内容进静态系统提示词，warnings 进 Describe caveats；Starter 新增 `mos4j.context-files.*`。
  - **运行期根目录管理**：`Agent.addRoot/addReadRoot/addWriteRoot/removeRoot/roots`（读写分离、锚点不可移除、移除未知根报错），文件工具与 shell 沙箱共用同一份根集合。
- **逐行复核后补记的差异**：未实现项由 7 项增至 **10 项**（新增：取消原因、运行期可调并发/轮数/参数规则、`llm.WithCache`）；**这 10 项已于 2026-11-08 全部补齐**（见上一节）。
- 有意差异登记为 SI-39…SI-42（装配形状与失败时机不同，语义等价）。

### 已知边界（发布时照实写进说明）

- macOS Seatbelt 的**强制效果**未在真机验收（本机 `sandbox_apply: Operation not permitted`）——验收包见 `tools/seatbelt-acceptance/`。
- Linux / Windows 后端未验证 / 未实现。
- 技能 `allowed-tools` 只诊断不生效（不造第二套安全真相）。
- 评审表 `docs/contract/REVIEW-SHEET.md` 的逐行人工签字是发布前的最后一道门（112 条判定项，**108 条待签**）。
- 相对 Go 参考实现的 **13 项能力缺口已全部补齐**（2026-11-08）；但**机器测试 ≠ 跨语言回放**——回放比对尚未做，登记为 B25（需人做）。

### 机械门基线

- 默认 reactor **846 通过 / 0 失败 / 0 错误 / 7 跳过**（六模块，106 个测试类）；`-Pstarter` **870 / 7**；`-Pexamples` **871 / 7**（示例自身 25 条）；`-Pexamples,mcp` **874 / 7**（示例自身 28 条）。
- 6 个 SDK 模块 + starter + examples 8 模块 `dependency:analyze` 无告警；C6（Spring / `java.util.regex`）均 0；裸 NUL 字节 0。
