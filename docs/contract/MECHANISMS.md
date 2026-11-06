# MECHANISMS — MoS4J 十个机制契约（J0 · D2）

> **地位**：机制是不变式（`INVARIANTS.md`）在代码里的落法。每个机制 = 机制陈述 + 参考实现位置 + 违反后果 + Java 落法 + 验证场景。
> **状态**：冻结。修订需 PR 评审。

---

## M1 载荷冻结

**陈述**：钩子不得改写工具名 / 参数 / CallID。守卫判定的是这次调用的**最终载荷**；改写即违约 → 拒绝执行，理由含「载荷冻结」。
**参考实现**：`agent/execpath/execpath.go:preflight`（post-execute 环绕环节同样受约束）。
**违反后果**：①守卫看到的不是最终载荷 → 判定可能失效（守卫只跑一次，不复检 → 漏判）；②有副作用的守卫（配额/审批）作用在错误载荷上；③CallID 被改写 → tool_call↔tool_result 配对断裂 → 投影历史非法。
**Java 落法**：`ToolCall.args` 保持 **String 原始 JSON 文本**（不解析成对象——解析后重新序列化会改字节序，导致冻结检查误报）；冻结检查用 `String.equals` 逐字比对 name/args/id。落点在 `mos4j-execpath` 的 `ExecPath`：preflight 冻结检查 + 执行前二次校验。
**编排顺序**：`守卫 → 钩子链 → 冻结检查 → 审批 → 执行 → 回填 → post hook`。守卫排在钩子前面（安全判定不应发生在扩展代码之后）；守卫判定的仍然是最终载荷——改写一律被冻结检查拒掉，能执行下去的载荷必然就是守卫看到的那一份。
**验证**：J2 场景——钩子改写任一字段 → 该调用 `isError=true`，理由含「载荷冻结」。
`ExecPathSecurityTest.l3_3_rewritingNameIsFrozenViolation` / `l3_3_rewritingArgsIsFrozenViolation` / `l3_3_rewritingCallIdIsFrozenViolation` / `l3_3_frozenToolIsNotExecuted`。

---

## M2 回填序 = 调用序

**陈述**：`ExecuteBatch(List<ToolCall>)` 返回 `List<Outcome>` 与入参**严格同序**；并发只发生在 dispatch 阶段，结果写回各自槽位。模型看到的 tool_result 顺序必须与它发出的 tool_calls 对应。
**参考实现**：`execpath.go:finalize`。
**违反后果**：对话语义错乱；「哪个结果对应哪个调用」只能靠模型猜（CallID 配对在部分协议上是弱保证）。
**Java 落法**：`mos4j-execpath` 用按调用索引的槽位数组回填，完成后按序输出。
**验证**：J2 场景——并发组内人为反序完成 → 断言输出顺序 == 调用顺序。

---

## M3 错误只可加重不可减轻

**陈述**：post-execute 钩子可把成功改失败（发现问题就报错），不能把失败洗成成功（单向判定）。
**参考实现**：`execpath.go:finalize`。
**违反后果**：见不变式 I3。
**Java 落法**：finalize 单向 if（成功 && err != null → isError=true）；无反向分支。`PostHook` 接口（`mos4j-hook`）在**接口层面**就没有「清除错误」的能力——它只返回一段内容，是否算错由执行通道单向判定。
**验证**：J2 场景——钩子试图洗白失败 → `isError` 仍为 true。
`ExecPathSecurityTest.l3_8_postHookCannotWashAFailureIntoSuccess` / `l3_8_postHookCanWorsenASuccess`。

---

## M4 投影自愈（合成中断回执）

**陈述**：进程被 kill 留下**无回执**的 tool/call（洞就是发生过的事实，日志不改写）→ 投给服务商的历史缺 tool 消息 → 服务商直接拒收整段对话。投影层补**合成中断回执**：带工具名与中断语义，按声明序补在首个非 tool/result 事件之前，序号继承声明它的 assistant 事件（→ 压缩遮蔽时与调用方同生共死）。
**参考实现**：`session/session.go:projectSeq`（文案 `（{toolName} 调用在执行期间被中断，没有结果；如仍需要请重新调用）`）。
**违反后果**：崩溃恢复后整段对话不可用，用户被迫重建会话。
**Java 落法**：`mos4j-session` 的 `messages()` 投影时逐 seq 检查 tool/call 是否有配对 tool/result，缺则合成 tool 消息回执（`isError` 语义由文案表达，投影消息上协议无 error 位）。
**验证**：L2 场景（T0.6）——孤儿 tool/call → 投影补合成回执；压缩遮蔽后回执随 assistant 一起被遮蔽。

