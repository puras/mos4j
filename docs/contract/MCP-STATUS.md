# MCP 接入 —— 状态与口径（`mos4j-tool` 的 `net.hegq.mos4j.mcp` 包）

> **状态：M0 / M1 / M2 / M3 批次已完成**（契约层 + 官方 SDK 客户端 + 真 stdio 子进程 E2E + 多服务器管理器 + 三档曝光落注册表 + 服务器说明每会话注入 + **认证** + **资源工具三个** + **policy / guard 联动** + **`mcp_servers` 分节** + **Describe 快照** + **`Agent.close()`**）；**有意不做**：Starter 接入（Starter 不引官方 SDK，会把 SLF4J / Reactor 带进 Starter 基线）。

## 0. 为什么立项

Go 参考实现在 **2026-11-04 / 11-06** 落地了完整的 MCP 接入（`tool/mcp/`，12 个文件约 3600 行，
S8.2 / S8.3 / S8.4），能力面包括：官方 SDK 客户端、stdio 与 streamable-http 两种传输、
工具适配与 schema 直通、`mcp__<server>__<tool>` 命名、**direct / deferred / hidden 三档曝光**、
多服务器编排（并发连接 / 两阶段换代 / 失败隔离 / scope 隔离）、服务器 instructions 每会话注入、
认证（`TokenProvider` + 401/403 分类）、**MCP 资源工具三个**、结果投影、policy/guard 拦截 E2E、
`Describe` 快照、`Agent.Close()`。

MoS4J 在立项时（2026-11-03 路线 A 拍板）把 MCP 划进「M3+ 单独立项」范围外，
理由是「移植期间不做范围外的功能」——**这个决定在当时是对的**：Go 侧的 MCP 是移植开始**之后**
才做完的。但它留下的后果是：MoS4J 相对参考实现存在一块**能力缺口**（不是「有意差异」）。
2026-11-07 用户拍板：**立，官方 SDK**。
**2026-11-08 更新**：MCP 已从原按需模块 `mos4j-mcp` **并入 `mos4j-tool`**（六模块合并），
包路径仍是 `net.hegq.mos4j.mcp`；官方 SDK 在 `mos4j-tool` 里是 **optional 依赖**，
宿主要用 MCP 须自己显式声明官方 SDK 坐标（不传递给下游宿主）。
根工程的 `-Pmcp` profile 已取消。

## 1. 模块与依赖口径

| 项 | 口径 |
|---|---|
| 模块 | `mos4j-tool` 的 `net.hegq.mos4j.mcp` 包（**在默认 reactor 里** → `./mvnw verify` 就带它；官方 SDK 是 optional 依赖，宿主须自己显式声明官方 SDK 坐标） |
| 协议层 | **不手写**：官方 MCP Java SDK `io.modelcontextprotocol.sdk` **2.1.0**（当前最新稳定版；2.2.0-M2 是里程碑版，不进默认路径） |
| 为什么官方 SDK | Go 侧同款决策：2026-07-28 规范废弃了 initialize/roots/sampling，手写协议层要同时正确跑两代初始化流程 |
| 类型不外泄 | 对外契约是 MoS4J 自己的 `McpClient` / `McpServerConfig` / `McpToolInfo`；SDK 类型只在 `net.hegq.mos4j.mcp.internal` 里出现 |
| JSON | 官方 SDK 的 Jackson 3 实现（`mcp-json-jackson3`）→ 与本仓库 Jackson 3 基线一致（databind 3.1.4 被父 POM 钉到 **3.2.4**） |

### 1.1 依赖纪律破例（I7 条款修订，必须如实登记）

官方 SDK 的传递依赖**超出**了 SDK 六模块的基线（Jackson 家族 / RE2J / JDK / SnakeYAML 系）：

| 新增依赖 | 来源 | 处置 |
|---|---|---|
| `org.slf4j:slf4j-api` 2.0.16 | `mcp-core`（jar 内 **199 处**引用） | **只在本模块放行**：排除会直接 `NoClassDefFoundError`（不是「少个日志门面」的事）。只含 API、不含实现 → 没有绑定时是 NOP logger，**不产生任何日志输出** |
| `io.projectreactor:reactor-core` 3.7.0 | `mcp-core` | 只在本模块出现；SDK 的响应式内核，宿主侧用 `McpClient.sync(...)` 同步门面 |
| `com.networknt:json-schema-validator` 3.0.6 | `mcp-json-jackson3` | 只在本模块出现 |

