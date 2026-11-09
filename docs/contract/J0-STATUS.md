# J0 推进状态

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 更新：2026-11-03（**J2 期间回头补注**：本文只记 J0 当时的快照；J1 / J2 的现状分别见 [`J1-STATUS.md`](J1-STATUS.md) / [`J2-STATUS.md`](J2-STATUS.md)）。
> J0 = 契约冻结 + 自建语料（任务列表 §2；B1 已在上阶段完成——Go 源码精读已沉淀进三份方案文档）。

## 已完成

| 任务 | 产出 | 状态 |
|---|---|---|
| T0.1 七条不变式 | `docs/contract/INVARIANTS.md`（I1–I7：陈述/后果/Java 落点/验证） | ✅ |
| T0.2 十个机制 | `docs/contract/MECHANISMS.md`（M1–M10 + 机制×不变式×里程碑总览） | ✅ |
| T0.3 接口契约 | `docs/contract/API.md`（事件种类/字段名/签名/文案表/六差异对位） | ✅ |
| T0.4 字段名冻结 | `docs/contract/fieldnames.json`（75 tag / 68 名，sha256 记录在清单内）+ `tools/fieldname-extract/extract.py` | ✅ |
| T0.5 L1 场景 | `docs/contract/L1-SCENARIOS.md`（22 条）+ **19 份 .sse 资源（73 条 data 行，全部与 Go 测试字面量逐字一致，机械核对 73/73）** + 场景测试骨架（`@Disabled` 待 J1）+ 语料完整性测试（已点亮） | ✅ |
| T0.6 L2 场景 | `docs/contract/L2-SCENARIOS.md`（16 条，Java 自建） | ✅ |
| T0.7 L3 场景 | `docs/contract/L3-SCENARIOS.md`（21 条，Java 自建，文案断言→API.md §4） | ✅ |
| T0.8 字段名守护 | `FieldNames`（常量锚点）+ `FieldNamesTest` + `FieldNameContractTest`（清单 sha256 完整性 + 常量子集断言）；**故意改错→红→改回→绿 已验证** | ✅ |
| T0.9 映射表 | `docs/contract/INVARIANT-TEST-MAP.md`（I1–I7 / M1–M10 无空项） | ✅ |
| T0.10 构建骨架 | 父 POM + 12 模块 POM（starter 不入构建）+ enforcer 依赖门 + CI（C1/C6 + JDK 25）+ PR 模板 + CONTRIBUTING + README | ✅ |
| T-INF.4 PR 模板 | `.github/pull_request_template.md`（契约勾选 + 自创机制 + 验证结果） | ✅ |
| T-INF.5 贡献指南 | `CONTRIBUTING.md` | ✅ |
| — | `docs/contract/SELF-INVENTED.md`（SI-1~4 自创机制登记） | ✅ |

## J2 期间对本文件的补注（2026-11-05）

| 项 | 变化 |
|---|---|
| T0.9 映射表 | 已按 J1 / J2 的真实测试类名与方法名回填（见 `INVARIANT-TEST-MAP.md`）；新增 `INVARIANT-TESTS.md`（逐条台账） |
| T0.7 L3 场景 | D1 段（L3-1 … L3-10）**已在 J2 全部点亮**；D2 / D3 段属 J3（**J3 期间已点亮**：D2 段 9/9、D3 段 2/2，见 `J3-STATUS.md`） |
| T0.10 构建骨架 | 父 POM 新增 `maven.dependency.plugin.version=3.9.0`（3.7.0 / 3.8.1 的 ASM 不认 class file 69，J2 才发现）；CI 新增 C2b 依赖硬门 |
| SELF-INVENTED | 新增 SI-5（审计接口下沉 core）、SI-6（安全编排顺序写死）、SI-7（`unavailable` 档） |
| 已闭合（2026-11-06） | Go 参考仓库只读授权已获得；**T2.11 已完成**（137 个真实 Go 测试名逐条抄录，机器复核全部存在）；首轮逐项对拍发现三处工具名 + 四处参数形状差异 → 见 `INVARIANT-TESTS.md` §3（**已裁决并落地 B13-1/2/3**；B13-4/5 记为有意差异） |

## 验证记录（真实输出）

- `mvn verify`：**BUILD SUCCESS**（13 reactor 模块；测试 9 通过 / 19 骨架跳过待 J1）。
- G0 机械判定项「故意改错字段名→红」：`TOOL_CALLS` 改 `tool_calls` → `FieldNamesTest` FAILURE、BUILD FAILURE；改回 → 绿。
- L1 语料逐字核对：73/73 data 行与 Go 测试字面量集合一致（python 机械比对）。
- Go 参考仓库只读：未由本工作区改动（`git status` 现存 6 项变更均为他人产物：tutorial.html / release-v0.1.0.md 等，修改时间 14:20 早于本会话 J0 动作）。

## 坐标与工具链（2026-11-03 修订）

- groupId：**`net.hegq`**（原 `io.github.puras`）；Java 包名同步为 **`net.hegq.mos4j.*`**（与 groupId 一致，符合 Maven/Java 惯例与 Central 的 javadoc 路径）。
- 目标 JDK：**25**（`maven.compiler.release=25`；字节码 major version 69 已核）。
- 版本管理：依赖与插件版本号全部收进父 POM `<properties>`（`jackson.version` / `re2j.version` / `junit.version` / `maven.*.plugin.version`），模块 POM 不再出现内联版本号。
- 运行时依赖实测（`dependency:tree -Dscope=runtime`，mos4j-sdk）：`jackson-databind`(+`jackson-core`) / `jackson-annotations` / `re2j`——仍是 I7 的 3 个外部依赖。

## 待办（进 J1 前）

| 项 | 说明 |
|---|---|
| L1 报文 A4 语义核对 | Go 原文注释称「2026-10 事故」；Java 测试骨架已按此语义，实现时对照 `llm.ErrStreamTruncated` 行为 |
| B8 / G0 门人工评审 | 三份契约文档 + 场景清单 + 守护测试的维护者评审（机械项已全绿） |
| 勾选任务清单 | `docs/mos4j-tasks.md` 的 T0.x 复选框待人工评审后勾选 |
| CI 真跑 | `.github/workflows/ci.yml` 为本地形态；推送 GitHub 后验证 C1–C7 |
| O1/O2 报文 `\n\n` 分隔 | OpenAI 用空行分隔事件（Go `strings.Join(…, "\n\n")`）；资源文件已按原文保留，SSE 解析器实现时注意容错两种分隔 |