---

## M5 流完整性按结束标记判定

**陈述**：流完整性 = 「**结束标记** 或 **结束原因** 任一到即完整」；两者皆无 → `ErrStreamTruncated`。**结束原因为空 ≠ 截断**（空值合法）。
**参考实现**：`llm/anthropic/anthropic.go:469-599`。
**背景**：有网关只发 `message_stop` 不发 `message_delta`（无 stop_reason、无最终 usage）——只看结束原因会把该网关的所有正常流误判成截断，工具调用全被拒。
**三协议结束标记**：Anthropic `message_stop`；OpenAI `[DONE]`；Gemini `finishReason`（更严，宿主可用 `require_finish=false` 关掉）。
**Java 落法**：`mos4j-llm` 各协议适配器按上述判定；截断错误类型 `ErrStreamTruncated` 对位 Java `StreamTruncatedException`。
**验证**：L1 场景（T0.5）——「只有 message_stop 的完整流」与「无任何结束信号的截断流」两条都要有，断言前者完整、后者抛截断。

---

## M6 用量口径统一在 FreshInputTokens

**陈述**：`Usage.promptTokens` = 协议原始值（OpenAI/Gemini 的 prompt 含缓存命中，Anthropic 的 input 不含）；`Usage.freshInputTokens` = 去缓存后的计费输入（跨协议同口径）；0 且 promptTokens > 0 表示该协议未提供此口径 → 按 promptTokens 用；缓存读/写**单列**在 details；`cacheHitRatio` 分母用去缓存口径。
**参考实现**：`llm/model.go`（`FreshInput()`）、`session/session.go:TotalUsage`。
**违反后果**：跨协议比较失真；缓存命中混进「输入」看不出成本结构；同百分比在两协议下含义不同。
**Java 落法**：`mos4j-llm` 的 `Usage.freshInput()` 方法同语义；会话总量折叠压缩调用的用量（「session totals include summarization work」）。
**验证**：不变式测试——分项之和 == 总计（按各协议自身口径）；L1 逐字段断言。

---

## M7 写前观察（CAS 防丢失更新）

**陈述**：模型 read 之后文件被 shell/别人改了，再 write/edit 会盖掉它没见过的改动。观察门记录「模型见过的文件状态 + 版本令牌」，写/改前 CAS 校验：
- write **已存在且未读过** → 拒绝（文案见 `API.md`）；新建（不存在）→ 放行，无需先读；
- write 读后**已删除** / **已修改** → 拒绝（要求重新 read）；
- edit 一律要求先读。
**参考实现**：`tool/builtin/observe.go`。
**违反后果**：丢失更新——模型盖掉并发修改，且不自知。
**Java 落法**：`mos4j-fs` 的 `ObserveGate`（**闸门只给事实：原因码 + 令牌**，文案由工具层按 `API.md` §4 拼装——文案散落各写一遍，迟早有一处漏掉半句）；版本令牌 Java 口径 `dev:ino:size:mtimeNs`（`Files.getAttribute(path,"unix:dev"/"unix:ino")`；拿不到 `unix:` 视图时退化为 `size:mtimeNs`）。**令牌不含 ctime**：JDK 的 `unix:` 视图其实**能**读到 `ctime`，但冻结口径不含它（跨平台语义不统一，有的文件系统读操作也会更新 ctime）；代价：**只改元数据、内容不变的改动检测不到**。CAS 本身仍是**尽力而为、非原子**。
**验证**：L3 场景（T0.7）——没见过就写（拒）、见过但已改（拒）、见过且未改（放行）、edit 没读过（拒）各一条。
`ObserveGateTest.l3_15_writeWithoutReadIsDenied` / `l3_16_writeAfterReadWithoutChangeIsAllowed` / `l3_17_writeAfterReadButChangedIsDenied` / `l3_17_deletedAfterReadIsDenied` / `newFileDoesNotRequirePriorRead` / `fileAppearingAfterAbsentObservationIsDenied` + `FileToolsTest.l3_15_*` / `l3_16_editWithoutReadIsDenied` / `l3_17_newFileDoesNotRequireRead`。

---

## M8 压缩遮蔽与切割点

