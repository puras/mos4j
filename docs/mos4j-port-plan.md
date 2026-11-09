# MoS4J 整体方案（Java 移植）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../README.md) 与 [`docs/contract/API.md`](contract/API.md)。

> 输入：`/Users/puras/workspace/proj/hegq/mos`（Go 版 MoS，只读）、`docs/ARCHITECTURE.md`、`docs/PRD.md`、移植方案 `mos-default/docs/mos-java-port-plan.md`。
> 性质：**照契约重写**，不是照代码翻译。每个机制必须能说清「为什么这样设计」「不这么做会出什么事」。
> 命名已定（MoS4J / `net.hegq` / `mos4j-*` / `net.hegq.mos4j.*` / CLI `mos4j`），本文不再讨论。
> 本文写方案；**实现自 J1 起已落地**（J0 只冻结契约，不写实现代码）。代码现状以 `docs/contract/J1-STATUS.md` 为准。

**文档集分工**（三份，各司其职）：

| 文档 | 回答的问题 | 读者 |
|---|---|---|
| **本文（`mos4j-port-plan.md`）** | **整体方案**：路线判定、目标架构、契约冻结内容、六处差异处置、风险与不做清单 | 全员 |
| [`mos4j-tasks.md`](mos4j-tasks.md) | **任务列表**：可勾选的任务条目（ID / 依赖 / 产出物 / 验收 / 估算） | 执行者 |
| [`mos4j-execution.md`](mos4j-execution.md) | **推进方案**：怎么一步步做（阶段顺序、CI 门、检查点、评审规范、协作流程、停点汇报格式） | 执行者 + 维护者 |

---

## 0.0 定位与验收策略（2026-11-03 二次拍板）

**驱动力已明确**：「只是参考当前 MoS 的实现，用 Java 重新实现——必须用 Java 写一份。」由此确定两件事：

**① 交付形态：SDK 为本体，starter 按需。**

- MoS4J 的实质是 **`mos4j-*` 那 12 个 SDK 模块**（sdk / llm / tool / session / execpath / hook / policy / fs / sandbox / context / driver / core）；starter 只是 SDK 顶上的自动装配薄层，且**依赖 SDK、不能反向**——只做 starter 等于把 Spring 绑进内核，非 Spring 宿主（CLI、Quarkus/Vert.x、其他 SDK 消费者）就用不了。
- 当前没有具体的 Spring 宿主需求 → 按 MoS 自己的「能力即证据」纪律，**starter 移出里程碑，进入按需清单**（见 `mos4j-tasks.md` §10）：首个真实 Spring 宿主需求出现再做，估算 ~1.5 人日。SDK 层保持无框架污染（Spring / Guava / DB / ORM 均不进，CI 有依赖门拦截）。

**② 验收策略：参考实现，不是逐字节对拍。**

定位从「语义逐字节对齐的移植」放宽为「参考 MoS 的语义重写」。据此：

| | 保留 | 卸掉 / 替代 |
|---|---|---|
| 七条不变式 + 十个机制 | **全保留**（MoS 踩坑换来的 80% 价值，「参考」参考的就是这些） | — |
| 事件字段名（`seq`/`kind`/`turn`/`toolCalls`…） | **照抄**（成本为零，读 Go 文档名词对得上） | — |
| 关键文案（含「不许绕」半句） | **照抄**（实测教训，不是措辞偏好） | 放宽为「语义等价」，无实质影响的小差异允许，差异需在 PR 说明 |
| Go 侧导出器（`tools/golden-export`） | — | **卸掉**：不产出 Go 基线，Go 仓库保持只读参考 |
| L1 协议语料 | SSE 报文**照抄** Go 测试里的现成字面量（`anthropic_test.go` 等，真实抓包非手造） | 期望值 Java 自算，不与 Go 基线机器比对 |
| L2/L3 语料 | 场景设计保留（压缩遮蔽 / 孤儿 tool/call / 载荷冻结…） | Java 侧自建测试 |
| 字段名校验 | 降级为**静态清单守护测试**（清单从 Go 抽一次，冻结后变更走 PR 评审） | 不随 Go 演进自动重抽 |

**代价（如实登记）**：语义一致性从「机器保证」降为「测试自觉」；与 Go 可能积累措辞差异（语义等价）。**触发回补的条件**：将来需要与 Go 版互操作（消费 Go 版会话日志、跨语言对拍验收）时，再把导出器与三层对拍捡回来——契约文档与字段名冻结使这一步是增补而非重构。

---

## 0. 结论与待拍板项

### 0.1 结论

可行，且**只有 J0 做完才有意义**。MoS 的价值 80% 在语义（7 条不变式 + 10 个机制 + 事件契约），这部分在 Java 里是 100% 可移植的；剩下 20% 在机制（取消传播 / 进程组击杀 / 版本令牌 / Schema 生成 / 协议层 / 正则），Java 侧**没有等价物**，只有显式降级——降级必须写进文档与 `Describe()` 快照，不许假装等价。

本方案把「照契约重写」落到三件可验证的东西上：

1. **契约冻结**：七条不变式 + 十个机制，逐条写下「不这么做会出什么事」——这是 J0 的第一份产物，也是后续每个 PR 的合入门。
2. **自建语料**：L1 协议报文照抄 Go 测试里的现成字面量（真实抓包，不是手造）；L2/L3 场景 Java 侧自建——用于锁住机制语义，**不与 Go 基线机器对拍**（§0.0②）。
3. **接口契约**：事件字段名照抄 Go（含 camelCase 与 snake_case 混用的不对称性），静态清单冻结 + 守护测试。

### 0.2 路线判定：路线 A 拍板（2026-11-03）

**驱动力已定：参考 MoS 的实现，用 Java 重新实现一份。** 由此判定走**路线 A（全量 Java 移植）**，且交付形态为 **SDK 本体 + starter 按需**（§0.0①）、验收策略为**参考实现 + 自建场景测试**（§0.0②）。

团队要的是 Java 侧的原生实现（用 Java 写工具、策略、守卫、审批通道），不是「Java 调用 Go 内核」；路线 B（Go 内核 + Java 宿主）会让团队同时维护两套语义，路线 C（半吊子双实现）明确不做（§9）。

**工程纪律面向团队而非个人**：PR 模板必须勾选「本次改动触及哪条不变式 + 哪个场景测试」；模块边界检查（ArchUnit）进 CI；贡献指南写清「语义对齐 MoS」的判定流程。公共 API 的 Javadoc 必须写清「不这么做会出什么事」，不能只写「这是什么」。详见 `mos4j-execution.md`。

**并行度待定**：1 人 ~17 周（见任务列表 §9）；事件契约只能一人改。

### 0.3 本方案与 Go 仓库的纪律对齐

Go 仓库 `AGENTS.md` 要求「任何机制设计附 PI/DSH 参考分析，不自创机制」。Java 移植的对应纪律是**语义对齐 MoS**：拿不准某个机制怎么实现时，看 MoS 怎么做的、为什么这么做。

具体到本方案：

- **凡 Java 版与 Go 版语义等价的实现**（占 95%）：照 MoS 契约重写，不需要重做 PI/DSH 分析——MoS 已经做过，重做一遍只是复制粘贴，且会引入新的漂移。
- **凡 Java 侧必须自创处置的**（六处差异，§5）：每处给出「为什么不能等价 + 降级边界 + 诚实声明的位置」，并标注「是否需要 PI/DSH 对位核实」。其中 **进程组击杀**（§5.2）与 **取消传播**（§5.1）涉及「Java 有没有更好的等价物」，若需要读 PI/DSH 源码核对（路径 `/Users/puras/workspace/source/pi`、`.../deepseek-harness`，当前未授权），我会单独申请只读权限。

---

## 1. 契约冻结：七条不变式

> 每条写下「它守住了什么」与「不这么做会出什么事」。这是 J0 的第一份产物，也是后续每个 PR 的合入门清单。

