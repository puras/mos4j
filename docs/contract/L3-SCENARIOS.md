# L3 决策场景清单（J0 · T0.7，Java 自建）

> **口径**：场景对齐 MoS 踩坑记录，期望值 Java 自算；拒绝文案断言以 `API.md` §4 文案表为准。
> **落点**：`mos4j-agent` / `mos4j-tool` / `mos4j-runtime` 测试；J2/J3 点亮，清单 J0 冻结。

## D1 编排决策（execpath，J2 点亮）

| # | 场景 | 断言 | 现状（守护测试） |
|---|---|---|---|
| L3-1 | 守卫拒绝 | `isError=true`，文案 `工具调用被安全策略拒绝: {reason}` | ✅ `ExecPathSecurityTest.l3_1_guardDeniesWithFixedWording` |
| L3-2 | 钩子拒绝 | 文案 `工具调用被扩展点拒绝: {reason}` | ✅ `ExecPathSecurityTest.l3_2_hookRejectsWithFixedWording` |
| L3-3 | **载荷冻结违约**（M1） | 钩子改写 name / args / CallID 各一条 → `isError=true`，文案含「载荷冻结」 | ✅ `ExecPathSecurityTest.l3_3_*`（4 条） |
| L3-4 | ask 且无审批通道（I1） | 文案含「未装配审批通道（fail-closed）」 | ✅ `ExecPathSecurityTest.l3_4_askWithoutChannelIsDenied` + `SecurityFlowTest.missingApprovalChannelFailsClosed` |
| L3-5 | 审批人工拒绝 | 文案**逐字断言**含「不要尝试用其他命令或方式达成同一目的」（不许绕，M10） | ✅ `ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence` |
| L3-6 | 审批无人应答 | 文案 `人工确认无人应答（fail-closed）: {reason}` | ✅ `ExecPathSecurityTest.l3_6_noAnswerIsFailClosed` + `throwingApprovalChannelBecomesUnavailableAndStillWritesTheDecision` |
| L3-7 | 回填序=调用序（M2） | 并发组内人为反序完成 → 输出顺序==调用顺序 | ✅ `ExecPathTest.resultsFollowCallOrderNotCompletionOrder`（J1 承接） |
| L3-8 | 错误只可加重（M3） | post 钩子试图洗白失败 → `isError` 仍 true；可加重：成功+err → isError=true | ✅ `ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` / `l3_8_postHookCanWorsenASuccess` |
| L3-9 | 双轨制结构（I5） | 钩子链无法在守卫之前注入逻辑（编译期结构 + 行为测试）；守卫对同一调用只触发一次（副作用计数） | ✅ `DualTrackStructureTest`（3 条）+ `ExecPathSecurityTest.guardRunsBeforeHooksAndDeniedCallNeverReachesHooks` / `l3_9_guardRunsExactlyOncePerCall` |
| L3-10 | 审批事件全量落日志 | asked / decided 各一条事件，`outcome` 四值（approved/rejected/cancelled/unavailable） | ✅ `SessionApprovalAuditTest`（5 条）+ `SecurityFlowTest.approvalEventsAreWrittenToTheLog` |

## D2 文件与沙箱（J3 点亮）

