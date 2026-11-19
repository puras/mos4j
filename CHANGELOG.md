# Changelog

本项目的版本号真源是父 POM 的 `<version>`（`net.hegq:mos4j`）。

## [0.1.0] — 未发布

首个发布版。内容按交付批次排列（每批都有守护测试，文末列机械门基线）。

### 框架本体

- **J1 最小垂直切片**：OpenAI 协议 + 工具 + 会话（JSONL）+ ReAct 驱动；字段名冻结清单（80 条 / 71 名）。
- **J2 安全纵深**：双轨制（Hook / Guard 分离）、载荷冻结、审批四档（含 `UNAVAILABLE`）、审计事件不进投影。
- **J3 文件与沙箱**：文件 jail（词法 + Realpath 双校验）、写前观察门（read-before-write）、命令沙箱、Describe 边界快照。
- **J4 上下文工程**：输出限流（行/字节双限 + 定向保留）、截断落盘逃生通道、压缩（两段式 token 估算）、提示词分节装配。
- **J5 工具面**：`grep` / `glob` / `ls` / `current_time` / `grant_access` / `tool_search` + `activate`（延后加载）/ `web_search` / `web_fetch`（SSRF 五条防线，全在可替换的缺省后端里）。
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

- 官方 MCP Java SDK **2.2.0**（2026-11-18 由 2.1.0 升级；不手写 JSON-RPC）；官方 SDK 在 `mos4j-tool` 里是 **optional 依赖**（不传递给下游宿主），要用 MCP 的宿主自己显式声明官方 SDK 坐标；包路径仍是 `net.hegq.mos4j.mcp`。
- 真 stdio 子进程 E2E、多服务器管理器（两阶段换代 / 失败隔离 / scope 隔离）、三档曝光（direct / deferred / hidden）。
- 服务器说明每会话注入一次（32KB 预算、user 侧）；**认证**（每次请求取一次 token，401/403 归认证类）；**资源工具三个**。
- `mcp_servers` 提示词分节；Describe 快照；`Agent.close()` 一并关掉连接与子进程。
- MCP 工具与资源工具**不进**内置 14 工具冻结清单。

### 其他

- **Jackson 3**（`tools.jackson.core:*:3.2.4`）；YAML 用 SnakeYAML Engine。
- **Spring Boot 4.1.2**（2026-11-18 由 3.5.16 升级）：Spring Framework **7.0.10** / Jakarta EE 11 线。
  代码**零改动**（`@AutoConfiguration` / `@ConfigurationProperties` / `ApplicationContextRunner` / `AutoConfigurations` 全部兼容），
  `META-INF/spring/...AutoConfiguration.imports` 机制在 4.x 仍有效（实测加载 31 个配置属性元数据正常生成）。
  **连带影响：Jetty 工具链** —— 4.1.2 的 BOM 把 Jetty 管到 **12.1.14**，`mos4j-tool` 原先显式钉的 12.0.36 会与 BOM 管的传递构件混族；
  已两处（`jetty-ee10-servlet` / `jetty-server`）同步升到 12.1.14，MCP streamable-http 真服务器 E2E 3 条重跑通过。
  **Jackson 不被覆盖**：父 POM 的 Jackson 声明在 BOM import 之前（先声明者赢），`dependency:tree` 实测仍为 3.2.4 / annotations 2.22。
