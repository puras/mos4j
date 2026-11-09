# CONTRIBUTING — MoS4J 贡献指南

> 一句话：**MoS4J 是参考 MoS 的语义重写**。你的工作不是「发明更好的做法」，是把 MoS 用踩坑换来的语义在 Java 里忠实落地。

## 1. 开始之前（必读）

按顺序读三份契约（都在 `docs/contract/`）：

1. `INVARIANTS.md` — 七条不变式：**合入门**。每条写明「不这么做会出什么事」；
2. `MECHANISMS.md` — 十个机制：不变式的代码落法；
3. `API.md` + `fieldnames.json` — 字段名与文案契约：**照抄，不是参考**。

遇到「拿不准怎么实现」：查 `docs/mos4j-execution.md` §6 的决策树；都没有 → 走自创机制登记（见 §4）。

## 2. 硬规矩（违反 = PR 直接拒）

| 规矩 | 为什么 |
|---|---|
| 事件字段名只从 `net.hegq.mos4j.core.FieldNames` 引用 | 手写字符串会手抖；消费端读到空字段而不是错误 |
| `ToolCall.args` 保持 String 原始 JSON | 载荷冻结（M1）要逐字比对；解析再序列化会改字节序 |
| SDK 模块不 import Spring / Guava / DB / ORM / 日志门面 | I7 依赖纪律；CI 门 C2/C6 会拦 |
| 正则一律 RE2J，禁 `java.util.regex` | RE2 线性时间；CI 门 C6 会拦 |
| 能力位未装配返回 `Optional.empty()`，不用 null | I1 fail-closed；null 让调用方漏判 = 静默放行 |
| 审批拒绝文案不改写（尤其「不许绕」半句） | 实测缺半句模型会拆解命令绕过规则（M10） |
| 不放宽任何 fail-closed 分支 | 「这里降级一下更友好」→ 停下，写建议 |

## 3. 交付形态

- **SDK 是本体**（`mos4j-*` 6 个模块）；`mos4j-spring-boot-starter` 在按需清单（`docs/mos4j-tasks.md` §10），没有真实 Spring 宿主需求不动它。
- 修改事件字段 / 公共 API → **两人评审**（含维护者）+ 同步 `docs/contract/`。
- 每个 PR 用 `.github/pull_request_template.md`，全不适用写 N/A，不留空。

## 4. 自创机制登记

MoS、PI/DSH 都没有等价物，而 Java 侧必须做的东西（例如 `CancellationToken`）：

1. 写清：MoS 怎么做 + 为什么 Java 不能照做；
2. 写清：Java 侧怎么做 + 代价；
3. 写清：**这是自创** → 登记 `docs/contract/SELF-INVENTED.md`；
4. 自创机制**不许放宽任何 fail-closed 分支**；
5. PR 里**单独列出**，让评审者重点看；
6. 评审表在 `docs/contract/REVIEW-SHEET.md`：§4 逐条对应上表的 SI 编号（评审时填「接受 / 需改 / 需讨论」），§1 填本次改动触及的不变式编号。

## 5. 测试纪律

- 每条不变式/机制有对应测试（映射表 `docs/contract/INVARIANT-TEST-MAP.md`，G2 门查空项）；
- 场景测试的期望值写在 Java 测试里；L1 报文照抄 `src/test/resources/model/`（改报文 = 改语料，须说明依据）；
- 提交前 `mvn -q verify` 必须绿；守护测试红了先查是不是你改了契约，不是就修实现；
- 评审表里引用的测试名必须是**真实存在的 `类.方法`**——写完自己核一遍（`grep -r "void <方法>("`），评审人不会替你追假名字。

## 6. 提交信息

`<模块>: <做了什么>（触及 <不变式/机制编号>）`

例：`mos4j-agent: 载荷冻结检查（M1/I5）——钩子改写 args 即拒绝`