**陈述**：压缩把旧历史用摘要**遮蔽**（不改写日志）：`Compaction{summary, firstKeptSeq, tokensBefore}`；投影丢弃 firstKeptSeq 之前的可投影消息；摘要以 `[历史摘要]` 前缀注入 user 角色；**切割点永不落在 tool 结果上**（否则投影非法）；连续两轮若都压缩，上一轮摘要作为 previous-summary 传给模型；旧摘要靠前缀识别。
**参考实现**：`session/compaction.go` 语义（Java 侧在 `mos4j-context`）。
**违反后果**：切割在 tool 配对中间 → 服务商拒收；摘要被重复注入或丢失。
**Java 落法**：`mos4j-context` 的 CompactionService；切割点搜索沿连续性规则回退。
**验证**：L2 场景（T0.6）——压缩后投影合法；切割点合法；两轮压缩的 previous-summary 传递正确。
**J4 已落地**：`mos4j-context` 的 `CompactionPolicy` / `CutPointFinder` / `Transcript` / `SummaryGenerator` / `CompactionService`；驱动在**每轮决策前**调 `CompactionService.maybeCompact()`。
- 触发：`contextTokens > contextWindow - reserveTokens`；**未配置 contextWindow 永不触发**（不拿猜的窗口做决定）。
- 估算：两段式——基座是**最后一次模型调用的真实用量**，其后新增事件按 chars/4 估（`Session.contextTokens()`）。
- 切割点：保留段不能以 tool 结果开头；找到声明者则**调用方与回执一起遮蔽**，找不到声明者则孤儿回执也遮蔽。
- 摘要失败 / 摘要为空 → **不遮蔽**（遮蔽了却留不下摘要 = 旧历史彻底消失）。
- 旧摘要折叠：上一轮摘要以 `<previous-summary>` 单独传给摘要器，不以 `[User]:` 行重复出现（防滚雪球）。
- 守护：`CompactionBudgetTest`（7 条）+ `CompactionCutPointTest`（3 条）+ `PreviousSummaryTest`（3 条）+ `ContextFlowTest`（5 条，接线级）。

---

## M9 路径双校验

**陈述**：jail 内路径解析必须两层：**Lexical**（规范化后落在根内）+ **Realpath**（逐段解析符号链接）。新建路径（叶子不存在）→ 向上回溯找**最深存在祖先**，验证祖先不越界后拼回剩余后缀。symlink 深度上限 32（防环，提前拒而不是死循环）。**读同样要走 Realpath**——根内一个指向 `/etc/passwd` 的 symlink 就能越界读。
**参考实现**：`filesystem/sandboxed.go:Resolve`。
**违反后果**：三种逃逸形态：目标是链接 / 中间组件是链接 / 链接环 → 越界读写。
**Java 落法**：`mos4j-fs` 的 `SandboxedFs.resolve()`。**逐段解析**（不是「先 normalize 再 toRealPath」）：`normalize()` 是纯字面操作，遇到符号链接就撒谎——`root/link/../x` 字面上是 `root/x`，真实文件系统里却是「link 指向目录的父目录/x」。所以走一段、解一段、每段确认还在根内；**深度上限 32**（计数器，超限 fail-closed，不死循环）。新建路径（叶子不存在）走同一条路：中间组件只要是链接就照样解析，解析后越界照样拒（「最深存在祖先」由此满足）。活根集合 `RootSet`：加根是**追加**、**锚点不变**、**对下一次调用立即生效**、变更留痕。拒绝文案带「该操作允许的根」（说「哪些根允许」比说「什么模式」有用）。
**验证**：J3 场景——三种逃逸形态各有测试，全部拒绝；正常相对路径、`..` 回退不出根的路径放行。
`SandboxedFsTest.l3_11_dotDotOutOfRootIsDenied` / `l3_12_symlinkTargetOutsideRootIsDenied` / `l3_13_symlinkAsMiddleComponentIsDenied` / `l3_14_symlinkLoopIsDeniedInsteadOfHanging` / `l3_12_readAlsoGoesThroughRealpath` / `l3_13_leafMayNotExistButMiddleComponentStillChecked` + `RootSetTest`（8 条）。

---

## M10 非零退出码不是错误 + 拒绝文案带「不许绕」

