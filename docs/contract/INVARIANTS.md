# INVARIANTS — MoS4J 七条不变式契约（J0 · D1）

> **地位**：本文件是 MoS4J 的合入门。每个 PR 必须能回答「本次改动触及哪条不变式」；任何与本文冲突的实现都是 bug，不是风格问题。
> **来源**：参考 MoS（Go 版 `/Users/puras/workspace/proj/hegq/mos`）的踩坑记录，语义照抄、实现形态 Java 自主。
> **使用方式**：每条不变式 = 陈述 + 违反后果 + 验证方式 + Java 落点。验证方式必须在 `mos4j-*` 测试中有对应实现（映射表见 T0.9）。
> **状态**：冻结。修订需维护者评审（PR 勾选触及的不变式编号）。

---

## I1 fail-closed（缺省拒绝）

**陈述**：任何安全/治理能力（守卫、审批通道、沙箱、压缩器）**未装配时，走拒绝或显式缺失路径，绝不静默放行**。能力位的缺省值是「关」，不是「不设防」。

**违反后果**：宿主忘了装配审批通道 → ask 类工具调用被静默放行执行——这是最高危事故形态（危险操作没有拦）；忘了装配沙箱 → shell 直接跑在宿主进程权限上。

**Java 落点**：
- 所有能力位 getter 未装配时返回 `Optional.empty()` 或抛专用 `NotConfigured` 异常，**绝不用 `null` 让调用方自己判断**；
- `execpath` 预检：guard 拒绝 / hook 拒绝 / ask 且无审批通道 → 一律以 `isError=true` 回填，文案见 `API.md` §文案表；
- 语言纪律：Java 的「可选参数 + 重载」容易把 fail-closed 写成「默认放行分支」，禁止。

**验证**：J2 场景——不装配审批通道发起 ask 工具 → 断言 `isError=true` 且理由含「未装配审批通道（fail-closed）」。

---

## I2 日志即事实（append-only）

**陈述**：事件日志是**唯一事实记录**，只追加、不改写、不删除。进程崩溃留下的「洞」（无回执的 tool/call）就是发生过的事实，投影层负责自愈（见机制 M4），**日志层不修复**。

**违反后果**：日志被改写后，「当时发生了什么」无从追查；审计（I3）失去凭据；投影自愈与审计各管各的边界被打破。

**Java 落点**：`mos4j-session` 的 `Session.append(ev)` 是唯一写入口，`seq` 由 Session 统一分配（全局递增从 1）；任何代码不得修改已追加的事件；JSONL 落盘只 append。

**验证**：J1 场景——append 后篡改返回的 Event 副本 → 断言日志内事件不变；崩溃模拟（append tool/call 后不 append result）→ 日志文件里洞仍在。

---

## I3 审计不变式（错误只可加重不可减轻）

**陈述**：后置钩子可以把成功**改**成失败（发现问题就报错），但**不能把失败洗成成功**。错误一旦发生，就是事实；审计日志不能被改写成成功。

**违反后果**：钩子「好心」吞掉错误 → 模型以为操作成功 → 基于错误状态继续决策；事后审计看到的是被美化过的历史。

**Java 落点**：`mos4j-agent` finalize 阶段单向判定：`if (!outcome.isError && out.err != null) { content = err消息; isError = true; }`——只允许这个方向。

**验证**：J2 场景——post-execute 钩子返回成功内容但 err 非空 / 试图把 isError 改回 false → 断言回执 `isError=true`。

---

## I4 上下文是稀缺资源

**陈述**：上下文窗口按预算管理：token 估算给上限保护；压缩（compaction）按预算触发；**事件目录等治理性元数据走预算制**，不无限膨胀；投影给模型的历史永远 ≤ 预算。

**违反后果**：长会话把上下文撑爆 → 请求被服务商拒绝或费用失控；治理事件（审批问答等）混进投影挤占业务上下文。

**Java 落点**：`mos4j-session` 的预算估算 + 压缩触发；`approval/*` 事件**不走预算制**（PRD 点名全量落日志，但投影不收）；压缩摘要以 `[历史摘要]` 前缀注入 user 角色，切割点永不落在 tool 结果上（见机制 M8 关联）。

**验证**：J4 场景——构造超预算历史 → 断言压缩触发、切割点合法、`firstKeptSeq` 之后的连续性保持。