### 1.1 fail-closed

**守住什么**：能力缺失即报错，不静默降级。

**为什么**（逐条对应 Go 的现状）：

| 场景 | 不这么做会出什么事 |
|---|---|
| 未知工具 | 模型幻觉出的工具名会落到「任意实现」上——注册表是能力边界，不是建议 |
| 缺能力（未装 `Tools` / 未装 `sandbox.Runner` / 未装 `Approval`） | 「没配沙箱就跑裸命令」是归档实现真实存在的缺陷；`ask` 降级为放行 = 审批形同虚设 |
| 截断消息里的工具调用 | 参数可能是半截 JSON，执行 = 拿残缺参数做破坏性操作（2026-10 事故：半截 write 被当完整消息处理） |
| 审批未装配 | 同上，「先执行再问」是灾难性的默认 |
| 缺必填组件（规划器/评审器） | 降级放行 = 用更弱的语义冒充框架语义 |

**Java 侧的对应纪律**：Java 的 `null` 与 Go 的 `nil` 语义一致，但 Java 有「可选参数 + 重载」这种容易把 fail-closed 变成「默认分支」的语言特性。**Java 版的硬规矩：所有能力位的 getter 在未装配时返回「显式缺失」对象（`Optional.empty()` 或专用 `NotConfigured` 异常），绝不用 `null` 让调用方自己判断**——调用方漏判 = 静默降级。

### 1.2 日志即事实

**守住什么**：会话事件日志 append-only，是唯一事实来源。

**为什么**：

- `Append` **同步落盘 fail-fast**：写失败则事件不进内存。若允许「先进内存后落盘」，落盘失败时内存与日志分叉，事后无法判断哪份是真的——日志是审计凭据，分叉即失去凭据资格。
- **崩溃恢复只补事件、不改写**：append-only 日志里的「洞」是发生过的事实。改写历史 = 伪造证据。孤儿 turn 只能在恢复时**合成** `turn/end{interrupted}` 事件，不回写已有行。

**Java 侧的对应纪律**：`Persistence.append()` 抛异常 → `Session.append()` 必须**在写入内存之前**抛出（Go 是 `persist.Append` 在 `s.events = append(...)` 之前）。Java 里容易写成「先 append 到 list 再 try 落盘」——顺序反了就是违约。另外 Jackson 的 `ObjectMapper` 不可复用为线程安全写入器，需要 per-write 或 thread-local 序列化（Go 的 `json.Encoder` 是 per-call）。

### 1.3 审计不变式

**守住什么**：只有**环境自证**的工具输出可以进 `TaskState`。

**为什么**：模型自述「我已完成 X」不是事实，是模型的自我评价。若模型自述可入 `TaskState`，整个状态机就是模型在给自己打分——审计门形同虚设。机器可回放信号（退出码、路径命中、事件日志）才可验证。

**Java 侧的对应纪律**：审计门白名单是**工具名级别**的封闭集合（`current_time` / `read` 等只读工具）。Java 版要显式建模为 `Set<ToolName>` 或 `Auditor.Trust` 注册，**不允许通配符**（Go 版就没有）。

### 1.4 上下文是稀缺资源

**守住什么**：截断 + 压缩；压缩切割点**永不落在 tool 结果上**；摘要落日志、投影遮蔽旧历史。

**为什么**：

- 切割点落在 tool 结果上 → 保留段以孤儿 tool 消息开头 → **投给服务商的请求非法**（tool 消息必须紧跟在带 tool_calls 的 assistant 消息之后），服务商直接拒收整个请求。
- 摘要不落日志 → 压缩后的历史无法重放、无法审计「当时模型看到了什么」。摘要落 `compaction` 事件，被遮蔽的事件**留在日志里不删**——审计走全量日志，模型投影是压缩后视图，两者不互相替代。

**Java 侧的对应纪律**：压缩是**派生视图重算**，不是状态修改。`Session.messages()` 每次从事件序列重算，Java 版必须保持「无缓存投影」或「缓存随 append 失效」的一致性。

### 1.5 Hook / Guard 双轨制

**守住什么**：可改写走 HookBus（waterfall），安全约束走 GuardBus（serial 单调）。**钩子结构上无法绕过守卫**。

**为什么**：

- 混成一条链 → 安全约束可被后注册的钩子洗白（钩子可改写载荷，守卫不可）。
- 结构性保证的具体形式：**GuardBus 不持有 HookBus 引用，HookBus 也不持有 GuardBus**；编排点固定在 `execpath.preflight`——先 `guards.allow()` 再 `hooks.waterfall()`。于是「钩子绕过守卫」不是靠约定，是靠类型系统（两个总线互不引用）。
- 载荷冻结是这套结构的**补强**：钩子不得改写工具名/参数/CallID → 守卫看到的就是最终载荷 → 守卫判定恒有效、**只跑一次**（不重复触发有副作用的守卫：配额扣减、审批请求）。

**Java 侧的对应纪律**：Java 的接口默认方法与 lambda 更容易写出「隐式耦合」。硬规矩：`HookBus` 与 `GuardBus` 两个类型的 API 里**互不出现对方的类型**（GuardBus 只接受 `Guard` 函数，HookBus 只接受 `Hook` 函数），编排只发生在 `execpath` 包内。

### 1.6 零开销降级

**守住什么**：扩展点为 null / 未注入时，行为与未注入完全一致，且不付额外成本。

**为什么**：不装扩展点的用户不该为别人的扩展点付费（`Telemetry` / `Observer` / `EventBus` 未注入时零开销，Go 版有基准测试守着）。而且**降级路径必须与主路径同语义**——降级成另一种行为 = 隐藏的 bug。

**Java 侧的对应纪律**：Go 的 `b == nil` 方法调用在 Java 里对应「可空总线 + null 检查」。Java 的写法建议：

- 用 `Optional<HookBus>` 或显式 `null` + 静态工厂 `HookBus.none()`；
- **注意 Go 的 nil-receiver 模式**（`func (b *HookBus) On(...)` 在 `b == nil` 时返回 no-op）——Java 不能靠 null receiver，得靠**总线内部持有一个 no-op 实现**：`HookBus.none()` 返回一个空实现，或用 `Optional.map(...)` 在调用点降级。两种都可以，但必须**测到「未注入 = 未装配」的等价性**（Go 的 `hook_test.go` 有对应测试，Java 必须逐条译过来）。

### 1.7 依赖纪律

**守住什么**：近零依赖；不碰数据库。

**为什么**：

- 近零依赖 → 宿主不被框架的传递依赖绑架（SDK 是要被别人依赖的）。
- 不碰数据库 → 持久化是 `Persistence` 接口 + JSONL 默认实现；数据库后端由业务层注入。框架内置 DB 依赖 = 替宿主做了持久化选型。

**Java 侧的对应纪律**：依赖清单严格控制为 **Jackson（databind+annotations）· RE2J · JUnit5**（测试）+ `com.sun.net.httpserver`（测试）。**注意 Java 的一个隐性依赖面**：Jackson databind 带来的传递依赖（jackson-annotations/core/databind）——这是可接受的（1 个 groupId）。RE2J 是 1 个 groupId 但**有理由**（见 §5.6）。**不引入**：Spring（仅 starter）、Guava、数据库框架、ORM、Lombok（可选）。

---

## 2. 契约冻结：十个必须原样保住的机制

> 每个机制写清「Go 里怎么做的」「为什么」「Java 侧怎么保住」「怎么验证」。