**其余禁令（Spring / Guava / ORM / Lombok / Logback）照旧**——用 `combine.self="override"` 把门改窄，
不是把整条门关掉（见 `mos4j-tool/pom.xml`）。SDK 六模块的依赖基线**没有变化**。

## 2. 已落地（M0 批次）

| 落点 | 内容 | 守护测试 |
|---|---|---|
| `McpTransport` | 传输方式枚举（STDIO / STREAMABLE_HTTP） | `McpServerConfigTest` |
| `McpExposure` | 三档曝光（DIRECT / DEFERRED / HIDDEN）+ `visibleToModel` / `searchable` | `McpServerConfigTest` |
| `McpServerConfig` | 服务器配置（Name/Transport/Command/Args/Env/Dir/URL/Headers/Exposure/ToolExposure/Disabled）；**构造期校验**（stdio 没命令、http 没 url → 立刻报错）；逐工具曝光解析（精确名 > `"*"` > 服务器默认） | `McpServerConfigTest`（7 条） |
| `McpToolInfo` / `McpContent` / `McpCallResult` | 上游工具描述、内容块、调用结果；**结果投影**：文本按序拼接，非文本给占位诊断 + 截断原始 JSON，结构化内容原样留在结构化字段 | `McpToolAdapterTest`（6 条） |
| `McpError` | 错误分层（TRANSPORT / PROTOCOL） | 见 §4 的差异登记 |
| `McpToolNaming` | 公开名 `mcp__<server>__<tool>`：非 `[A-Za-z0-9_]` → `_`（按 UTF-8 **字节**），超 64 字节截断 + 8 位 SHA-256 后缀 | `McpToolNamingTest`（6 条） |
| `McpToolAdapter` | 把上游工具包装成 `net.hegq.mos4j.tool.Tool`：schema **原样透传**、公开名带命名空间、上游 `isError` → 工具失败（错误文本留给模型自纠） | `McpToolAdapterTest` |
| `internal/SdkMcpClient` | 官方 SDK 客户端封装：stdio / streamable-http 两种传输、**客户端能力显式置空**、协议版本交给 SDK 协商、错误按传输 / 协议分层 | `SdkMcpClientE2ETest`（4 条） |
| `McpManager` | 多服务器编排：**两阶段换代**（先并发取全量，再整体写进注册表）、**失败隔离**、**scope 隔离**（`mcp:<server>`）、三档曝光落注册表、hidden 可见性层、快照诊断、单台重连 | `McpManagerTest`（10 条） |
| `McpKit` | 门面：连服务器 → 工具进注册表 → 说明提供器（喂给 `Config.mcpInstructions(...)`） | `McpKitTest`（4 条） |
| `McpSnapshot` / `McpInstruction` / `McpClientFactory` | 快照（每台服务器的状态与各档工具数）、一条服务器说明、客户端工厂（可换实现） | `McpManagerTest` |

**与既有模块的接线**（本批次新增）：

| 落点 | 内容 | 守护测试 |
|---|---|---|
| `ToolRegistry.setVisibilitySlot(slot, filter)` | **具名可见性槽**：同名槽整层替换，多槽 **AND** 叠加，传 {@code null} 移除；过滤器必须纯判定 | `ToolRegistryScopeTest`（15 条，含 MCP 侧用法） |
| `ToolRegistry.replace(scope, tools, deferred)` | **按 scope 原子换代**：先校验整批（重名 / deferred 含名单外工具 / schema 生成失败）再落盘；失败时注册表保持原样（上一代还在）；同 scope 旧工具消失；跨 scope 互不影响 | 同上 |
| `DeferredToolCatalog.unregister/deactivate/setVisibility` | 换代时清旧登记与激活态；检索与注册表用**同一份**可见性判定（看不见的工具不该被搜出来） | 同上 |
| `Session.claim(key)` | 会话级一次性标记（每会话只注入一次） | `SessionClaimTest`（4 条） |
| `Config.mcpInstructions(Supplier<String>)` + `Agent` 注入 | 服务器说明**每会话一次**、走 **user 侧**（写一条 user 事件，不进静态系统提示词——保 prompt cache） | `McpInstructionsFlowTest`（5 条） |

**真 stdio 子进程 E2E**（`SdkMcpClientE2ETest` + `EchoMcpServer`）：起一个**真的** MCP 服务器子进程，
连上去 → `tools/list` → `tools/call` → 取 instructions → 协商协议版本 → 经适配器调通。
这类用例不能用替身替代：协议层一旦对不上，替身全绿而真实服务器连不上。