- **Spring Boot Starter**（按需模块，31 个配置属性，由 `spring-boot-configuration-processor` 生成元数据）：路径强制开关、`require-finish`、`max-tokens`、技能目录等。
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
- **示例集（#10）**：8 个示例模块 + 共享 testkit（脚本化模型替身，**不碰网络**）；独立 `examples` profile，不进默认 reactor。（2026-11-18 新增 `model` 纯模型示例：直连 `ChatModel`，非流式 / 流式 / 多轮 / 三协议装配 / 期限取消；守护 `ModelExampleTest` 6 条，协议层用本地假 HTTP 端点抽样，不碰外网。2026-11-19 模型族扩展后补上装配面：`ModelSurface`（registry / catalog / 装饰器链 / 异步任务与产物）+ `ScriptedTaskPoller`，守护 `ModelSurfaceTest` 13 条；同日再把它做成**循环对话**（`ModelChatLoop` + `--loop`，宿主自留历史）并把配置收进 **`ModelConfig` 常量**，守护 `ModelChatLoopTest` 11 条 + `ModelConfigTest` 7 条，该模块 19 → **37 条**；`STREAM_IN_LOOP` 开关后 `ModelChatLoopTest` **13 条** + `ModelConfigTest` **8 条**，该模块 → **40 条**。）
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
- 评审表 `docs/contract/REVIEW-SHEET.md` 的逐行人工签字是发布前的最后一道门（115 条判定项，**111 条待签**）。
- 相对 Go 参考实现的 **21 项能力缺口已补齐**（2026-11-15 第六轮补 #24/#25/#26：与 Go v0.2.0 之后三个提交对齐）；但**机器测试 ≠ 跨语言回放**——回放比对尚未做，登记为 B25（需人做）。
- **空回复不再冒充完成**（2026-11-15，对齐 Go `ff229ec`）：模型这一轮既无正文（空白算无）又无工具调用时，本轮以 `turn/end{reason:"error"}` 收尾并抛 `NoAnswerException`（宿主可精确 `catch`），**不记 `completed`**。事实照旧留痕（assistant 事件的思维链与用量都在），但空回复**不进模型投影**（部分厂商拒收「既无 content 也无 tool_calls」的 assistant 消息，一个空回复能把会话卡死）。⚠️ 行为变更：以前这种轮次返回空串并记 `completed`。
- **`finishReason` 落日志**（2026-11-15，对齐 Go `c610f9f`）：assistant 事件新增 `finishReason`（`omitempty`，未设置时键不出现），截断的正文照样交付但日志能分出「说完的」与「被切开的」。⚠️ **冻结字段清单由此变更**：80 条 / 71 个唯一名 → **81 条 / 72 个唯一名**（名字集合 SHA-256 `3d35911d…` → `ee43c9dd…`，文件字节 SHA-256 `aeb48e08…` → `a5938d52…`）。
- **流式增量出口已打通**（2026-11-14）：`Mos4j.Builder.onChunk(Consumer<StreamChunk>)` / `Config.Builder.onChunk(...)` 现在能把增量交给宿主。此前 `RunContext.onChunk` 与驱动侧全通，但门面没有入口——宿主能开 `stream(true)` 却拿不到任何增量。⚠️ 仍在调用线程同步回调，慢消费者会拖慢决策。
- **Gemini 结构化输出已实现**（2026-11-14）：`json_schema` → `responseMimeType=application/json` + `responseSchema`；`json_object` → 只发 MIME（对齐 Go。`name` 在 Gemini 协议无落点，不下发）。同时修正 `GenerationConfig` 把未设置字段发成 `null` 的问题（Go 全字段 `omitempty`）。仍未与真实 Gemini 服务对括。
- **`web_fetch` 的真实公网验收已完成**（2026-11-09）：本机实测 `https://example.com/` 200 `text/html; charset=utf-8`（title=Example Domain）、`http://example.com/` 200、`httpbin.org/status/404` → 404 当结果、`httpbin.org/gzip` gzip 正常解、跨源重定向拒绝、`http://127.0.0.1:1/` 拒绝内网（均为预期）。Wikipedia 超时系**本机 DNS 污染**（`curl` 同样超时、`dig` 得 `31.13.88.169` / `2001::1`），不是代码缺陷。仍未验的：反爬拦截、重定向到 CDN、需客户端证书的站点。
- **本轮由真实公网验收发现的真安全缺陷已修**：`PublicAddresses.isPublic` 当时放行 NAT64（`64:ff9b::7f00:1`）/ 6to4（`2002:7f00:1::`）/ Teredo（`2001::1:2:3:4`）与 TEST-NET、`0/8`、`224/4` 起整段——是**真实可用的 SSRF 绕过**（实例：本机 DNS 给 `en.wikipedia.org` 返回 `2001::1`，整条解析结果被当公网）。现按 Go `TestIsPublicIP` 完整清单（23 private + 4 public）逐条断言；内嵌 IPv4 一律拒（GO-DIFF #22）。

### 机械门基线

- 默认 reactor **980 通过 / 0 失败 / 0 错误 / 7 跳过**（六模块，116 个测试类）；`-Pstarter` **1004 / 7**；`-Pexamples` **1041 / 7**（示例自身 61 条）；`-Pexamples,mcp` **1044 / 7**（示例自身 64 条）。（2026-11-19 模型族扩展后复跑；3.2 节的 930/954/962/965 为 2026-11-18 的旧值。）
- 6 个 SDK 模块 + starter + examples 8 模块 `dependency:analyze` 无告警（model 于 2026-11-18 实核；2026-11-19 复跑受本机沙箱限制未能执行——`~/.m2` 不可写）；C6（Spring / `java.util.regex`）均 0；裸 NUL 字节 0。

---

## 未发布 —— 二次复核修复（2026-11-08）

> 触发问题：「除了验证，其他都 OK 了么」——逐项核对时用**出站报文抓包**发现的真缺口。
> 两者都不是文档问题，而是「声明了但没做到」：一个会让真实服务商多轮调用直接 400，一个让宿主以为发了的多模态内容静默消失。

### `mos4j-model`：**请求侧 `reasoning_content` 回传**（对齐 Go `injectReasoningContent`）

- DeepSeek / Qwen 等 thinking 模式要求把上一轮的思维链原样回传，不回传不是「少一个可选字段」而是**第二轮直接 400**。
- 修复：公开 `Message.reasoningContent` 本来就存着值，但出站报文里从来没有它（`MessageParam` 没这个字段）。现在 `MessageParam` 新增 `reasoning_content`，只对 **role=assistant 且非空**赋值；Go 因为生成的请求结构体没这个字段只能序列化后做字节注入，Java 的请求 DTO 是自己写的，多一个字段即得同样报文。
- **只改 OpenAI 兼容协议**：Anthropic 侧 Go 也不回传 thinking 块，不算缺口。
- 守护：`OpenAiChatModelTest.reasoningContent_isEchoedOnAssistantMessages` / `reasoningContent_absentWhenEmptyOrNonAssistant`。

### `mos4j-model`：**`ContentPart` 的 `audio` / `file` 三协议真实现**（对齐 Go `toOpenAIContent` / `anthropicContent` / `geminiParts`）

- 修复前：`file` 块出站是 `{"type":"file"}`（url / data 全丢）；Gemini 的 `audio` / `file` 直接丢掉——宿主以为发了，其实没发。
- **OpenAI**：`image` / `file` 都走 `image_url`（URL 优先，否则 `data:\u003cmime\u003e;base64,\u003cdata\u003e`，mime 缺省 `image/png`）；`audio` 走 `input_audio`，格式映射改成 Go 的口径（`audio/mpeg`→`mp3` 等，未知→`mp3`）。
- **Anthropic**：`file` → `document` 块（base64，`media_type` 原样用宿主给的 MIME，**不**补 `image/png`）；`audio` **不发块**（Go 也忽略，Anthropic 无对应块类型）。
- **Gemini**：`Part` 新增 `fileData`（含 `FileData` record）；`image` / `audio` 有 URL 走 `fileData` 否则 `inlineData`；`file` 只走 `fileData`。
- 守护：OpenAI 4 条 + Anthropic 2 条 + Gemini 4 条（全部断言出站报文）。

