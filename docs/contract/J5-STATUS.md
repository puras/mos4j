# J5 推进状态（工具全集与联网）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 更新：2026-11-05。J5 = grep / glob / ls / current_time / grant_access + tool_search（BM25 + deferred）+ web_search / web_fetch + Guideline（任务列表 §7）。
> 配套：[任务列表](../mos4j-tasks.md)、[推进方案](../mos4j-execution.md)、[契约目录](.)、[J4 状态](J4-STATUS.md)。

## 一句话状态

**T5.1–T5.10 全部完成并有测试；G5 三项机械验收全绿。**
grep / glob / ls / current_time / grant_access 五个内置工具可用；
工具面延后加载（`tool_search` + `activate`）已接进 `ReActDriver`——
每轮决策前重组装工具清单，激活**下一轮生效**，历史工具名自动并入兑底；
`web_search` / `web_fetch` 可用（SSRF 防线齐），`Guideline` 聚合渲染可拼进系统提示词。

## 产出文件

| 模块 | 文件 | 说明 |
|---|---|---|
| mos4j-tool | `builtin/GrepTool.java` | RE2J 正则搜索；非法正则报错不静默当字面量；二进制跳过；limit 默认 100 / 上限 1000；单文件 1MB / 2 万文件上限 |
| mos4j-tool | `builtin/GlobPattern.java` | glob → RE2J 正则（`*`/`**`/`?`/`[...]`/`{a,b}`；`**/` 目录可选；括号不配对报错） |
| mos4j-tool | `builtin/GlobTool.java` | 模式不含 `/` 按文件名、含 `/` 按相对路径；越界由 jail 拦 |
| mos4j-tool | `builtin/LsTool.java` | 含隐藏项、按名排序、`name\ttype\tsize`；不递归；上限 5000 |
| mos4j-tool | `builtin/CurrentTimeTool.java` | 时钟注入（测试可拨）；不接受参数 |
| mos4j-tool | `builtin/GrantAccessTool.java` | 模型只有「申请」能力；批准才 `roots.add`；回调异常 = fail-closed 拒绝；批准不缓存 |
| mos4j-tool | `searchtools/Bm25Index.java` | BM25 k1=1.2 b=0.75；camelCase 分词 + 词干 + 停用词 + **CJK bigram** |
| mos4j-tool | `searchtools/DeferredToolCatalog.java` | 登记名字+说明；activate 改激活集；未知名忽略；已激活幂等 |
| mos4j-tool | `searchtools/ToolSearchTool.java` / `ActivateTool.java` | 模型侧两个入口 |
| mos4j-tool | `web/PublicAddresses.java` | SSRF 防线一：公网判定（回环/内网/链路本地/CGNAT/ULA/IPv4 映射伪装全拒） |
| mos4j-tool | `web/PinnedConnector.java` | SSRF 防线二：地址钉住（socket 直绑校验过的地址）；非文本 Content-Type 响亮失败 |
| mos4j-tool | `web/WebFetchTool.java` | 防线三：只跟同源重定向（跨源拒绝、逐跳重校验）；防线四：512KB 上限 + 20s 超时 + 只解 UTF-8 |
| mos4j-tool | `web/SearchProvider.java` / `WebSearchTool.java` | 搜索 provider 接口；`UNTRUSTED_NOTE` 冻结文案；非 2xx 是结果不是错误 |
| mos4j-tool | `tool/Guideline.java` / `Guidelines.java` | 跨调用指引进系统提示词；聚合渲染逐字节稳定 |
| mos4j-tool | `tool/ToolRegistry.java` | `registerDeferred`（deferred 非空自动注册 tool_search/activate）；`list()` 只出非 deferred + 已激活；`admitHistoricalTools` 兑底 |
| mos4j-driver | `driver/ReActDriver.java` | 每轮决策前重组装工具清单（激活下一轮生效）；执行前历史工具名并入 |

## 任务完成情况