| # | 场景 | 断言 | 现状（守护测试） |
|---|---|---|---|
| L3-11 | 路径越界（`../` 出根） | 拒绝；Lexical 层拦截 | ✅ `SandboxedFsTest.l3_11_dotDotOutOfRootIsDenied` / `l3_11_deepDotDotOutOfRootIsDenied` / `absolutePathIsDenied` |
| L3-12 | symlink 目标越界 | 拒绝；Realpath 层拦截（读也校验，M9） | ✅ `SandboxedFsTest.l3_12_symlinkTargetOutsideRootIsDenied` / `l3_12_relativeSymlinkTargetEscapingRootIsDenied` / `l3_12_readAlsoGoesThroughRealpath` |
| L3-13 | 中间组件是 symlink | 拒绝；最深存在祖先回溯正确 | ✅ `SandboxedFsTest.l3_13_symlinkAsMiddleComponentIsDenied` / `l3_13_leafMayNotExistButMiddleComponentStillChecked` / `newFileUnderNormalDirectoryIsAllowed` |
| L3-14 | symlink 环 | 深度上限 32 → 提前拒绝（不死循环） | ✅ `SandboxedFsTest.l3_14_symlinkLoopIsDeniedInsteadOfHanging` / `l3_14_selfLoopIsDenied` |
| L3-15 | 写前观察三态（M7） | 没见过就 write 已存在文件 → 拒；见过但已改/已删 → 拒；见过且未变 → 放行 | ✅ `ObserveGateTest.l3_15_writeWithoutReadIsDenied` / `l3_16_writeAfterReadWithoutChangeIsAllowed` / `l3_17_writeAfterReadButChangedIsDenied` / `l3_17_deletedAfterReadIsDenied` + `FileToolsTest.l3_15_*` |
| L3-16 | edit 未读过 | 文案 `edit 拒绝：编辑前必须先 read…` | ✅ `FileToolsTest.l3_16_editWithoutReadIsDenied`（文案逐字断言） |
| L3-17 | 新建文件免读 | write 不存在的路径 → 放行（createIfAbsent 语义） | ✅ `FileToolsTest.l3_17_newFileDoesNotRequireRead` + `ObserveGateTest.newFileDoesNotRequirePriorRead` |
| L3-18 | 非零退出码（M10） | exit 1 → `isError=false`，输出末尾 `[exit code: 1]`；超时 → `isError=true` | ✅ `ShellToolTest.l3_18_nonZeroExitIsNotAnError` / `l3_18_successExitIsAlsoMarked` / `l3_18_timeoutIsAnError` + `LocalRunnerTest.nonZeroExitCodeIsAResultNotAnError` |
| L3-19 | 截断工具调用拒执行 | finish_reason 显示截断 → 文案含「参数可能不完整，已拒绝执行」 | ✅ J1 已实现（`ReActDriverTest`），文案出自 `Texts.truncatedToolCall` |

## D3 Java 差异诚实降级断言

| # | 场景 | 断言 | 现状（守护测试） |
|---|---|---|---|
| L3-20 | 进程组击杀尽力而为 | 子孙进程清理走 `ProcessHandle.descendants`；清理失败不静默（记录 + 结果注明），文档声明非原子 | ✅ `SandboxBoundaryTest.descendantsKillerDeclaresItsWeakness`（文案含「尽力而为」「非原子」「不等价于 Go」）+ `descendantsKillerKillsTheProcess` + `DescribeSandboxTest.describeReportsFileAndSandboxBoundaries`（降级文案进 `Describe()` 快照） |
| L3-21 | 版本令牌降级 | 令牌形态 `dev:ino:size:mtimeNs`；mtime 粒度内修改可能漏检 → 文档声明（不改行为，只声明边界） | ✅ `ObserveGateTest.versionTokenUsesDevInoSizeMtimeOnUnix` / `versionTokenChangesWhenContentChanges` + `FileSystemBoundary.caveats()` 把退化口径写进 `Describe()` |

## D4 工具全集与联网（J5 点亮）