---

## I5 Hook / Guard 双轨制

**陈述**：**守卫（Guard）是安全边界，钩子（Hook）是扩展点**，二者结构上不可互替：
- Guard：纯判定、无副作用假设、**只跑一次**、看不到也不需要「改写后的载荷」（载荷冻结保证它看到的就是最终载荷）；
- Hook：可编排（链式 `next`）、可观察、受载荷冻结约束（改写工具名/参数/CallID 即违约）；
- **钩子无法绕过守卫**：这是类型层面的结构性保证（守卫在编排点内侧，钩子的 `next` 链到不了守卫之前），不靠纪律约定。

**违反后果**：钩子能改载荷 → 守卫判定失效（见机制 M1）；钩子能绕守卫 → 「安全策略」沦为可协商的建议。

**Java 落点**：`mos4j-tool` 的 `GuardBus` / `HookBus` 分型；`mos4j-agent` 编排顺序：guard → hook（链）→ 冻结检查 → 执行 → post hook → finalize。

**验证**：J2 场景——钩子链中试图跳过守卫 / 改写载荷 → 均被拒；守卫对同一调用只触发一次（副作用计数测试）。

---

## I6 零开销降级

**陈述**：可选能力（思维链回传、多模态、压缩、落盘 spill）未启用时**零额外开销**：不多一次拷贝、不多一层包装、不因「可能有」而常驻对象。

**违反后果**：所有用户为少数人用的能力付常数开销；热路径（每 token 事件）被无谓分配拖慢。

**Java 落点**：能力位用 `Optional` / 空实现单例（而非每次 new 包装器）；流式路径上未订阅 reasoning 时零复制；spill 目录未配置时截断 note 走「不落盘」分支（文案见 `API.md`）。

**验证**：J6 性能场景——未启用可选能力时关键路径无额外分配（简单断言：note 文案走「请改用分次读取…」分支且无落盘尝试）。

---

## I7 依赖纪律

**陈述**：SDK 模块（`mos4j-core` / `mos4j-model` / `mos4j-runtime` / `mos4j-tool` / `mos4j-session` / `mos4j-agent`）的**运行时依赖只有 4 类：Jackson 家族（databind/core/annotations/dataformat-yaml）、RE2J、SnakeYAML 系（只经由 `jackson-dataformat-yaml` 传递引入，用于 YAML frontmatter 解析）、（JDK 自带）**。Spring / Guava / 数据库 / ORM / 日志门面不进 SDK；Spring 只允许出现在 `mos4j-spring-boot-starter`（按需，见任务列表 §10）。

> **修订（J7 / B16）**：原条文写「只有 3 个：Jackson、RE2J、JDK」。J7 要解析技能 frontmatter（YAML），选了成熟 YAML 方案（Jackson 的 `jackson-dataformat-yaml`）而不是手写 YAML 子集——手写子集会变成第二套解析真相，且 YAML 的坑（布尔字面量、标签、锚点）远比 frontmatter 需要的子集多。因此运行时依赖面增加 SnakeYAML 系（`org.snakeyaml:snakeyaml-engine`，由 YAML 模块传递引入，不直接用其 API）。**机械门不动**：Enforcer 的 `bannedDependencies` 列表不变（SnakeYAML 本来就不在禁令里），C2b/C6 照旧。

**违反后果**：「SDK 优先」的交付形态被污染：非 Spring 宿主被迫拖入 Spring；依赖树膨胀到无法审计。

**Java 落点**：CI 双拦截——依赖白名单（`mvn dependency:analyze` + Enforcer）+ 源码扫描（SDK 模块不得 import `org.springframework.*`；正则一律 RE2J，不得 import `java.util.regex`）。

**验证**：CI 门 C2/C6（见 `mos4j-execution.md` §5.2）；G6 复核运行时依赖仍只有 Jackson 家族 / RE2J / SnakeYAML 系 / JDK。

---

## 与十条机制的关系

不变式是「为什么」，机制（`MECHANISMS.md`）是「怎么做」。映射：I1↔M1/M6、I2↔M4、I3↔M3、I4↔M8、I5↔M1/M2、I6↔M5/M7、I7↔字段名清单（`fieldnames.json`）。冲突时不变式优先。