| # | 机制 | Go 位置 | 为什么这样设计（不这么做会出什么事） | Java 侧保住的方式 | 验证 |
|---|---|---|---|---|---|
| 1 | **载荷冻结** | `agent/execpath/execpath.go:preflight` | 钩子若能改写工具名/参数/CallID：①守卫看到的就不是最终载荷 → 判定可能失效（守卫只跑一次，不复检 → 漏判）；②有副作用的守卫（配额/审批）会作用在错误载荷上；③CallID 被改写则 tool_call↔tool_result 配对断裂 → 投给服务商的请求非法 | `ToolCall.Args` 保持为 **String（原始 JSON 文本）**，不解析成对象（解析后重新序列化会改字节序，导致冻结检查误报）。冻结检查用 `String.equals` 逐字比对 name/args/id | `execpath` 测试：钩子改写任一字段 → 该调用 `isError`，理由含「载荷冻结」 |
| 2 | **回填序 = 调用序** | `execpath.go:finalize` | 并发只影响**完成顺序**；若按完成序回填，模型看到的 tool_result 顺序与它发出的 tool_calls 不对应 → 对话语义错乱，且「哪个结果对应哪个调用」只能靠模型猜 | `ExecuteBatch` 返回 `List<Outcome>` 与入参 `calls` **严格同序**；并发只在 dispatch 阶段，结果写回各自 `plan` 槽位 | 并发组内人为反序完成 → 断言输出顺序 == 调用顺序 |
| 3 | **错误只可加重不可减轻** | `execpath.go:finalize` | post-execute 钩子可把成功改失败（发现问题就报错），但**不能**把失败洗成成功——错误是事实，审计日志不能被改写成成功 | `finalize` 里：`if (!outcome.isError && out.err != null) { content = err; isError = true; }`，方向单向 | 钩子试图洗白失败 → 断言 `isError` 仍为 true |
| 4 | **投影自愈** | `session/session.go:projectSeq` | 进程被 kill 留下**无回执**的 tool/call（append-only 不改写，洞就是发生过的事实）→ 投给服务商的历史缺 tool 消息 → **非法历史，服务商直接拒收整段对话** | `projectSeq` 补**合成中断回执**（带工具名与中断语义，按声明序补在首个非 tool/result 事件之前，序号继承声明它的 assistant 事件 → 压缩遮蔽时与调用方同生共死） | L2 场景：孤儿 tool/call → 投影补合成回执，行为与 MoS 语义一致 |
| 5 | **流完整性按结束标记判定** | `llm/anthropic/anthropic.go:469-599` | 有网关**只发 `message_stop` 不发 `message_delta`**（无 stop_reason、无最终 usage）。只看结束原因 → 该网关的**所有正常流**被误判成截断 → 工具调用全被拒 | 三协议各有**结束标记**：`message_stop` / `[DONE]` / Gemini `finishReason`。判定 = 「结束标记 **或** 结束原因任一到即完整」；两者皆无 = `ErrStreamTruncated`。**结束原因未知 ≠ 截断**（空值合法） | L1 语料：`TestChatStreamWithoutMessageDeltaIsComplete` / `TestChatStreamCutWithoutAnyEndSignalErrors` 两条都要进 Java 语料 |
| 6 | **用量口径统一在 FreshInputTokens** | `llm/model.go`、`session/session.go:TotalUsage` | OpenAI 的 prompt **含**缓存命中，Anthropic 的 input **不含** → 跨协议直接相加不可比；缓存命中混在「输入」里看不出成本结构 | `Usage.promptTokens` = 协议原始值；`Usage.freshInputTokens` = 去缓存后的计费输入（跨协议同口径）；缓存读/写**单列**；`cacheHitRatio` 的分母用去缓存口径（否则同百分比在两协议下含义不同） | 不变式测试：**分项之和 == 总计**（按各协议自身口径）+ L1 语料逐字段比对 |
| 7 | **写前观察** | `tool/builtin/observe.go` | 模型 read 之后文件被 shell/别人改了，模型再 write/edit 就**盖掉它没见过的改动**（丢失更新）。版本令牌做 CAS，防丢失更新 | `ObserveGate` 记录「模型见过的文件状态 + 版本令牌」，写/改前 CAS 校验。**新建文件不需要先读**（createIfAbsent），edit 一律要求先读 | 三类场景各有测试：没见过就写（拒）、见过但已改（拒，读后变了）、见过且未改（放行） |
| 8 | **路径双校验** | `filesystem/sandboxed.go:Resolve` | 只做 Lexical（规范化后落在根内）→ 根内一个指向 `/etc/passwd` 的 symlink 就能把内容读出边界——**读同样要走 Realpath**。新建路径（`root/escape/new.txt`）叶子不存在但中间组件是越界链接 → 必须向上找**最深存在祖先**验证 | Lexical + Realpath 两层；新建路径向上回溯找最深存在祖先再拼回后缀；symlink 深度上限 32（防环，提前拒而不是死循环） | 三种逃逸形态各有测试：目标是链接 / 中间组件是链接 / 链接环 |
| 9 | **非零退出码不是错误** | `tool/builtin/shell.go` | 把非零退出当失败 → 模型必须自己写 hack 绕开（实测：它拼 `; echo "exit=$?"` 让整条命令以 0 退出，把失败塞进输出里）——**失败被掩进输出，事实被污染** | 非零退出码 → **正常结果**，`isError=false`，回执带 `exit_code` 且输出末尾附 `[exit code: N]`；只有「命令根本没起来」（启动失败/超时/沙箱拒绝）才 `isError` | shell 工具测试：exit 1 → `isError=false` + 末尾标记；超时 → `isError=true` |
| 10 | **拒绝文案带「不许绕」** | `execpath.go:preflight` 拒绝分支 | 实测不加这句，模型会**拆解命令绕过规则**（把 `git rm -r lab` 改成逐个 `git rm` 达成同一目的） | 审批拒绝文案**照抄 Go**（见 §4.3），不得改写语义、不得省略后半句 | L3 场景测试：断言拒绝理由含该语义 |

---

## 3. 目标架构（Java 侧）

### 3.1 模块与包（依赖方向自上而下，禁止反向引用）

```
mos4j-sdk              net.hegq.mos4j.sdk          宿主装配 DSL（Builder）
   ↓
mos4j-core             net.hegq.mos4j.core         共享词汇表（CancellationToken / 字段名锚点）
   ↓
mos4j-driver           net.hegq.mos4j.driver       LoopDriver 接口 + ReAct 实现
   ├ mos4j-driver/planner                                决策器（Go: planner 包）
   └ mos4j-driver/agent         net.hegq.mos4j.agent    Config / Agent / Describe 快照
   ↓
mos4j-execpath         net.hegq.mos4j.execpath     工具执行通道（三段式）
   ↓
mos4j-hook             net.hegq.mos4j.hook         HookBus + GuardBus
   ├ mos4j-hook/approval                                 Approver / Answerer / 四档结果
mos4j-policy           net.hegq.mos4j.policy       名字级名单 + 参数级决策点
   ↓
mos4j-session          net.hegq.mos4j.session      事件日志 / 投影 / JSONL / TaskState
mos4j-context          net.hegq.mos4j.context      截断 / 压缩 / 审计门 / 提示词 / 上下文文件
   ↓
mos4j-llm              net.hegq.mos4j.llm          ChatModel 抽象 + 共享值类型
   ├ mos4j-llm/protocol/{openai,anthropic,gemini}        三协议 DTO + SSE 解析
   └ mos4j-llm/transport                                 共享 HTTP envelope + 用量归一
mos4j-tool             net.hegq.mos4j.tool         Tool / Registry / Schema 生成
   ├ mos4j-tool/builtin                                 内置工具
   ├ mos4j-tool/web                                     web_search / web_fetch
   └ mos4j-tool/searchtools                             tool_search（BM25）
mos4j-fs               net.hegq.mos4j.fs           文件 jail（双校验）+ 运行期根集合 + 写前观察门 + 版本令牌
mos4j-sandbox          net.hegq.mos4j.sandbox      进程隔离 / 资源上限 / 路径强制（版本令牌在 mos4j-fs）
```

**依赖方向的四个特例（与 Go 版一致，必须在合入门检查）**：