**当前基线**（2026-11-08 六模块合并后实跑）：MCP 相关测试 **71 条全绿**（13 个测试类，
分布在 `mos4j-tool` 与 `mos4j-agent`）；6 个 SDK 模块 `dependency:analyze` 全 `No dependency problems found`；
四套 reactor：默认 **846 / 0 / 0 / 7**、含 starter **870 / 0 / 0 / 7**、`-Pexamples` **871 / 0 / 0 / 7**、`-Pexamples,mcp` **874 / 0 / 0 / 7**。

## 2.1 M3 批次（已落地）

| 落点 | 内容 | 守护测试 |
|---|---|---|
| `McpTokenProvider` | **凭据来源**：每次请求取一次、**不缓存**；空串 = 不注入 `Authorization`；抛错 = 认证类错误；只对 streamable-http 生效（stdio 是本地子进程） | `McpAuthTest`（5 条，JDK `HttpServer` 真发 HTTP） |
| `McpError.Kind.AUTH` | **认证层**独立成类：401 / 403 / 取凭据失败 → 认证类（不是传输、不是协议） | 同上 |
| `SdkMcpClient` 凭据注入 | 官方 SDK 的 `authorizationErrorHandler` 把 401/403 转成认证类；请求定制器每次请求取一次 token | 同上 |
| `McpClient.listResources / listResourceTemplates / readResource` | 资源契约（官方 SDK 类型不外泄） | `McpResourceToolsTest` |
| `McpResourceTools` | **三个共享工具**：`mcp_resource_list` / `mcp_resource_templates` / `mcp_resource_read`；工具面**恒为 3**（不随服务器数增长）；任一可见服务器有资源才注册；hidden 服务器的资源不泄露；二进制只给长度与类型（模型读不了 base64） | `McpResourceToolsTest`（8 条） |
| policy / guard 联动 | 被策略拒的 MCP 工具**不进模型清单**，点名调用**被守卫拦下**（可见性槽与运行时守卫读同一份名单） | `McpPolicyIntegrationTest`（3 条） |
| `PromptSections` | 新增 **`mcp_servers`** 分节（中心顺序：技能清单 → **MCP 服务器摘要** → 工具指引） | `PromptSectionsTest` |
| `DescribeContributor` | **工具层往装配层的槽里写装配事实**；该 SPI 下沉到 `net.hegq.mos4j.core`（零依赖），避免 tool → agent 与 agent → tool 的 Maven 环 | `McpKitWiringTest`（4 条） |
| `McpKit.serversSection() / describeContributor()` | 分节文本与快照补充者 | 同上 |
| `Config.closer(...)` + `Agent.close()` | **关闭责任**：宿主装进来的外部资源（MCP 连接、stdio 子进程）由智能体一并关掉；幂等；一个关不掉不阻止关别的 | `AgentCloseTest`（4 条） |

**冻结清单口径（本轮已定）**：MCP 工具名是**动态**的（`mcp__<server>__<tool>`），**不进**字段名冻结清单与
`ToolContractTest` 的 14 个内置工具清单；**MCP 资源工具三个也不进**（条件注册工具，
有资源的服务器才装）——由 `mcpToolsAreNotInTheFrozenList` / `mcpResourceToolsAreNotInTheFrozenList` 两条守护测试守住。

## 3. 还没做（明确登记，不假装完成）

| 项 | 内容 | 状态 |
|---|---|---|
| **M3** | Starter 接入 | **有意不做**：Starter 引官方 SDK = 把 SLF4J / Reactor / json-schema-validator 带进 Starter 基线 |
| — | 装饰器 `Cache` | 未做（用户口径：可缓） |

## 2.1 本轮补齐的三项（2026-11-07）

| 项 | 做了什么 | 守护 |
|---|---|---|
| **结果投影口径收紧** | 非文本内容的占位诊断**按类型分开**（image / audio / resource / resource_link / 未知），原文案是一句笼统的「只把文本入模」；原始 JSON 上限从 512 改成 **2048**（对齐 Go 的 `maxOpaqueJSON`）；新增 `McpCallResult.opaqueParts()`（结构化占位）与 `textContent()`（纯文本）；参数不是 JSON 对象、上游没给结果 → **fail-closed**（协议层） | `McpResultProjectionTest`（9 条）+ `McpToolAdapterTest.nonTextContentBecomesAPlaceholder`（口径已更新） |
| **`tool_search` 激活后同轮再声明** | 核实：Java 的 `ReActDriver` **每轮决策前重取工具清单**（与 Go `agent/driver/react/react.go` 同口径），所以「激活 → 下一次请求就声明」本来就成立；**缺的只是 MCP 层的证明** → 新增 MCP 用例：DEFERRED 档的 MCP 工具可被 `tool_search` 搜到、激活后**同一次 run 的下一次请求**就进清单、真能调上游 | `McpDeferredVisibilityTest`（2 条）+ 既有 `DeferredFlowTest.activationTakesEffectNextRound` |
| **streamable-http 真服务器 E2E** | 官方 SDK 的服务端传输只有 stdio 与 HttpServlet 两族 → 引入**测试作用域**的嵌入式 Jetty 12.0.36（`jetty-ee10-servlet`），用官方 SDK 的 servlet 传输起一台**真的** MCP 服务器，走完 initialize / 会话头 / tools/list / tools/call | `McpHttpServerE2ETest`（3 条） |