### 新的有意差异与登记

- **SI-53（新增）**：未知内容块类型 **fail-fast**（`IllegalArgumentException`），而不是 Go 的「当 `text` 兜底」。理由：把未知块当文本发 = 把空字符串送上去，模型看到的请求与宿主想的不一样且两端无错误线索。

---

## 未发布 —— web 工具与能力位对齐（2026-11-09）

> 触发问题：「还有什么功能需要处理的」——逐项核对 B 表 / 源码 TODO / GO-DIFF 对位表时，发现 **web 工具从未进入差异清单**。旧文档的「✅ 完全一致」只对工具名成立。

### `mos4j-tool`：`web_search` 参数与回执形状对齐 Go（GO-DIFF #17）

- **参数**：新增 `queries[]`（首选，1–4 条并行搜索后按 URL 去重合并）；`query` 降为单条容错；`limit` 缺省与上限都是 **8**（原为 5 / 10，与 Go 的 `DefaultMaxResults` 不符）。
- **回执**：纯文本 → **结构化 map**（`queries` / `summary` / `note` / `sources` / `count` / `truncated` / `content`），`ExecPath` 序列化成 JSON 回填（与 Go `json.Marshal` 同效果）。
- **失败语义翻案**：原实现把 provider 非 2xx 当结果回执；复核 Go 的 `Search.Execute` 后确认是**工具错误**（`web_search 失败: %w`）。只有「部分查询失败」才用结果 + `note`；全部失败才算整体错误。
- **接口破坏性变更**：`SearchProvider.search(String, int)` → `search(List<String>, int) → Outcome`；只有单查询 API 的服务商实现 `SearchProvider.Single` 后用 `WebSearchTool.of(...)` 装配（虚拟线程并行 + 去重 + 部分失败容忍）。
- 守护：`WebToolsTest` 9 条（结构化回执逐字段 / 多查询去重 / 单条容错 / 参数校验 / `limit` 钳制 / 失败是工具错误 / 部分失败进 note / 全部失败是错误）。

### `mos4j-tool`：`web_fetch` 抽出可注入 `Fetcher`（GO-DIFF #18）

> **已被下一节取代**：本节的 `Fetcher` 形状（`fetch(FetchRequest)` / `name()="pinned"` / 四条防线分居工具层与后端）在随后一轮全对齐中**二次重构**，保留此段仅作变更历史。

- 新增 `Fetcher` 接口（`name()` + `fetch(FetchRequest)` + 默认 `lastReadTruncated()`）；`PinnedConnector` 从「唯一实现」降为**缺省实现**（`implements Fetcher`，`name() = "pinned"`）。
- `WebFetchTool` 构造器：`(PinnedConnector, int)` → `()` / `(Fetcher)` / `(Fetcher, int)`。
- **换掉实现 = 换掉防线**：缺省 `PinnedConnector` 带四条 SSRF 防线（公网校验 / 地址钉住 / 同源重定向 / 体积超时），需要走代理 / 专线 / 测试内网的宿主自备实现并一并接过访问策略责任。
- 守护：`WebToolsTest` 3 条（自备后端可注入并拿到 `maxBytes` / 截断标记渲染成附注 / 必填校验）。

### `mos4j-model`：Anthropic 能力位对齐 Go（GO-DIFF #19）

- 删掉 `capabilities()` 里的 `STRUCTURED_OUTPUT`：Anthropic 协议根本没有 `response_format`，声明了就是假的（宿主会据此发 `responseFormat` 然后被无声忽略）。
- **保留 `REASONING`**：Java 侧真的实现了（`reasoningEffort` → `thinking.budget_tokens` + `thinking` / `thinking_delta` 两通道）——这里 Go 不声明属**欠报**，不是我们多报。
- 守护：`L1AnthropicScenariosTest.a_capabilitiesHaveNoStructuredOutput`。
- [`docs/contract/GO-DIFF.md`](docs/contract/GO-DIFF.md) 新增 #15 / #16 两条（均为「声明了但没做到」的真缺口，已修复）；`SELF-INVENTED.md` 新增 SI-53；评审表 113 条判定项 / **109 条待签**。

---

## 未发布 —— web_fetch 全对齐与传输层可测化（2026-11-09）

> 触发问题：「web_fetch 要不要全对齐 Go」——逐项核对 Go `tool/web/fetch.go` 与 `web.go` 后发现，**不只是缺 4 个特性，而是分层错了**：
> Java 把 SSRF 防线放在工具层，Go 全放在缺省后端；结果「换后端 = 换防线」在 Java 侧不成立（实测：注入后端被工具层拦死）。

### 分层重做：防线全部下沉到缺省后端（GO-DIFF #18）

- **工具层**（`WebFetchTool`）只做参数校验、字符截断、回执整形——**不解析 DNS、不跟重定向、不做地址判定**；`Fetcher` 可为 `null`，未装配在**执行期** fail-closed（对齐 Go `Fetch.Execute` 的 nil 检查，错误文案 `url 不能为空` / `web_fetch 未装配抓取后端（宿主注入 Fetcher 后才可用）` 都不带 `web_fetch 失败: ` 前缀）。
- **后端**（`PinnedConnector`）带全部五条防线 + 内容归类 + HTML 清洗；`Fetcher` 是可替换接口。
- **地址钉住改用裸 Socket**：`HttpURLConnection` / `HttpClient` 无拨号钩子，`Proxy.Type.DIRECT` 会忽略代理地址并重新解析主机名 = **假钉住**。现在手写 HTTP/1.1 GET（状态行 / 头 / chunked / gzip / 字节上限 / TLS-SNI）。
- **回执 6 键固定都在**（`url` / `status_code` / `title` / `content_type` / `truncated` / `content`）：Go 的 map 字面量无 `omitempty`；空标题发 `""`、`truncated` 发 `false`。上一版的「空值省略」**作废**。
- **非 2xx 是结果不是错误**（404 带 `status_code` 正常返回，因为「资源状态」本身就是模型要的信息）；**缺 Content-Type 响亮失败**（旧「空类型按文本」的宽容行为推翻）。