1. **`mos4j-llm` 的值类型子包（`llm.model`）是全框架共享词汇表**——`Message` / `ToolCall` / `Usage` 被 `mos4j-session` 直接引用。这是 Go 版「日志与协议同形、避免翻译边界丢字段」的关键（thinking 模式的 `reasoning_content` 必须原样回传）。**Java 版必须保留**：共享 DTO 上的 Jackson 注解直接决定日志字段名。
2. **`mos4j-session` 依赖 `mos4j-llm`（仅 model 子包）**——基础设施层引用共享词汇表是被允许的；禁止的是能力层/驱动层反向依赖装配层。
3. **`mos4j-tool/web` 不依赖 `mos4j-llm`**——Go 的 `HTTPFetcher` 本来就不走 `httpx` 注入的客户端（ARCHITECTURE §3.6.2 明写：地址钉住要自己控制拨号）。Java 侧用 JDK `HttpClient` 独立实现，**不引入共享 http 模块**（保持用户给定的 13 个 artifactId 不变）。

4. **`Config` / `Agent` / `Describe` 落在 `mos4j-driver`（`net.hegq.mos4j.agent`），不落 `mos4j-core`**（2026-11-05 实现期修正，与本文初稿不同）。理由：`Agent` 要持有并调用 `LoopDriver` / `RunContext`（都在 driver 里），放 core 就得让 core 反向依赖 driver——那会把「地基依赖能力层」变成常态。core 因此只留共享词汇表（`CancellationToken`、字段名锚点 `FieldNames`）。**依赖方向不变，变的只是「谁拥有装配入口」**。

### 3.2 与用户给定 artifactId 列表的两处归置（需确认）

用户已定 13 个 artifactId。Go 侧还有两个包没有独立归属，我的建议：

| Go 包 | 建议归置 | 理由 |
|---|---|---|
| `agent/approval`（151 行） | `mos4j-hook` → `net.hegq.mos4j.hook.approval` | 「双轨制 + 审批」同属安全纵深（J2）；且 `execpath` 已依赖 `hook`，加 approval 不新增依赖边 |
| `planner`（97 行） | `mos4j-driver` → `net.hegq.mos4j.driver.planner` | 决策器是驱动的组件；Go 侧 `driver` 已 import `planner`，归置后依赖边不变 |

若你倾向 `mos4j-approval` 独立成模块，我按你的定（会多一个 artifactId，依赖边不变）。

### 3.3 命名与 Java 表达优势（不是逐行翻译的地方）

| Go | Java | 说明 |
|---|---|---|
| `context.Context` 遍布全栈 | `CancellationToken` 显式传参（§5.1） | Java 无等价物，不假装有 |
| `goroutine` + `sync.WaitGroup` + channel 滚动池 | 虚拟线程 + `Semaphore`（**不用** `StructuredTaskScope`：JDK 25 下它仍是预览 API，公共契约不能用预览特性） | 滚动池语义：`Semaphore` 满则等待一个完成 |
| `sync.RWMutex` / `atomic` | `ReentrantReadWriteLock` / `AtomicLong` | 直译 |
| `ctx.Err()` 的 `context.Canceled` / `DeadlineExceeded` 区分 | `CancellationException` 携带原因（枚举：`CANCELLED` / `DEADLINE_EXCEEDED`） | Go 侧「取消是终止性的」「重试立即停」依赖这个区分 |
| `os.WriteFile` + `f.Sync()` | `Files.write` + `FileChannel.force(true)` | 保 fail-fast 语义 |
| `filepath.EvalSymlinks` | `Path.toRealPath()`（跟随）/ `toRealPath(NOFOLLOW_LINKS)` | 见 §5.3 |
| 临时文件 + rename 原子写 | `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` | 同目录临时文件 |
| `bufio.Scanner` 逐行 | `BufferedReader.readLine()` | **注意**：Go 的 `ReadString('\n')` 保留 `\n`，Java `readLine()` 去掉行分隔符 → 影响 JSONL 的字节级复现（见 §4.4） |
| `encoding/json` + `SetEscapeHTML(false)` | Jackson 默认**不**转义 HTML → 天然对齐 | 这是 Java 侧的真实优势：Go 侧要显式关，Java 侧天然如此 |
| `uuid.New()` | `UUID.randomUUID()` | — |
| `interface{ ... }` 隐式实现 | 显式接口（`Tool` / `Guard` / `Hook` / `Persistence` / `ChatModel`） | Java 契约面更显式，是优势不是负担 |

---

## 4. 接口契约（J0 产出物之二）

### 4.1 共享值类型（`mos4j-llm` 的 `model` 子包）

字段名与 JSON tag **一个不改**：

```java
public record ToolCall(String id, String name, String args) {}   // args = 原始 JSON 文本
public record ContentPart(String type, String text, String url, String data, String mimeType) {}
public final class Message {                                     // role/content/content_parts/tool_calls/tool_call_id/name/reasoning_content
    String role, content, toolCallId, name, reasoningContent;
    List<ContentPart> contentParts;
    List<ToolCall> toolCalls;
}
public final class Usage {
    int promptTokens;          // "prompt_tokens"       协议原始值（跨协议不可比）
    int freshInputTokens;      // "fresh_input_tokens"  去缓存口径（跨协议可比）
    int completionTokens;      // "completion_tokens"
    int totalTokens;           // "total_tokens"
    UsageDetails details;      // "details"
}
```

**关键约束**：`ToolCall.args` 是 **String**（原始 JSON 文本），不是 `JsonNode`。理由：①载荷冻结要逐字比对；②工具参数容错解析（Go 的 `normalizeArgs`：模型偶发输出 `{…}{…}` 拼接）需要**原样保留**再交给工具层修复。

### 4.2 事件契约（`mos4j-session`）

**字段名一个不改**，含 Go 里 camelCase（事件）与 snake_case（协议值类型）**混用**的不对称性：

| Go 字段 | JSON tag | Java |
|---|---|---|
| `Event.Seq` | `seq` | `long seq` |
| `Event.Kind` | `kind` | `EventKind kind`（枚举，值 `turn/start` / `turn/end` / `user` / `assistant` / `tool/call` / `tool/result` / `compaction` / `approval/asked` / `approval/decided`） |
| `Event.Turn` | `turn,omitempty` | `int turn` |
| `Event.Time` | `time` | `Instant time`（**序列化格式见 §4.4**） |
| `Event.Text` | `text,omitempty` | `String text` |
| `Event.Reasoning` | `reasoning,omitempty` | `String reasoning` |
| `Event.ToolCalls` | `toolCalls,omitempty` | `List<ToolCall> toolCalls` |
| `Event.ToolCall` | `toolCall,omitempty` | `ToolCall toolCall` |
| `Event.ToolResult` | `toolResult,omitempty` | `ToolResult toolResult`（`callId` / `content` / `isError,omitempty`） |
| `Event.Usage` | `usage,omitempty` | `Usage usage` |
| `Event.Reason` | `reason,omitempty` | `String reason` |
| `Event.Approval` | `approval,omitempty` | `Approval approval`（`id` / `toolName` / `callId,omitempty` / `reason,omitempty` / `outcome,omitempty`） |
| `Event.Compaction` | `compaction,omitempty` | `Compaction compaction`（`summary` / `firstKeptSeq` / `tokensBefore,omitempty`） |
| `Event.Error` | `error,omitempty` | `String error` |

### 4.3 文案契约（照抄 Go，差异需 PR 说明）