| 任务 | 状态 | 证据（真实测试） |
|---|---|---|
| T5.1 grep | ✅ | `BuiltinSearchToolsTest`（grep 5 条：命中格式 / 非法正则报错 / 二进制跳过 / limit 截断 / RE2 语法） |
| T5.2 glob | ✅ | 同上（glob 5 条：文件名匹配 / 相对路径匹配 / 字符类与花括号 / 非法模式报错 / jail 越界） |
| T5.3 ls | ✅ | 同上（ls 2 条：隐藏项+排序+格式 / 非目录报错） |
| T5.4 current_time | ✅ | 同上（注入时钟拨时间 / 拒绝参数） |
| T5.5 grant_access | ✅ | `GrantAccessToolTest`（6 条：批准加根 / 拒绝含「不许绕」/ 回调异常 fail-closed / 挂载名单段 / 目录缺失 / 批准不缓存） |
| T5.6 tool_search | ✅ | `DeferredToolsTest`（BM25 3 条：排序 / CJK bigram / 分词+词干+停用词） |
| T5.7 Registry deferred | ✅ | `DeferredToolsTest`（5 条：登记不进清单+自动注册辅助工具 / activate 未知名 / 历史并入 / 重名 fail-closed / 空集不注册）+ `DeferredFlowTest`（2 条：下一轮生效 / 历史并入执行） |
| T5.8 web_search | ✅ | `WebToolsTest`（3 条：归一化+不可信标注 / 异常是结果 / 参数校验） |
| T5.9 web_fetch | ✅ | 同上（7 条：回环拒 / 内网字面量拒 / IPv4 映射伪装拒 / 链路本地拒 / 协议与主机 / 公网判定边界 13 个地址） |
| T5.10 Guideline | ✅ | 同上（聚合渲染稳定 / 空规范空串） |

## 场景覆盖（真实计数）

| 层 | 绿 | 未做 | 说明 |
|---|---|---|---|
| L3 D4（工具全集与联网） | **8/8**（L3-22 … L3-29） | — | 新增段，见 `L3-SCENARIOS.md` |
| G5 机械验收 | **3/3** | — | 十工具行为一致 / RE2J 无 `java.util.regex` / SSRF 绕过测试（防线一） |

## 验证记录（真实输出）

```
$ mvn -Dmaven.repo.local=.m2repo -B clean verify
mos4j-core     Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
mos4j-llm      Tests run: 35, Failures: 0, Errors: 0, Skipped: 14
mos4j-fs       Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
mos4j-sandbox  Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
mos4j-tool     Tests run: 77, Failures: 0, Errors: 0, Skipped: 0   ← 新增 37 条
mos4j-session  Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
mos4j-hook     Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
mos4j-execpath Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
mos4j-context  Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
mos4j-driver   Tests run: 27, Failures: 0, Errors: 0, Skipped: 0   ← 新增 2 条
mos4j-policy   Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

合计 **379 个用例通过 / 15 个跳过**（共 394 个跑过；J4 收口时是 340/15）。
跳过的 15 条不变 = Anthropic + Gemini 的 L1 协议场景 14 条（适配器在 J6）+ Seatbelt 强制效果 1 条。

Maven Wrapper 复跑：计数完全一致，13 个 reactor 项全部 `SUCCESS`。

依赖门与源码纪律（CI 同款命令）：

```
$ mvn -Dmaven.repo.local=.m2repo -B -am -pl mos4j-core,mos4j-llm,mos4j-fs,mos4j-sandbox,mos4j-tool,mos4j-session,mos4j-hook,mos4j-execpath,mos4j-context,mos4j-driver,mos4j-policy dependency:analyze -Dmdep.analyze.failOnWarning=true
11 个已实现模块全部 No dependency problems found
BUILD SUCCESS