### 新公开面

- `FetchLimits` record（5MB 字节 / 10 万字符 / 30s / 5 跳 / UA，`defaults()` + `normalized()`）；`FetchResult` 顶层 record。
- `Fetcher`：`name()` + `fetch(String, FetchLimits)`（**破坏性变更**：旧 `fetch(FetchRequest)` 与 `lastReadTruncated()` 删除）。
- `Resolver` / `Dialer` 两个函数接口（对齐 Go 的 `HTTPFetcher.Resolver` / `.Dial`）：只注入解析器仍钉住（地址来源变了、防线不变）；注入拨号器就不再钉住（责任转移，javadoc 明写）。
- `PinnedConnector.name()` 返回 `"http"`（对齐 Go `HTTPFetcher.Name()`）。

### HTML → 纯文本

- 新增 `HtmlText`（包内可见）：标题提取、四类块级/脚本块处理、十项命名实体 + 十进制/十六进制数值实体、两趟解码、逐行压空白；六条正则逐字移植 Go，用 RE2J（C6 禁 `java.util.regex`）。
- **期望值用真 Go 产出**：本机 `go1.27.0` 跑参考实现（`.j7log2/htmlref/`），发现 3 处自写期望错，`HtmlText` 实现本身零修改。
- **两处有意偏离 Go**（登记 GO-DIFF #20 / #21）：Go 的十六进制实体解码因去前缀漏字符**从不生效**（`&#x41;` 原样输出），Java 按意图解；Go 对代理对码点输出 U+FFFD，Java 保留实体原文。
- **一处对齐 Go 可观测行为而非注释意图**（GO-DIFF #22）：Go 的四个内嵌 IPv4 分支实际永远返回 false（`embeddedIPv4` 返 16 字节 → `v4[0]==0` 必中），所以 NAT64 / 6to4 / Teredo **一律拒**；Java 同结论，不按内嵌地址放行。

### 测试

- `PinnedConnectorTransportTest`（**22 条，真 socket**）：出站报文与 Host / chunked + trailer + 非法长度 / gzip / 头名大小写 / 字节与码点双上限 / 404 / 三类非文本与缺 Content-Type / 同源与跨源重定向 / 跳数上限 / 注入解析器仍拦私网 / 非 HTTP 响应。
- `PinnedConnectorTest` **9** / `HtmlTextTest` 9 / `WebToolsTest` 22（search 9 + fetch 13）。
- `mos4j-tool` **315 → 358 → 359**；四套基线 **869/893/894/897 → 912/936/937/940 → 913/937/938/941 → 922/946/948/951 → 930/954/956/959 → 930/954/962/965 → 980/1004/1025/1028 → 980/1004/1041/1044**（末段 2026-11-19：模型族扩展（retry / decorate / catalog / wire / kind 谱系）+ model 示例跟上装配面 + 示例变循环对话与配置常量 + 循环对话可走流式 + 收掉 chat 示例里的异步/产物演示）。

---

## 未发布 —— wire 层：协议无关的 SSE 分帧 + 流终止判据（2026-11-18）

> 动因：eucp-ai（网关平台）需要把上游厂商协议**原样透传**给下游客户端——`event:` / `id:` / 多行 data 不能丢，「只挑 data 行」的行级读取不够用。抽出的共用面是 **wire 层**（SSE 事件级分帧 + 流终止判据）；统一模型投影层不共用（eucp-ai 用不上投影）。

### `mos4j-model`：`net.hegq.mos4j.model.wire`

- **`SseEvent`**（record：`event` / `id` / `data`）+ **`SseEventReader`**（事件级分帧，WHATWG `text/event-stream`：空行派发、`: 注释`跳过、多行 data 用 `\n` 连接、`event:`/`id:` 字段归位（id 跨事件保留）、字段值去一个前导空格、BOM 剔除）。**有意偏差**：EOF 时宽松派发未完成事件（对齐旧行级实现的即时处理；「流算不算完整」由终止规则判定，不进分帧层）。
- **`StreamTermination<T>`**（机制 M5 显式化）：`observeRaw(String)`（原始层哨兵，如 `[DONE]`/空 data 行）+ `observe(T)`（事件层证据）+ `isFinal()`（事件层终止事件已到）+ `streamIncomplete()`（流读完判定）。口径不变：**结束标记或结束原因任一到即完整，两者皆无才是截断；结束原因为空 ≠ 截断**。
- 三协议的终止判据从各自 `*ChatModel` 私有循环抽到 `protocol/*` 包的 **Rules 类**（`OpenAiStreamRules` / `AnthropicStreamRules` / `GeminiStreamRules`），三个 ChatModel 全部改走 `SseEventReader` + Rules——防「两份各自演化的 SSE 处理」漂移。**M5 行为零变化，L1 语料回归全绿**。
- **不引厂商 SDK**（openai-java / anthropic-java / google-genai）：chat 线协议稳定且实现成本已付；引 SDK 会破 I7 依赖纪律（SDK 基线仅 Jackson/RE2J/JDK）并引入 Jackson 2 vs 3 冲突。Spring AI 定位：可选适配器之一，不进内核。
- **语料规范化**：11 份 SSE 语料补事件间空行（anthropic 8 / openai 1 / gemini 2）——旧语料靠逐行处理的宽容度存活（连续 `data:` 行在事件级分帧下会合并成一个事件，JSON 解析会炸），现在是真实线格式。
- 守护：`SseEventReaderTest` 11 条（多行 data 合并 / 注释 / `id:` 跨事件保留 / BOM / CRLF / 单空格剥离 / EOF 宽松派发 / 停读 / 截断流与随机垃圾不抛异常）+ 三协议 Rules 终止单测 13 条 + L1 全量回归。
- eucp-ai 消费面（最终口径）：`transport.*` + `wire.*` + `protocol/*` 的 Config/Rules（将来按需加带 catch-all 的无损 DTO）；**不含** `ChatModel` 及统一模型投影。
- 基线：默认 reactor **930 → 954**（113 个测试类，0 失败 / 7 跳过）；全仓 `./mvnw verify` 绿。