| 位置 | 文案 |
|---|---|
| execpath 载荷冻结违约 | `扩展点试图改写工具调用载荷（载荷冻结契约，调用已拒绝）` |
| execpath 守卫拒绝 | `工具调用被安全策略拒绝: {reason}` |
| execpath 钩子拒绝 | `工具调用被扩展点拒绝: {reason}` |
| execpath 无审批通道的 ask | `工具调用需要人工确认，但未装配审批通道（fail-closed）: {reason}` |
| execpath 审批**人工拒绝** | `用户已拒绝此操作，该操作保持拒绝状态。请停下，向用户说明情况；不要尝试用其他命令或方式达成同一目的。若确有必要，请先向用户说明并获得同意，然后原样重新发起这次调用（会再次请求批准）。拒绝时给出的理由：{reason}` |
| execpath 审批无人应答 | `人工确认无人应答（fail-closed）: {reason}` |
| 投影合成中断回执 | `（{toolName} 调用在执行期间被中断，没有结果；如仍需要请重新调用）` |
| 写前观察（write） | `write 拒绝：{path} 已存在但未被读取过。先 read 读取当前内容，再决定是否覆盖` / `... 自读取后已被删除` / `... 自读取后已被修改。请重新 read 获取最新内容后再写入` |
| 写前观察（edit） | `edit 拒绝：编辑前必须先 read 读取 {path} 的当前内容` |
| 截断工具调用 | `模型输出被截断（finish_reason={reason}），此工具调用的参数可能不完整，已拒绝执行。请重新发起该调用` |
| shell 退出码标记 | `[exit code: N]`（输出末尾） |

这些文案照抄 Go（进自建 L3 场景测试断言；语义等价的小差异允许，须在 PR 里说明）。理由（Go 侧 ARCHITECTURE §3.9）：文案差异会导致模型行为差异——「不许绕」那句缺一个字，模型就会拆解命令绕过规则。**这不是措辞偏好，是实测教训，所以照抄而不是重写。**

### 4.4 JSON 口径（与 Go 对齐的两条保留项）

> 本节原为「跨语言逐字节对拍口径」；定位放宽后对拍已卸掉，这里只保留两条与 Go 对齐的**理由**——它们决定 Java 版 JSONL 日志的形态正确性，与对拍无关。

JSONL 日志形态与 Go 对齐的两条口径：

1. **`usage.details` 的空壳省略规则按 Go**：Go 用自定义 `MarshalJSON` 省略空 details（`omitempty` 对结构体无效）；Jackson 的 `NON_EMPTY` 对 POJO **不生效** → Java 必须**手写 Serializer**（§5.5）。
2. **`time` 字段**：Go `time.Time` JSON 是 RFC3339 带本地偏移与纳秒；Java `Instant` 默认是 UTC `Z`。这是**格式差异不是语义差异**，消费端一律解析成 instant 后使用（若将来与 Go 互操作，这也是对拍时的归一口径）。

**另一个必须显式对齐的点：JSON 的 `omitempty` 语义。**

Go 的 `omitempty` 对 `int` 的 0 也会省略（`turn:0` 不出现、`tokensBefore:0` 不出现）。Jackson 的 `Include.NON_EMPTY` 对数字 **不**省略 0；`NON_DEFAULT` 会对 POJO 用「默认构造实例」比较（语义模糊）。因此 Java 侧的 `Event` / `ToolResult` / `Approval` / `Compaction` **需要手写 Jackson Serializer 或逐字段 `@JsonInclude(Include.NON_DEFAULT)`**，并用守护测试锁住（「`turn=0` 的事件不含 `turn` 键」）。这不是可选优化——否则 Java 版自己的 JSONL 日志会拖着 Go 版没有的空键噪声，读日志、写消费端都会被它烦。

### 4.5 关键接口签名（Java 侧草案）

```java
// mos4j-llm
public interface ChatModel {
    Response chat(Request req) throws IOException;
    Response chatStream(Request req, StreamEmitter emit) throws IOException;   // emit 可抛错以中断输出
    Capability capabilities();
}

// mos4j-tool
public interface Tool {
    String name();
    String description();
    Object parameterType();        // 反射源：Class<?> 或 Map（SchemaProvider 形态）
    Object execute(ToolContext ctx, String rawArgs) throws Exception;
}
public interface ConcurrencySafe { boolean concurrencySafe(String rawArgs); }   // 可选接口
public interface Guideline { String guideline(); }                                // 可选接口

// mos4j-execpath
public final class ExecPath {
    public static List<Outcome> executeBatch(CancellationToken token, ToolRegistry reg,
                                              List<ToolCall> calls, ExecOptions opts);
}

// mos4j-session
public interface Persistence {
    void saveHeader(String id, Header h) throws IOException;
    void append(String id, Event ev) throws IOException;   // 失败 → Session.append 抛错，事件不进内存
    List<Event> load(String id) throws IOException;
    void close() throws IOException;
}

// mos4j-hook —— 两个类型互不引用对方
public interface Guard { void check(HookEvent ev) throws GuardDenied; }      // 单调：抛异常即拒绝
public interface Hook  { HookEvent apply(HookEvent ev, Next next); }        // waterfall：next 可不调用（短路）
```

---

## 5. 六处 Java 差异的显式处置

> 每处给出：Go 现状 → Java 不能等价的原因 → 处置 → **降级声明写在哪** → 怎么验证。

### 5.1 取消传播（`context.Context` → `CancellationToken`）

**Go 现状**：`ctx` 贯穿全部 API，是取消与超时的唯一载体（`llm/retry.go` 的「取消是终止性的」、`sandbox` 的超时、`execpath` 的「取消不留空洞」全靠它）。

**Java 不能等价的三个原因**：

1. 无 `context.Context` 等价物；Java 的 `Future.cancel` 只覆盖 `Future` 场景，不覆盖「传遍全栈的取消位 + deadline」。
2. `ThreadLocal` **不可靠**：取消位需要跨线程可见（虚拟线程上 `ThreadLocal` 按线程隔离，从父线程看不到子线程的状态）；`InheritableThreadLocal` 在虚拟线程池下也不可靠。
3. `ScopedValue`（JDK 25 仍是预览 API）语义上是不可变绑定，**不适合表达「可变取消位」**，且预览 API 不适合公共 SDK 契约。

**处置**：

```java
public interface CancellationToken {
    boolean isCancelled();
    CancellationReason reason();     // CANCELLED / DEADLINE_EXCEEDED
    Instant deadline();               // 可为 null
    default void throwIfCancelled() { ... }   // 抛 CancellationException
    static CancellationToken none();
}
public final class CancellationTokenSource {   // cancel() / cancelAfter(Duration) / token() }
```

- **显式传参**：每个可阻塞点（LLM 调用、工具执行、审批等待、进程等待、文件 IO）签名里带 token。
- **取消 ≠ 超时**：Go 侧 `ctx.Err()` 区分 `Canceled` 与 `DeadlineExceeded`，重试逻辑依赖这个区分（「取消是终止性的」「退避等待中 ctx 结束则直接返回」）。Java 侧 `CancellationException` 必须携带原因枚举。
- **阻塞审批用虚拟线程**：`Executors.newVirtualThreadPerTaskExecutor()` 起一个等待线程 + `CompletableFuture`，审批应答方回调完成 future。Go 里阻塞一个 goroutine，Java 这点更自然。
- **「取消不留空洞」**：取消时未派发的调用补记合成失败结果（与 Go 同文案：`调用在派发前被取消（上下文已结束）`）。

**降级声明写在哪**：

- `CancellationToken` 的 Javadoc 顶部；
- `mos4j-core` 的 `Describe()` 快照里写明「取消靠显式检查，无编译期保证」；
- 一份**「可阻塞点清单」**（§7 的合入门）：每个可阻塞点必须有对应的取消测试。

**怎么验证**：每个可阻塞点一个测试——「token 在进入前取消 → 立刻返回 CancellationException，不发起调用」。

### 5.2 进程组击杀（`setpgid` → `ProcessGroupKiller`）

**Go 现状**：`sandbox/sandbox.go` 用 `syscall.SysProcAttr{Setpgid: true}` 起新进程组，超时 `syscall.Kill(-pid, SIGKILL)` **整组**杀，孤儿进程一起死。