$ grep -rn "import java.util.regex" --include="*.java" mos4j-*/src/main   → OK: 无（G5 门）
$ grep -rn "import org.springframework" --include="*.java" mos4j-*/src/main → OK: 无 spring
```

`mos4j-tool` 新增运行时依赖 `com.google.re2j:re2j`（已在父 POM 版本管理内，I7 依赖纪律允许）。

## J5 期间改掉的「会静默出事」的问题

| 问题 | 后果 | 修复位置 |
|---|---|---|
| 非法正则当字面量搜 | 模型以为「文件里没有」，把错误埋进推理 | `GrepTool`：`PatternSyntaxException` → 报错文案含「正则表达式非法」（fail-closed） |
| glob 手写递归匹配器 | 模式来自模型，恶意模式拖入指数回溯 | `GlobPattern` 用 RE2J 编译（线性时间引擎） |
| grep/glob 遍历不做上限 | 大目录树把一轮对话拖死 | 两处都有 2 万文件上限，超限报错说明怎么缩小范围 |
| `activate` 当轮生效 | 模型看到的清单与决策时清单不一致 → 调「不存在」的工具 | `ToolRegistry.list()` 只出激活后的工具；`ReActDriver` 每轮决策前重组装（激活下一轮生效） |
| 会话恢复后 deferred 激活集为空 | 历史调用对着「本轮清单里没有」的工具，模型困惑 | `ReActDriver` 执行前 `admitHistoricalTools` 把历史工具名并入激活集 |
| SSRF 只校验 URL 文本 | `localhost.example.com` 文本无害、解析是 127.0.0.1 | `WebFetchTool` 先 DNS 解析再逐地址校验（校验解析结果） |
| 校验与连接之间 DNS 换地址（rebinding） | 校验白做，连接打到内网 | `PinnedConnector` socket 直绑校验过的地址（`Proxy(DIRECT, InetSocketAddress(resolved))`），无第二次解析 |
| 重定向绕过校验 | 每一跳都是新地址，自动跟随等于没校验 | 关闭自动重定向；同源判定 + 跨源拒绝 + 重定向目标再校验 |
| 搜索服务故障当工具错误 | 整轮 turn 以 error 收尾，模型失去换词重试的机会 | `WebSearchTool` 捕获 provider 异常 → 「搜索失败」回执（是结果不是错误） |
| 外部内容当可信输入 | 搜索摘要 / 网页正文可含提示注入 | `UNTRUSTED_NOTE` 冻结文案打头（web_search 与 web_fetch 共用） |
| 非文本响应用硬解 | 二进制解码给模型 = 乱码 + 幻觉 | `PinnedConnector` 按 Content-Type 判定，非文本响亮失败 |
| grant_access 自己判「能不能加」 | 模型自己给自己发钥匙 | `AccessApprover` 宿主回调；本工具只转发申请；回调异常 = 拒绝（fail-closed） |

## 未完成 / 待决策

| 项 | 说明 | 影响 |
|---|---|---|
| G5 人工语义评审 | 机械项全绿；**语义正确性**（glob 语义、deferred 交互轮次、SSRF 防线口径）需维护者确认 | G5 门未过 |
| SSRF 防线二/四的真机验证 | 地址钉住与体积超时是传输层实现；判定逻辑有测试，真机 rebinding 对拍不可行（当前环境无法自建 DNS 场景） | 诚实边界已登记（`L3-SCENARIOS.md` D4 + `SELF-INVENTED.md` SI-22） |
| 工具名与 Go 侧逐字核对 | ✅ 已核对（2026-11-06）：Go = `read`/`write`/`edit`，Java 当时 = `read_file`/`write_file`/`edit_file`；**另有四处参数形状差异**（`edit`/`grant_access`/`grep`/`shell`） | **已裁决并落地（B13-1/2/3，2026-11-06）**：工具名改为 `read`/`write`/`edit`；`edit` 改 `edits[]`；`grep` 补 `glob`；`glob`/`ls` 补 `limit`；`shell` 补 `description`；`tool_search` 参数名改 `limit`。`grant_access` 形状与 deferred 两步 = 有意差异（B13-4/5）。Java 侧冻结口径见 `API.md` §3.4，差异登记见 `INVARIANT-TESTS.md` §3 与 `SELF-INVENTED.md` SI-26 |
| `Guideline` 与系统提示词的自动接线 | 聚合渲染是独立 API；宿主自行把 `Guidelines.render(...)` 拼进 `tools` 段 | 与 J4 的 `PromptSections` 同一处置 |
| `web_search` 的默认 provider | SDK 不绑服务商（依赖纪律）；宿主实现 `SearchProvider` | 没有 provider 就无法搜索（构造期即要求） |
| web_fetch 的真机端到端测试 | 当前环境对公网的出网可用性未验证 | 单元测试用本地构造场景；接入真实网络后建议补一条冒烟用例 |
| T6 系列任务 | Anthropic/Gemini 适配、取消贯通、重试、并发硬化等 | 属 J6 |