---

## 未发布 —— `mos4j-model` 包布局整理：retry / catalog 子包（2026-11-18）

> 动因：`mos4j-model` 根包混了三类东西——纯抽象与值类型、重试家族、模型目录三件套。`wire` / `protocol` / `decorate` / `transport` 都已经是具名子包，根级 28 个类读不出边界。

### 破坏性变更（一次性，只付一次）

- **retry 家族 → `net.hegq.mos4j.model.retry`**：`RetryingChatModel`（含 `Sleeper`）/ `RetryPolicy` / `RetryPolicies` / `RetryExecutors`（后两个仍是包私有，随家族一起搬）。
- **catalog 三件套 → `net.hegq.mos4j.model.catalog`**：`ModelOption` / `ModelRegistry`（含 `Factory`）/ `SwitchableChatModel`。
- 根包回落到 **21 个类**：`ChatModel` 与抽象/值类型 + 契约异常（`ModelTimeoutException` 等**留在根级**——宿主 catch 的是契约，不是某个子包）。同步改消费方 import（`mos4j-agent` 的 `Agent` / `Config` / `ModelCatalog` / `sdk.Mos4j` 与测试）与测试所在包（`RetryingChatModelTest` → `model.retry`、`ModelRegistryTest` → `model.catalog`）。
- 依赖方向未变：`wire ← protocol/* ← 根级 ← retry/catalog`（两边都只依赖 `ChatModel` 与值类型），模块边界门与构建全绿。

### 测试

- 纯搬家、不改用例：「mos4j-model」17 类 / 140 用例（**该数字含包重组前的陈旧 surefire 报告，真实值 123**，见 2026-11-19 节）；全仓 **995 用例 / 118 类，0 失败 / 7 跳过**；`./mvnw verify` 绿。

---

## 未发布 —— 模型族扩展第 1 批：公共底座（`Artifact` / `TaskHandle`）+ kind 谱系与方言约定（2026-11-19）

> 动因：要接的模型不止 chat（百炼全谱：视觉理解 / omni / embedding / rerank / 文件解析 / TTS / ASR / 图像视频生成 / Realtime），
> 而当前统一模型层是 **chat 单族**、没有产物抽象、没有异步任务抽象。**建设初始不怕破坏**——趁公共契约未固化，先把底座与分层口径定死，chat 族随后下沉。

### 公共底座（`net.hegq.mos4j.model` 根级，跨 kind 共享）

- **`Artifact`**（sealed 接口）：二进制产物的统一表示。两种形态**用类型区分**：`InMemory`（字节在内存，构造时防御性复制；相等性按**内容**判——record 默认按数组引用判，会把同一产物说成两个东西）/ `OnDisk`（已落盘，只带位置 + 大小 + 媒体类型）。不变式 fail-closed：缺媒体类型 / 负大小 / 空位置 / 空字节一律构造即拒。
- **二进制不进 `Message.content`**（已定口径）：上下文会爆 / 审计记不下 / 可能带凭据跟着 prompt 走。模型侧只回引用，怎么用由宿主定；产物不自己写文件（写盘是宿主的权限边界）。
- **`TaskHandle` / `TaskStatus` / `TaskPoller`**：异步任务生命周期（图像 / 视频生成、文档解析这类「提交 → 轮询 → 取产物」）。`UNKNOWN` 状态 = 取值不认识，**不猜**（猜成功会拿空产物当结果，猜失败会误杀在跑的任务），且不算终态；**失败必带原因**（没原因的失败只能透传「任务失败了」）；底座只定义「刷新一次」，**不提供退避循环**——轮询节奏与超时是宿主策略（同取消语义：框架不替宿主做决定）。
- 归位口径：**跨 kind 的留根级**（`Usage` 家族 / `StatusError` / `ModelTimeoutException` / 新增四件套），**chat 专属的随 chat 族下沉**（T26）。

### 分层口径（先定死，避免按平台重复建设）

- **kind 谱系**：chat / 视觉理解 / 对话式 omni / embedding / rerank / 文件解析 / TTS / ASR / 图像生成 / 视频生成 / Realtime；生命周期五型：一次 JSON / 一次二进制 / SSE 单向 / WebSocket 双向 / 异步提交→轮询→取产物。视觉理解与对话式 omni **复用 chat**（`ContentPart.image*` + `MULTIMODAL` + `Request.modalities` + `Message.contentParts`），不另立 kind。
- **方言优先**：协议层目录为 `protocol.<方言>.<kind>`——**第一维度是方言（wire family），不是平台**。兼容性是方言级的：OpenAI 方言一份覆盖 DeepSeek / Kimi / Qwen 兼容模式 / 豆包 / 智谱 / SiliconFlow / OpenRouter / vLLM / Ollama / 百炼兼容模式；按平台分 = 同一套线格式写 10+ 份适配器。决策树：有现成方言就用它（平台差异走 Config）→ 「方言 + 私有扩展」仍用该方言 + 扩展钩子 → 真独有才开平台原生方言包。
- **平台差异三收口**：`Config`（baseUrl / apiKey / 鉴权方式 / extra headers / extra body）+ **方言扩展点（SPI）**（请求体扩展 + 响应投影钩子，**不允许方言包长出 `if (provider.equals(...))`**）+ 能力位由方言给缺省、Config 覆盖。已知不等价（usage 字段名 / `finish_reason` 取值 / 思维链字段名 / 并行工具调用 / 多模态字段名）逐平台登记偏差（范式照 `docs/contract/GO-DIFF.md`）。
- **批次计划**：T25 底座（本批）→ T26 chat 族下沉 `model.chat` → T27 协议层方言化 → T28 横切策略化 + `catalog` 按 kind 键化 → T29+ 逐 kind 落地（embedding / rerank → 文件 / TTS / ASR → 图像视频 → Realtime）。