> **依赖纪律**：Jetty **只在 test scope**（`mos4j-tool`），不进运行时依赖；SDK 六模块的基线不变。
> 注意 Jetty 版本要**整族一致**：`jetty-ee10-servlet` 12.1.x 的传递依赖是 12.0.x 的 core，混用会
> `NoSuchMethodError`（已踩过，见 `Environment.ensure`）。

**冻结清单口径**：MCP 工具名是**动态**的（`mcp__<server>__<tool>`），**不进**字段名冻结清单与
`ToolContractTest` 的 14 个内置工具清单；M1 起要新增一条守护：「MCP 工具不进冻结清单」。

## 4. 与 Go 的有意差异

| 编号 | 差异 | 原因 |
|---|---|---|
| D-MCP-1 | **stdio 无工作目录**：Go 的 `ServerConfig.Dir` 在 Java 侧不可配置 | 官方 SDK 未暴露 `ProcessBuilder.directory`（`getProcessBuilder()` 是 protected）；要支持得改用自定义传输 |
| D-MCP-2 | 公开名截断**保证是合法 UTF-8**（切到半个字符时收尾） | Go 按字节切可能产出非法 UTF-8 的工具名；名字只用于标识，合法性比逐字节一致更重要 |
| D-MCP-3 | 错误分层：连接 / 初始化失败 = TRANSPORT；**调用期异常（含超时）暂归 PROTOCOL** | 官方 SDK 的异常类型不足以稳定区分超时与协议错误；等 SDK 给出可靠信号再细分（现在归错类的代价是「说服务器不可用」时其实服务器在，只是这次调用超时） |
| D-MCP-4 | **跨 scope 同名工具直接报错**（Go 的 `Registry.Replace` 会让新名单覆盖别的 scope 的同名工具） | 同名工具归两个来源时“谁生效”说不清；Java 侧宁可报错也不覆盖（覆盖是静默的行为漂移） |
| D-MCP-5 | 会话级一次性标记**只在内存**（会话从日志恢复后会再注入一次说明） | 与 Go 的 `Session.Claim` 同构（Go 也不落盘）；把标记写进日志就要新增事件类型，而事件字段清单是冻结的 |

## 5. 诚实边界

1. **MCP 已完成 M0–M3**：契约层 + 客户端 + 命名 + 适配 + 真 stdio E2E + 多服务器编排
   （两阶段换代 / 失败隔离 / scope 隔离）+ 三档曝光落注册表 + 说明每会话注入 +
   **认证** + **资源工具三个** + **policy / guard 联动** + **`mcp_servers` 分节** +
   **Describe 快照** + **`Agent.close()`** + **结果投影口径收紧** +
   **deferred 工具同轮再声明（MCP 层已证明）** + **streamable-http 真服务器 E2E**。
   **还没做**：装饰器 `Cache`（用户口径：可缓）；**Starter 接入有意不做**（会污染 Starter 基线）。
   所以对外说「MoS4J 支持 MCP 客户端与编排」成立；说「所有 MCP 场景都已验收」不成立。
2. **依赖纪律破例已发生**（slf4j-api / reactor-core / json-schema-validator），只限 `mos4j-tool` 一个模块；
   SDK 六模块的基线没变，但 **I7 条款本身需要修订**（见 `REVIEW-SHEET.md` 的 B17）。
3. **stdio 的工作目录不可配置**（D-MCP-1）：依赖宿主当前目录，与 Go 行为不同。
4. **超时分类暂不精确**（D-MCP-3）。
5. 官方 SDK 升版可能带来行为变化（2.1.0 → 2.2.0 的里程碑版已在仓库外）；版本钉在父 POM 的
   `mcp.sdk.version`，升版是一次显式决定。
