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
- 评审表 `docs/contract/REVIEW-SHEET.md` 的逐行人工签字是发布前的最后一道门（115 条判定项，**111 条待签**）。
- 相对 Go 参考实现的 **16 项能力缺口已补齐**（2026-11-09 第四轮）；但**机器测试 ≠ 跨语言回放**——回放比对尚未做，登记为 B25（需人做）。
- **Gemini `responseFormat` 未实现**（协议支持 `responseMimeType` / `responseSchema`，Java 仅传 temperature / maxTokens）：需要真实 Gemini 接口环境验证，已确认缺口并暂缓。
- **`web_fetch` 未对真实公网主机做端到端验收**：传输层已用本地真 socket 端到端跑通（chunked / gzip / 上限 / 重定向），但真实站点的 TLS 链接、反爬拦截、重定向到 CDN 等场景仍属未知。

### 机械门基线

- 默认 reactor **912 通过 / 0 失败 / 0 错误 / 7 跳过**（六模块，109 个测试类）；`-Pstarter` **936 / 7**；`-Pexamples` **937 / 7**（示例自身 25 条）；`-Pexamples,mcp` **940 / 7**（示例自身 28 条）。
- 6 个 SDK 模块 + starter + examples 8 模块 `dependency:analyze` 无告警；C6（Spring / `java.util.regex`）均 0；裸 NUL 字节 0。

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

### 测试

- `PinnedConnectorTransportTest`（**22 条，真 socket**）：出站报文与 Host / chunked + trailer + 非法长度 / gzip / 头名大小写 / 字节与码点双上限 / 404 / 三类非文本与缺 Content-Type / 同源与跨源重定向 / 跳数上限 / 注入解析器仍拦私网 / 非 HTTP 响应。
- `PinnedConnectorTest` 8 / `HtmlTextTest` 9 / `WebToolsTest` 22（search 9 + fetch 13）。
- `mos4j-tool` **315 → 358**；四套基线 **869/893/894/897 → 912/936/937/940**。