### 测试与基线

- 新增 `ArtifactTest` 9 条 + `TaskHandleTest` 9 条（两种形态不混 / 字节防御性复制 / 内容相等 / 元信息不变式 / 失败必带原因 / 成功不带原因 / `UNKNOWN` 不算终态 / 产物列表不可变）。
- 「mos4j-model」17 类 / 123 用例 → **19 类 / 141 用例，0 失败 / 0 错误 / 0 跳过**；全仓 `./mvnw verify` 绿。
- **基线数字订正**：上一节（包布局整理）记的「17 类 / 140 用例」含包重组前的**陈旧 surefire 报告**（旧包名的 `ModelRegistryTest` 8 条 + `RetryingChatModelTest` 9 条各被算了两遍）——真实值 **123**（与 T23.6 的口径一致）。
- 环境备注：本机沙箱下 JUnit `@TempDir` 不可用（系统临时目录禁止创建），临时文件测试落在工程内 `target/`。

---

## 未发布 —— `mos4j-model` chat 族下沉 `net.hegq.mos4j.model.chat`（2026-11-19）

> 动因：**建设初始不怕破坏**——公共契约尚未固化时把 chat 家族一次整理到稳定层级，
> 免得 chat 长期留在根级成为「特例根」，后续每个 kind 族都要跟这个例外搏斗。**行为零变化。**

### 破坏性变更（一次性，只付一次）

- **14 个类型下沉到 `net.hegq.mos4j.model.chat`**：`ChatModel` / `Request` / `Response` / `Message` / `Role` / `ContentPart` / `Tool` / `ToolCall` / `Capability` / `StreamChunk` / `StreamEmitter` + chat 专属契约异常 `EmptyResponseException` / `StreamTruncatedException` / `StreamAbortedException`。
- **留在根级的是跨 kind 底座**：`Artifact` / `TaskHandle` / `TaskStatus` / `TaskPoller`（产物与异步任务）、`Usage` / `UsageDetails` / `InputTokenDetails` / `OutputTokenDetails` / `UsageJson`（token 计量，chat 与 embedding 共用，口径 M6 不变）、`StatusError` / `ModelTimeoutException`（宿主 catch 的契约异常）。
- **同步改所有消费方的 import**：`mos4j-model` 内 `protocol/*` / `decorate` / `retry` / `catalog`；`mos4j-session`（`Event` / `Projection` / `Transcript` / `Session` / `context/*`）、`mos4j-tool`（`ToolRegistry`）、`mos4j-agent`（`Agent` / `Config` / `ModelCatalog` / `sdk.Mos4j`）、`mos4j-spring-boot-starter`（`Mos4jAutoConfiguration`）。消费方里的全限定名写法（`net.hegq.mos4j.model.Request` 等）一并改写，**0 处遗留**。
- **测试随族走**：三个 L1 语料场景测试（`L1OpenAiScenariosTest` / `L1AnthropicScenariosTest` / `L1GeminiScenariosTest`）下沉到 `net.hegq.mos4j.model.chat`（它们是 chat 场景回归）；根包测试只留底座（`ArtifactTest` / `TaskHandleTest` / `UsageJsonTest` / `L1CorpusIntegrityTest` / `SseParserFuzzTest`）。
- **依赖方向**：`chat → 根级`（chat 依赖 `Usage` 等底座）；`protocol/*` → `chat` + 根级；`decorate` / `retry` / `catalog` → `chat`；**根级不依赖 chat**。

### 测试与基线

- **纯搬家、不改用例**：「mos4j-model」19 类 / **141 用例**；全仓 **972 用例**（core 20 / model 141 / runtime 99（7 跳过）/ tool 359 / session 147 / agent 206），**0 失败 / 0 错误**；`./mvnw verify` 绿（含 `BoundaryRulesTest`——包前缀级依赖方向未越界）。

---

## 未发布 —— 协议层方言化：`protocol.<方言>.<kind>`（2026-11-19）

> 动因：协议层的第一维度从「平台」换成「**方言（wire family）**」。兼容性是方言级的——OpenAI 方言一份覆盖
> DeepSeek / Kimi / Qwen 兼容模式 / 豆包 / 智谱 / SiliconFlow / OpenRouter / vLLM / Ollama / 百炼兼容模式；
> 按平台分 = 同一套线格式写 10+ 份适配器。包结构现在把「方言 × kind」矩阵立住：**后续加 kind 只加一格，不动已有格子**。

### 破坏性变更（一次性，只付一次）