**陈述**（两部分）：
1. shell 工具：非零退出码 → **正常结果**（`isError=false`），回执带 `exit_code` 字段且输出末尾附 `[exit code: N]` 标记；只有「命令根本没起来」（启动失败/超时/沙箱拒绝）才 `isError=true`。把非零退出当失败 → 模型自己写 hack 绕开（实测：拼 `; echo "exit=$?"` 让整条命令以 0 退出，把失败掩进输出——**失败被掩进输出，事实被污染**）。
2. 审批拒绝文案**照抄 Go**，含「不要尝试用其他命令或方式达成同一目的」的「不许绕」半句——实测不加这句，模型会拆解命令绕过规则（把 `git rm -r lab` 改成逐个 `git rm` 达成同一目的）。
**参考实现**：`tool/builtin/shell.go`、`execpath.go:preflight` 拒绝分支。
**Java 落法**：`mos4j-sandbox` 的 ShellTool 结果构造；文案表见 `API.md` §4.3 对位章节。拒绝文案在 Java 侧的**唯一出处**是 `net.hegq.mos4j.core.Texts`（散落各写一遍，迟早有一处漏掉半句，而且测试很难发现——那句仍然「像那么回事」）；测试只引用 `Texts`，不复制字符串常量。
**验证**：L3 场景（T0.7）——exit 1 → isError=false + 末尾标记；超时 → isError=true；审批拒绝文案逐字断言含「不许绕」语义句。
文案侧已绿：`ExecPathSecurityTest.l3_5_humanRejectionKeepsTheNoWorkaroundSentence` + `SecurityFlowTest.humanRejectionReachesTheModelWithTheNoWorkaroundSentence`。
退出码侧已绿（J3）：`ShellToolTest.l3_18_nonZeroExitIsNotAnError` / `l3_18_successExitIsAlsoMarked` / `l3_18_timeoutIsAnError` + `LocalRunnerTest.nonZeroExitCodeIsAResultNotAnError`。

---

## 机制 × 不变式 × 里程碑 总览

| 机制 | 守的不变式 | Java 模块 | 首个验证里程碑 | 状态 |
|---|---|---|---|---|
| M1 载荷冻结 | I5 | mos4j-execpath | J2 | ✅ J2 |
| M2 回填序 | I3/I5 | mos4j-execpath | J2 | ✅ J1 |
| M3 错误只加重 | I3 | mos4j-execpath | J2 | ✅ J2 |
| M4 投影自愈 | I2 | mos4j-session | J1 | ✅ J1 |
| M5 流完整性 | I1（诚实的完整性判定） | mos4j-llm | J1/J6 | ✅ J1（OpenAI）/ J6 |
| M6 FreshInputTokens | I4（成本可观测） | mos4j-llm/session | J1 | ✅ J1 |
| M7 写前观察 | I1（防丢失更新） | mos4j-fs | J3 | ✅ J3 |
| M8 压缩遮蔽 | I4 | mos4j-context | J4 | ✅ J4 |
| M9 路径双校验 | I1（越界防护） | mos4j-fs | J3 | ✅ J3 |
| M10 退出码/不许绕 | I2/I3（事实不被污染） | mos4j-sandbox/execpath | J2/J3 | ✅ J2（文案）/ ✅ J3（退出码） |

## 附：不属于 M1–M10 的机制契约（J7 技能机制）

技能机制是 Go `agent/skill` 的机制重写，**不归入 M1–M10**（那十条是载荷/日志/上下文层的硬约束），
它守的是 I4（上下文稀缺：渐进式披露）+ I6（零开销降级：无技能时不注清单也不注册工具）+ I2（日志即事实：审计先于加载）。

| 契约点 | 口径 | 落点 | 验证 |
|---|---|---|---|
| 渐进式披露 | 清单（name + description）常驻系统提示词，正文调用时现读 | `SkillCatalog.render()` / `renderContent()` | `SkillCatalogTest` / `SkillFlowTest` |
| 条件注册 | 模型可见技能为空 → **根本不注册** `skill` 工具 | `Agent.of` + `ToolRegistry.visibleNames()` | `SkillFlowTest`（无技能不注册 / 全 disable 不注册） |
| 审计先于加载 | `skill/activate` 写失败 → 不返回正文 | `SkillTool.execute` + `SessionSkillAudit` | `SkillToolTest` |
| 正文不入快照 | digest 只含元数据；正文不驻留 | `SkillCatalog.metaDigest()` | `SkillCatalogTest` |
| 审计不改工具面 | `allowed-tools` 只求交留痕 `missingAllowed` | `SkillCatalog.checkAllowedTools()` | `SkillToolTest` / `SkillFlowTest` |