**Java 不能等价的原因**：JDK 没有 `setpgid`；`ProcessBuilder` 无法设置进程组；`ProcessHandle.descendants()` 快照 + 递归 `destroyForcibly()` **非原子**——进程可能在快照与 kill 之间 fork 出新的孙子进程。

**处置**：

```java
public interface ProcessGroupKiller {
    void kill(Process process);        // 尽力而为
    String description();              // 写进 Describe() 快照，让部署方看得见强度
}
```

- **默认实现 `DescendantsKiller`**（纯 Java、跨平台）：`process.toHandle().descendants().toList()` 快照 → 递归 `destroyForcibly()`。**文档与快照明写「尽力而为，非原子」**。
- **可选实现 `NativeKiller`**（JNA 调 libc `setpgid(2)` / `kill(-pgid, SIGKILL)`）：放在**可选模块**（`mos4j-sandbox` 的 optional 依赖，或独立 `mos4j-sandbox-native`），**默认不装配**。JNA 是新增依赖 → 与「近零依赖」冲突，必须显式 opt-in。
- **绝不做的事**（MoS 纪律：「假装有边界比没有边界更危险」）：不在无强制后端时假装已隔离。`Describe()` 必须写明用的是哪个 killer、强度如何。

**降级声明写在哪**：`sandbox` 包 Javadoc + `Describe()` 快照 + README 的「诚实的边界」章节。

**怎么验证**：起一个会 fork 孤儿进程的脚本 → 超时后断言主进程被杀（**不断言孙子进程一定死**——那是尽力而为的边界，断言它就是在假装等价）。

### 5.3 文件版本令牌（`dev:ino:size:mtimeNs:ctimeNs` → 降级）

**Go 现状**：`tool/builtin/observe_version_unix.go` 用 `syscall.Stat_t` 取 `Dev`/`Ino`/`Ctim`，拼成令牌做 CAS。

**Java 不能等价的原因**：NIO 不暴露 ctime（inode 变更时间）。

**处置**（按方案文档给定的口径，写进文档）：

```java
// Unix：优先用标准 NIO 扩展属性（公开 API，不是 sun.nio.fs 内部类）
long dev  = (Long) Files.getAttribute(path, "unix:dev");
long ino  = (Long) Files.getAttribute(path, "unix:ino");
long size = attrs.size();
long mtimeNs = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
String token = dev + ":" + ino + ":" + size + ":" + mtimeNs;
```

- **不反射 `sun.nio.fs.UnixFileKey`**：`Files.getAttribute(path, "unix:dev")` 是**公开的标准扩展属性**，不需要 `--add-opens`，也不会因模块化失效。非 Unix 平台该属性抛 `UnsupportedOperationException` → 降级为 `size:mtimeNs`（与 Go 的非 Unix 退化**完全一致**）。
- **影响面**（写进文档）：ctime 捕获的是「文件被替换但 size+mtime 恰好相同」的极窄场景；dev+ino 已覆盖「删了重建同名文件」。可接受。
- **不用 `stat` 子进程**：每次 write/edit 多一次进程，代价不划算。

**怎么验证**：同 token 判定测试（写→token 变；改内容→token 变；删除重建→dev+ino 变）。

### 5.4 Schema 生成（struct tag → 注解 + 反射）

**Go 现状**：`tool/tool.go:structToSchema` 用 `json:"path"` + `mos:"desc=...,required=true"` 双 tag 反射，**注册时缓存**（FR-C1：循环内零反射）。

**Java 不能等价的原因**：Java 反射读取**用户自定义类的字段**需要 `setAccessible(true)`；JDK 17+ 模块系统下对外部 jar 可能失败。

**处置**：

```java
@Retention(RUNTIME) @Target(FIELD)
public @interface ToolParam {
    String desc() default "";
    boolean required() default false;      // 只由显式声明决定
}
```

- 字段名：优先 `@JsonProperty("path")`，否则用 Java 字段名（对位 Go 的「json tag 只取字段名，omitempty 不参与必填判定」）。
- **`required` 只由 `@ToolParam(required=true)` 决定**——**不按「非基本类型且无默认」推断**（Go 侧明确拒绝这种推断：会让模型被强制补齐带零值语义的参数）。
- **注册时缓存**：`Registry.register(tool)` 时生成 schema 存入 map，`List()` 取缓存（FR-C1 不变式保持）。
- **反射失败 fail-closed**：无法反射时**抛错、注册失败**，**不许静默生成空 schema**。错误信息要指明「工具参数类需要放在未命名模块（classpath 目录）或显式提供 SchemaProvider」。
- **并行路径**：工具可直接给 JSON Schema（`Map<String,Object>`）——Go 侧 MCP 用了 `tool.SchemaProvider`，Java 侧原生支持（Map 传参无需反射）。
- **不选注解处理器（APT）**：增加构建复杂度，而注册时缓存已把反射成本摊销到一次。

**怎么验证**：守护测试——① 生成结果无 `title` / `$defs`（FR-C2）；② 连续两轮 `List()` 不再反射（FR-C1，用计数或基准）；③ 反射失败 → 注册抛错（fail-closed）。

### 5.5 协议层（moproto 全量 → 按调用面裁剪）

**问题**：`moproto` 是只读外部 Go 仓库（16k 行），Java 版无法复用；全量复刻是明确的「不做」项。

**处置**：只写三协议 DTO + usage 归一化（≈1.5–2.5k 行 Java）。

**必须原样保住的四件事**：

1. **流完整性按结束标记判定**（§2 机制 5）：Anthropic `message_stop` / OpenAI `[DONE]` / Gemini `finishReason`（更严，宿主可用 `require_finish=false` 关）。结束原因只是附加信息。**结束原因为空 ≠ 截断**。
2. **Anthropic 的 usage 分两处到达**：`message_start.message.usage` 给输入侧，`message_delta.usage` 给最终快照。**两侧都读、逐字段合并**，且**方向不能反**（实测：某些网关的 `message_start.cache_read_input_tokens` 报的是总输入而非命中子集 → 必须 delta 赢、start 只补 delta 省略的字段）。
3. **OpenAI 流式 `tool_calls.arguments` 是分片 JSON 字符串**，必须**按 index 累加后再解析**。Java 实现：`TreeMap<Integer, StreamToolCall>`（id/name/args 累加器），流结束时**按 index 顺序**输出完整调用。
4. **`injectReasoningContent`**（OpenAI 请求体按字节注入 `reasoning_content`）：Java 用 Jackson `ObjectNode` 定点替换即可（比 Go 的字节级重编码更简单，但**注入位置与 Go 一致**）。

**其他对齐点**：

- Jackson 默认不转义 HTML → 对齐 Go 的 `SetEscapeHTML(false)`（**优势**：JSONL 里 `&&` 不再是 `\u0026\u0026`）。
- **手写 Serializer**（§4.4）：`Usage` / `UsageDetails` 的空壳省略规则必须与 Go 的自定义 `MarshalJSON` 一致。
- SSE 解析：Anthropic 用 `BufferedReader.readLine()` 逐行解析 `data:` 前缀（Go 用 `bufio.Scanner`，**注意 Java `readLine()` 去掉行分隔符、Go `Scanner.Text()` 也去掉 → 一致**）。
- 不引入 OpenAI/Anthropic 官方 Java SDK（依赖纪律 + 官方 SDK 的流式行为与我们的 fail-closed 判定冲突）。

### 5.6 正则（RE2 → RE2J）

**Go 现状**：`grep` 工具用 `regexp.Compile`（RE2，无回溯、无环）。

**Java 不能等价的原因**：`java.util.regex` 是**回溯引擎**，而 `grep` 的 pattern **来自模型 = 不可信输入** → 病态 pattern 可致 ReDoS（长时间占用一个线程）。JDK 25 没有正则超时。