- **包路径**：`protocol/openai` → `protocol/openai/chat/`、`protocol/anthropic` → `protocol/anthropic/chat/`、`protocol/gemini` → `protocol/gemini/chat/`（各 5 / 5 / 4 个类；测试包同步下沉：`OpenAiChatModelTest` / `OpenAiStreamRulesTest` / `AnthropicStreamRulesTest` / `GeminiStreamRulesTest`）。
- **全仓 import 改写**（含 `mos4j-agent` / `mos4j-session` / `mos4j-tool` / `mos4j-spring-boot-starter` 与 examples），**0 处遗留**；**行为零变化**。
- **eucp-ai 消费面路径变了**：`protocol.openai.X` → `protocol.openai.chat.X`（Config / Rules 同理）；**wire 层路径不变**（`wire.SseEventReader` / `wire.StreamTermination`）。

### 保留项（刻意的）

- **方言级共享层**（连接配置 / 鉴权 / extra body / 扩展钩子）**等第二个 kind 落地时再抽**——届时有两个实例才能定形共享面，现在抽就是猜。

### 测试与基线

- 纯搬家、不改用例：「mos4j-model」19 类 / **141 用例**；全仓 **972 用例**，**0 失败 / 0 错误**；`./mvnw verify` 绿。

---

## 未发布 —— kind 谱系的类型化身 + 目录按 kind 键化（2026-11-19）

> 动因：kind 谱系之前只是文字。目录与注册表都硬绑 `ChatModel`，宿主无法回答「这一项是什么 kind」。

### 新增

- **`net.hegq.mos4j.model.ModelKind`**（根包）：`CHAT` / `EMBEDDING` / `RERANK` / `IMAGE` / `VIDEO` / `SPEECH` / `ASR` / `FILE` / `REALTIME`——kind = 做什么，与方言（怎么说）正交；TTS / ASR / REALTIME 不塞进 chat 的 `Capability`。

### 破坏性变更

- **`catalog.ModelOption` 泛型化**：`record ModelOption<T>(ModelKind kind, String id, String label, T model)`；工厂 `ModelOption.chat(id, model)` / `ModelOption.chat(id, label, model)`；旧构造器 `ModelOption(id, label, chat)` 与访问器 `chat()` **已删除**（改用 `model()`）。消费方（`Agent` / `Config` / `sdk.Mos4j` / 测试）全部改 import 与类型。
- **agent `ModelCatalog` 按 kind 校验**：`List<ModelOption<ChatModel>>`；`kind != CHAT` 在装配期报错（`mos4j: 模型目录里的 X 不是 chat 模型（kind=Y）`）。

### 刻意不改（口径写进 API §3.21.4）

- 装饰器壳（`ChatModelDecorator` / `RetryingChatModel` / 缓存 / 日志 / 超时 / 降级）仍锁 `ChatModel`：各 kind 调用签名不同、无共同超类型可装饰，硬抽只能靠反射或 `Object`。
- `catalog.ModelRegistry` 仍产出 `ChatModel`：按 kind 键化推迟到第一个非 chat kind 落地。
- 重试**机制**（`RetryPolicy` + `RetryExecutors`）本就 kind 无关（分类器是注入的），不动。

### 测试与基线

- 新增 `ModelOptionTest`（7 条）+ agent kind 校验（1 条）：「mos4j-model」**148 用例**、`mos4j-agent` **207**；全仓 **980 用例**，**0 失败 / 0 错误**，7 跳过；`./mvnw verify` 绿。

---

## 未发布 —— `examples/model` 跟上装配面（2026-11-19）

> 动因：模型模块把「调用面之外」的东西攒齐了（retry / decorate / catalog / wire / kind 谱系 / 异步任务与产物），
> 而示例还停在「直连一个 `ChatModel`」。示例定位是**装配说明书**，装配面缺了就等于只教了一半。

### 新增

- **`ModelSurface`**（`examples/model`）：装配侧的六个真问题各一段可读代码——
  `registry()`（名字 → 工厂，未知名字 fail-closed）、`catalog(...)`（`ModelOption<ChatModel>` 带 kind / label）、
  `decorate(...)`（**Observation → Fallback → Retry → Timeout → 协议模型**，空参数 = 不装那一层）、
  `withCache(...)`（只缓存非流式成功，故**不进**默认链）、`awaitTerminal(...)`（宿主的轮询循环：刷新到终态或超时报错，
  `UNKNOWN` **不算**终态）、`requireArtifact` / `describe` / `promoteToDisk`（产物取用与**由宿主**落盘）。
- **`ScriptedTaskPoller`**：标注「本地替身，不是某个厂商的实现」；脚本用完即抛 `IllegalStateException`（不静默重复最后一步）。
- **`ModelExample.main` 收尾打印装配面**（目录 / 装饰 / 异步 / 产物四段），`assemble(...)` 改为走 `ModelRegistry`
  （未知协议 fail-closed 并列出已注册名字）。

### 测试与基线

- 新增 `ModelSurfaceTest` **13 条** + 原有 `ModelExampleTest` 6 条，`examples/model` 自 **6 → 19 条**；
  examples 侧 **32 → 45 条**（`-Pexamples`）/ **35 → 48 条**（`-Pexamples,mcp`）。
- 四套 reactor 复跑（2026-11-19）：默认 **980 / 0 / 0 / 7**、`-Pstarter` **1004**、`-Pexamples` **1025**、`-Pexamples,mcp` **1028**，均为 `0 失败 / 0 错误`。（下一节把 examples 侧推到 1046 / 1049。）
- 环境备注：本机沙箱禁写 `~/.m2`，`dependency:analyze` 本轮**未能执行**（不是无告警，是没跑成）；
  `@TempDir` 同样不可用，落盘用例写在 `target/model-surface-test/` 下并自行清理。

---

## 未发布 —— `examples/model` 变循环对话 + 配置收进 Java 常量（2026-11-19）

> 动因：纯模型示例只跑「一个问题」看不出「状态归宿主」到底长什么样；而配置散在
> `ModelExample.PROTOCOL` 与注册表的字面量里，换模型要翻代码找字符串。