| # | 场景 | 断言 | 现状（守护测试） |
|---|---|---|---|
| L3-22 | grep 非法正则报错 | 文案含「正则表达式非法」；不静默当字面量 | ✅ `BuiltinSearchToolsTest.grepRejectsInvalidRegex` |
| L3-23 | grep 二进制跳过 / limit 截断 | 含 NUL 的文件跳过并注明；limit 截断后注明已达上限 | ✅ `BuiltinSearchToolsTest.grepSkipsBinary` / `grepHonoursLimit` |
| L3-24 | glob 语义 | 不含 `/` 按文件名；含 `/` 按相对路径；`**/` 目录可选；非法模式报错；越界由 jail 拦 | ✅ `BuiltinSearchToolsTest.globMatchesByFileName` / `globMatchesByPath` / `globSupportsCharClassAndBraces` / `globRejectsInvalidPattern` / `globIsJailed` |
| L3-25 | grant_access 三态 | 批准才加根；拒绝不加根且含「不许绕」；回调异常 = fail-closed 拒绝；批准不缓存 | ✅ `GrantAccessToolTest.approvedRootBecomesAccessible` / `rejectedApplicationDoesNotAddRoot` / `approverFailureIsFailClosed` / `approvalIsNotCached` |
| L3-26 | web_search 失败是工具错误 | provider 整体失败 → `ToolException`（`web_search 失败: …`）；**部分查询失败**才保留结果 + `note`；全部失败且零结果 → `web_search 失败: 全部查询都失败（…）: …`；结果带不可信标注 | ✅ `WebToolsTest.searchFailureIsAToolError` / `searchPartialFailureKeepsResults` / `searchAllQueriesFailedIsError` / `searchRendersProviderResults` |
| L3-27 | SSRF 防线（公网校验） | 回环 / 内网字面量 / IPv4 映射伪装 / 链路本地（云 metadata）/ TEST-NET 三段 / `0/8` / `224/4` 起整段全部拒绝；**IPv6 内嵌 IPv4 四类形式（IPv4-mapped / NAT64 / 6to4 / Teredo）一律拒且不论内嵌何地址**；校验解析结果非 URL 文本；注入 resolver 仍过公网校验 | ✅ `PinnedConnectorTest.resolveRejectsNonPublicAddresses` / `publicAddressClassification`（Go 完整清单 23 private + 4 public）/ `embeddedIpv4VariantsAreRejectedRegardlessOfInnerAddress` + `PinnedConnectorTransportTest.injectedResolverStillEnforcesPublicAddresses` |
| L3-28 | 抓取后端可注入 | 宿主自备 `Fetcher`（代理 / 内网）后能真拿到回执；**换掉后端就连防线一起换掉**（注入后端后私网地址不再被拦）；`Fetcher` 可为 `null`，未装配在**执行期** fail-closed | ✅ `WebToolsTest.customFetcherReplacesTheGuards` / `fetchRequiresFetcherAndUrl` |
| L3-29 | deferred 下一轮生效 | activate 当轮清单不变；下一轮出现；未登记名忽略并说明；重名登记 fail-closed | ✅ `DeferredFlowTest.activationTakesEffectNextRound` + `DeferredToolsTest.deferredToolsAppearOnlyAfterActivation` / `activateIgnoresUnknownNames` / `duplicateDeferredRegistrationFails` / `noHelpersWithoutDeferredTools` |
| L3-30 | 历史工具名并入兑底 | 模型直接调未激活 deferred 工具 → 执行成功并入激活集 | ✅ `DeferredFlowTest.historicalCallIsAdmitted` |
| L3-31 | web_fetch 传输层（真 socket） | 出站报文头与 Host（含显式端口）；chunked / trailer / 非法长度；gzip；头名大小写不敏感；字节与字符（按码点）双上限；404 是结果；三类非文本 / 缺 Content-Type 拒绝；同源重定向 / 跨源只发一个请求 / 跳数上限 / 缺 Location；注入解析器仍拦私网；非 HTTP 响应报错 | ✅ `PinnedConnectorTransportTest`（22 条，真 socket + 注入 resolver/dialer） |
| L3-32 | web_fetch 回执形状 | 6 键固定都在（空标题发空串、truncated 发 false）；非 2xx 是结果不是错误；HTML 转纯文本 + title；截断附注 | ✅ `WebToolsTest`（回执 5 条）+ `HtmlTextTest`（9 条） |

> **G5 防线的说明（诚实边界）**：地址钉住与同源重定向的**真实网络**行为仍有环境边界：传输层已由 `PinnedConnectorTransportTest` 用**本地真 socket + 谎报解析器**端到端跑通（chunked / gzip / 上限 / 重定向全真跑），但这并不等于「对真公网主机做过 rebinding 对拍」——后者需要恶意的权威 DNS 服务器，当前环境不可行。这条边界仍登记在 `SELF-INVENTED.md` SI-22。

## 文案对照验收

每条含文案断言的场景（L3-1/2/4/5/6/16/19）在测试里引用**同一个文案常量类**（`net.hegq.mos4j.core.Texts` 或同语义），常量值与 `API.md` §4 逐字一致——文案改一处，测试全红。