**处置**：**必须**用 `com.google.re2j:re2j`（RE2 的 Java 移植），**不许用 `java.util.regex`**。依赖纪律的例外理由写进文档：「模型输入的正则是攻击面，不可用 JDK 回溯引擎」。

- 非法正则**直接报错**（不静默当字面量）。
- RE2J 不支持 lookahead/backreference → 若 `grep` 语义依赖它们需换实现（当前 Go 版也没有，RE2 同样不支持 → 天然对齐）。
- 版本：待核实 Maven Central 最新版（写 `1.8`，J0 时确认）。

### 5.7 汇总：降级声明清单（都要写进文档，不许沉默）

| # | 差异 | 降级强度 | 声明位置 |
|---|---|---|---|
| 1 | 取消传播 | 语义等价，**但无编译期保证**（靠清单 + 测试） | Javadoc + `Describe()` + 可阻塞点清单 |
| 2 | 进程组击杀 | **弱于 Go**（尽力而为，非原子） | sandbox Javadoc + `Describe()`（写明 killer 实现） |
| 3 | 版本令牌 | **弱于 Go**（无 ctime） | observe 包 Javadoc + 文档 |
| 4 | Schema 生成 | 语义等价，**反射可能受模块化限制**（fail-closed 报错） | tool 包 Javadoc + 文档（工具类放 classpath 未命名模块） |
| 5 | 协议层 | 语义等价（按调用面裁剪，**不复刻 moproto 全量**） | 文档 |
| 6 | 正则 | **更强**（RE2J ≈ RE2） | 依赖说明 |

---

## 6. J0：契约冻结 + 自建语料（不可跳过）

### 6.1 J0 交付物清单

> 按 §0.0② 的定位放宽：不再产出 Go 基线、不写导出器；语料是 **Java 侧自建测试**的输入，期望值 Java 自算。

| # | 交付物 | 形态 | 验收 |
|---|---|---|---|
| D1 | 不变式与机制契约 | `docs/contract/INVARIANTS.md`（§1+§2 的定稿版，每条带「不这么做会出什么事」+ 验证手段） | 七条 + 十条全覆盖，逐条有验证方式 |
| D2 | 接口契约 | `docs/contract/API.md`（§4 的定稿版：事件字段名表、关键类型签名、文案表） | 字段名与 Go 抽取清单一致（静态清单，见 §6.3） |
| D3 | L1 协议语料 | `src/test/resources/llm/<protocol>-<case>.txt`（SSE 报文**照抄 Go 测试字面量**）+ Java 侧断言 | 回放产出与 Java 侧手写期望一致（期望值从 Go 的断言注释抄语义） |
| D4 | L2 会话场景测试 | Java 侧自建：构造含压缩 / 审批 / 孤儿 turn / 孤儿 tool/call 的事件序列，断言 `messages()` 结果 | 场景覆盖 §6.2 的 L2 清单，场景断言与 Go 的行为描述一致 |
| D5 | L3 决策场景测试 | Java 侧自建：构造工具调用序列 + 守卫/钩子/审批组合，断言 `Outcome` | 场景覆盖 §6.2 的 L3 清单，拒绝文案断言照抄 §4.3 |
| D6 | 不变式测试清单 | `docs/contract/INVARIANT-TESTS.md`（Go 不变式测试 → Java 测试的映射表） | 每条 Go 测试有 Java 对应项（译不过来要写明原因） |

### 6.2 场景清单（L1/L2/L3 的测试覆盖范围）

> 场景设计与 Go 版完全一致——这些场景本身就是 MoS 踩坑记录的固化；差异只在：期望值 Java 自算，不与 Go 基线机器对拍。

**L1（协议场景）**——报文从 Go 测试字面量抄，断言语义从 Go 测试断言抄：

| 用例 | 来源（Go 测试） | 断言点 |
|---|---|---|
| 正常流（有 stop_reason + usage） | `anthropic_test.go:TestChatStream` | content / toolCalls / finishReason / usage |
| **只发 message_stop（无 message_delta）** | `TestChatStreamWithoutMessageDeltaIsComplete` | 流**完整**、finishReason 为空 |
| **真截断（两者皆无）** | `TestChatStreamCutWithoutAnyEndSignalErrors` | 抛 `ErrStreamTruncated` |
| thinking 流 | `TestChatStreamThinking` | reasoning 回传 |
| 工具调用流 | `TestChatStreamToolUse` | toolCalls 完整 |
| usage 分项和 = 总计 | `TestChatStreamUsagePartsSumToTotal` | 不变式 |
| usage delta 赢过 start | `TestChatStreamUsageDeltaWinsOverStart` | 合并方向 |
| OpenAI 工具调用分片累加 | `openai_test.go:TestChatStreamToolCallAggregation` | 按 index 累加 |
| OpenAI 只发 `[DONE]` | `TestChatStreamWithDoneOnlyIsComplete` | 流完整 |
| Gemini 流 | `gemini_test.go` | finishReason 判定 |

**L2（会话场景）**——Java 侧构造事件序列：

| 用例 | 构造 |
|---|---|
| 正常多轮 | user → assistant → tool/call → tool/result → assistant |
| **含压缩事件** | compaction（含 `firstKeptSeq`）→ 投影遮蔽旧历史、注入摘要 |
| **含审批事件** | approval/asked + approval/decided（同 id 配对） |
| **孤儿 turn** | 只写 turn/start 不写 turn/end → `Restore` 合成 `turn/end{interrupted}` |
| **孤儿 tool/call** | assistant 带 tool_calls 但无 tool/result → 投影补合成中断回执（机制 4） |
| 迭代压缩 | 两次 compaction → 旧摘要折叠、不重复出现 |

**L3（决策场景）**——Java 侧构造 `ExecuteBatch` 输入：

| 用例 | 断言 |
|---|---|
| 未知工具 | isError=true，理由含「未知工具」 |
| 守卫拒绝 | isError=true，理由与 §4.3 一致 |
| **载荷冻结违约**（钩子改写 args） | isError=true，理由含「载荷冻结」 |
| **审批人工拒绝** | isError=true，理由含「不要尝试用其他命令或方式达成同一目的」 |
| 无审批通道的 ask | isError=true，理由含「未装配审批通道（fail-closed）」 |
| 参数非法 | isError=true |
| **并发组反序完成** | Outcome 顺序 == 调用顺序（机制 2） |
| 错误只可加重 | 钩子把失败洗成成功 → 仍是 isError（机制 3） |

### 6.3 字段名的静态守护（防手抖）

「字段名照抄 Go」不能靠人眼。J0 要做一次**静态抽取**：

- 脚本从 Go 源码抽取所有 `json:"..."` tag（`session` / `llm` 包），生成 `docs/contract/fieldnames.json`（**一次性冻结**，不随 Go 演进自动重抽）；
- Java 侧的守护测试读取该文件，断言 Jackson 序列化出的键集合**完全一致**（多一个少一个都失败）；
- 字段名变更走 PR 评审（改清单 + 改 DTO 同一 PR）。

这不是跨语言对拍机制（那份已卸），是 Java 版自己的防手抖门——字段名是事件日志与消费端的契约，错了会静默破坏下游。

### 6.4 J0 的验收门

1. `mvn -q verify` 在**空实现**下能跑（构建骨架 + 契约测试 + 场景测试骨架通过）；
2. 字段名守护测试能**发现**故意改错的一个字段名（自测：改一个 → 测试红 → 改回）；
3. D1/D2/D6 三份文档定稿（每条都有验证方式）；
4. L1 报文已从 Go 测试字面量抄入 `src/test/resources/llm/`。

> 原「Go 仓库 `git status` 干净」一项自然消失（不再写导出器）；Go 仓库只读，全程零改动。

---

## 7. 里程碑与验收门

> 原则照 MoS：**每个里程碑是一个可运行的垂直切片**，不是「做完了某层」。每步做完停下汇报。