### 新增

- **`ModelChatLoop`**（`examples/model`）：循环对话里**宿主自己那半边**——历史是一个列表，
  每轮 `user` + `assistant` 整段带上重发。三条口径：**失败不进历史**（抛异常就什么都不追加，
  宿主可原样重问）、**空回答不算一轮**（既无正文也无工具调用时抛错且不追加，否则下一轮
  请求会带着一条空 assistant 消息）、**历史不可变出去**（`history()` 返只读副本）。
  `reset()` 保留系统提示词（它是装配的一部分，不是对话的一部分）。
- **`ModelExample --loop`**：交互模式，`/exit`·`/quit`·EOF 退出，`/reset` 清历史，
  `/history` 报条数与轮数；**单轮失败不带走整个进程**（打印原因 + 根因，继续读下一行）。
  `runLoop(model, reader, out)` 的输入输出都是入参，用例不开真终端。
- **`ModelConfig`**（`examples/model`）：配置全部是 Java 常量——协议、三方言的
  密钥 / 基址 / 模型名、环境变量名（唯一真源）、缺省模型名、系统提示词、超时、
  循环命令词与提示符、**循环是否流式（`STREAM_IN_LOOP`）**。取值顺序：**常量 → 同名环境变量 → 协议缺省**。
  密钥常量默认留空（写进源码会跟着 git / jar / 日志传播）；`Provider.toString()` **给密钥打码**。
  `ModelSurface.registry()` 的缺省模型名改为引用 `ModelConfig.DEFAULT_*_MODEL`（去掉字面量副本）。
- **循环对话可走流式**：`STREAM_IN_LOOP = true` 时 `runLoop` 走 `ModelChatLoop.askStream`，
  增量实时打到**注入的** `PrintStream`（不是 `System.out`——否则终端看着正常、用例里一个字都没有）；
  **模型不支持流式时自动退回非流式**，并在开头打一行 `[模式]` 说清是哪一种（不静默换挡）。

### 测试与基线

- 新增 `ModelConfigTest` **8 条**（三档取值顺序 / 常量空时用环境变量 / 只剩缺省 /
  各方言读各自的环境变量名 / 空白同于未设 / 密钥不外泄 / 未知协议 fail-closed / 装配入口同源）
  + `ModelChatLoopTest` **13 条**（逐轮历史 / 副本与轮数 / reset 留提示词 / 空提示词不带 system /
  失败不改历史 / 空正文不算一轮 / 空问题直接拒 / 流式也进历史 / 命令词与 EOF /
  单轮失败不中断 / 流式增量进注入的流且不重复 / 不支持流式时退回非流式），
  `examples/model` 自 **19 → 40 条**；examples 侧 **45 → 66 条**（`-Pexamples`）/
  **48 → 69 条**（`-Pexamples,mcp`）。
- 四套 reactor 复跑（2026-11-19）：默认 **980 / 0 / 0 / 7**（116 个测试类）、`-Pstarter` **1004**、
  `-Pexamples` **1046**、`-Pexamples,mcp` **1049**，均为 `0 失败 / 0 错误`。（本节之后又收掉了 chat 示例里的异步/产物演示，见下。）
- 真实端点验证：本地起了一个假 OpenAI 端点（`HttpServer` 脚本）跑 `--loop`，
  服务端逐轮收到的消息条数为 2 → 4 → 6（system 只一条、assistant 原样带回）；
  连不上的端点走「这一轮失败 + 根因 `ConnectException`，历史未改动」路径。

---

## 未发布 —— 收掉 chat 示例里的异步任务 / 产物演示（2026-11-19）

> 动因：那两个底座（`TaskHandle` / `TaskStatus` / `TaskPoller` + `Artifact`）服务的是
> 图像 / 视频生成、文档解析这类 kind，而 **chat 是同步的「一次请求换一次响应」**，
> 没有任务号、没有轮询。在 chat 示例里演一段假状态转移 + 一张编造的 4 字节 PNG，
> 恰好违反本示例自己写的「不假装它们能跑」。

### 删除

- **`ModelSurface` 去掉 `awaitTerminal` / `requireArtifact` / `describe` / `promoteToDisk`**，
  类注释从「四件事」收为「两件事」（装配 + 横切能力），并说明为什么只有两件。
- **删掉 `ScriptedTaskPoller`**（它只服务那段演示）；
- **`ModelExample.printAssembly` 去掉 `[异步]` / `[产物]` 两段**（含编造的 `fakePng` 字节）；
- **删掉 `ModelSurfaceTest` 的 5 条异步/产物用例**——那些语义由 mos4j-model 自己的
  `TaskHandleTest`（9 条）/ `ArtifactTest`（9 条）守，在示例里再抄一遍只是重叠覆盖。

### 测试与基线

- `ModelSurfaceTest` **13 → 8 条**，`examples/model` **40 → 35 条**；examples 侧
  **66 → 61 条**（`-Pexamples`）/ **69 → 64 条**（`-Pexamples,mcp`）。
- 四套 reactor 复跑（2026-11-19）：默认 **980 / 0 / 0 / 7**、`-Pstarter` **1004**、
  `-Pexamples` **1041**、`-Pexamples,mcp` **1044**，均为 `0 失败 / 0 错误`。
- 主程序实跑确认输出只剩 `[能力]`、`[非流式]`、`[流式]`、`[多轮]`、`[目录]`、`[装饰]`、`[循环]`。

### 留给以后的

异步任务与产物的真场景示例（提交 → 轮询 → 取产物）随第一个非 chat kind 落地时单独立模块；
届时复用同一套口径（刷新一次归框架、节奏与超时归宿主、产物不自己写文件）。