| 里程碑 | 内容 | 产出 | 验收门 | 估算 |
|---|---|---|---|---|
| **J0** 契约冻结 | D1–D6 | 契约文档 + 自建场景测试 + 字段名静态清单 | §6.4 全部通过 | ~1 周 |
| **J1** 最小垂直切片 | `mos4j-core` + `mos4j-llm`（OpenAI 一协议）+ `mos4j-tool`（Registry + Schema）+ `mos4j-session`（事件 + 投影 + JSONL）+ `mos4j-driver`（ReAct）+ `mos4j-execpath`（**串行**） | 能跑通一个「读文件 → 作答」的完整 turn | **L1 + L2 场景测试通过** | 3–4 周 |
| **J2** 安全纵深 | `mos4j-hook`（HookBus + GuardBus）+ `mos4j-policy` + 审批四档 | 载荷冻结 / 双轨制 / fail-closed 全有测试 | **L3 场景测试通过**（含拒绝文案断言）；Go 不变式测试逐条译过 | 2–3 周 |
| **J3** 文件与沙箱 | `mos4j-fs`（jail 双校验 + 根集合）+ `mos4j-sandbox`（超时/输出上限/ulimit/环境裁剪）+ 写前观察门 | 三类事故有测试覆盖 | 路径越界 / symlink 逃逸 / 丢失更新 三类各有测试；进程组降级写进文档 | 2–3 周 |
| **J4** 上下文工程 | 截断 + 压缩（模型生成摘要、切割点规则）+ 提示词分节装配 + AGENTS.md 发现 | 压缩遮蔽行为正确 | **L2 增加压缩用例后全绿**；切割点永不落在 tool 结果上有测试 | 2 周 |
| **J5** 工具全集与联网 | builtin 十个 + `tool_search`（BM25 + CJK bigram）+ web 三件套（SSRF 四条硬防线） | 工具面规模化行为一致 | 工具行为测试 + SSRF 绕过测试 | 2 周 |
| **J6** 三协议 + 硬化 | Anthropic / Gemini 适配、取消/超时语义、fuzz、性能 | L1 全场景通过 | L1 全绿；取消/超时无泄漏；无线程泄漏 | 2–3 周 |
| **按需** | Spring Boot starter（首个真实 Spring 宿主需求出现再做，§0.0①） | `mos4j-spring-boot-starter` | starter 能自动装配跑通一个 turn | ~1.5 人日 |
| **合计** | | | | **~14–16 周（1 人）** |

**每个里程碑的固定汇报格式**：做了什么 / 没做什么 / 卡在哪 / 下一步。

**J1 过不了就不做 J2**——J1 验证的是「模型能不能被正确驱动」，不是代码量。

### 7.1 依赖纪律检查（每里程碑合入门）

- `mvn dependency:tree` 审计：运行时依赖只有 `jackson-databind` / `jackson-annotations` / `re2j`；
- 禁止反向依赖检查（用 ArchUnit 或简单的包依赖扫描）：能力层不得 import 驱动层/装配层；
- **「日志与协议同形」检查**：`mos4j-session` 的事件 DTO 与 `mos4j-llm` 的值类型是**同一批类**（不是两套 DTO）。

---

## 8. 风险登记

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | 取消靠纪律不靠类型 → 漏检查导致任务不中断 | 高 | 高 | 可阻塞点清单 + 每点一个取消测试 + PR 模板勾选 |
| R2 | 进程组降级 → 超时后孤儿进程存活 | 中 | 中 | `ProcessGroupKiller` 双实现 + `Describe()` 明写强度；需要严格时用 JNA 实现（opt-in） |
| R3 | 与 Go 版语义漂移（无人发现） | 中 | 中 | 场景测试从 Go 踩坑记录固化；字段名静态守护；「语义对齐 MoS」进 PR 模板。触发回补条件见 §0.0② |
| R4 | 协议层重写遗漏边角（分片累加 / usage 口径 / 结束标记） | 中 | 高 | L1 场景覆盖真实抓包；「分项和 = 总计」不变式测试 |
| R5 | Java 反射在模块化下失败 → Schema 生成退化 | 中 | 中 | fail-closed 报错（不静默空 schema）+ 支持直接给 Schema（Map） |
| R6 | JVM 启动/分发体验劣于单二进制 | 高 | 低 | 胖 jar + `jlink`/`jpackage` 运行时镜像；接受退化 |
| R7 | 范围蔓延（把 M3 多智能体 / MCP / Skills 一起做） | 中 | 中 | 按 §7 切分；M3+ 单独立项 |
| R8 | 拒绝文案与 Go 不一致 → 模型行为分叉 | 中 | 中 | 文案照抄 §4.3；变更走 PR 评审 |
| R9 | RE2J 依赖与「近零依赖」纪律冲突 | 低 | 低 | 记录例外理由（模型输入的正则是攻击面） |
| R10 | Jackson 的 `omitempty` 语义与 Go 不一致 → JSONL 出现空键噪声 | **高** | 中 | §4.4 已识别；用手写 Serializer + 守护测试锁住 |
| R11 | 与 Go 版长期分叉后才被发现（如将来需要互操作） | 中 | 中 | 契约文档 + 字段名冻结使回补对拍是增补不是重构（触发条件见 §0.0②） |

---

## 9. 明确不做（写进 Java 版产品文档）

| 不做 | 理由 | 替代 |
|---|---|---|
| 逐行翻译 Go 代码 | 翻译会继承 Go 的机制假设（ctx 传遍全栈、goroutine 编排），丢掉 Java 的表达优势 | 照契约重写 |
| 复刻 `moproto` 全量（16k 行） | MoS 实际只消费三协议 DTO + usage 归一 | 按实际调用面裁剪 |
| SDK 层引入 Spring / 数据库 / ORM | SDK 要能被非 Spring 宿主使用 | Spring 只在 `mos4j-spring-boot-starter`；持久化是 `Persistence` 接口 |
| 用 JDK 默认正则引擎 | ReDoS 风险，pattern 来自模型 | RE2J |
| 放宽任何 fail-closed 分支 | 纪律是 MoS 的核心资产 | —— |
| 修改 Go 仓库任何文件 | 只读；发现缺陷写成建议交给你（参考实现也不反哺 Go） | — |
| 在无具体 Spring 宿主需求前做 starter | 「能力即证据」：没有消费者的装配层是债务（MoS 治理规则同款） | 按需清单：首个 Spring 宿主需求出现再做（~1.5 人日） |
| M3+ 功能（多智能体编排 / MCP / 插件化 / Skills） | 不在本次范围 | 后续单独立项（Go 侧同样是 M3 待建） |
| 「假装有边界」（进程组无强制后端却声称已隔离） | MoS 纪律：假装有边界比没有边界更危险 | `Describe()` 写明强度 + 文档 |

---

## 10. 剩余待确认事项（不阻塞 J0 启动）

**已决**：驱动力 = 参考 MoS 的实现，用 Java 重新写一份 → **路线 A**（§0.2）；交付形态 = **SDK 本体 + starter 按需**；验收 = **参考实现 + 自建场景测试**，无跨语言对拍（§0.0）。

以下不阻塞 J0 启动，可并行推进时定：

1. 并行度：1 人（~14–16 周）还是 2 人——影响 J1 之后的排法。
2. `agent/approval` 与 `planner` 的归置（§3.2 的建议 vs 独立模块）。
3. 目标 JDK：**已定 `--release 25`**（虚拟线程正式版；本机 JDK 25 直接编译）。
   —— 注意：JDK 25 下 `StructuredTaskScope` 仍是预览 API，故 §3.3 仍用「虚拟线程 + Semaphore」而非 StructuredTaskScope。

**下一步**：开 **J0**（任务列表见 `mos4j-tasks.md` 的 T0.x），第一步是把 §1/§2 的契约定稿成 `docs/contract/INVARIANTS.md`，然后把 L1 报文从 Go 测试字面量抄入测试资源。
