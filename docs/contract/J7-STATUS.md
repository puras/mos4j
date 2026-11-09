# J7 状态（权威口径）

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 批次：**B16 / Jackson 3 升级**（J7 的前置）+ **J7 技能机制**（Go `agent/skill` 的 Java 语义重写）。
> 收口日期：2026-11-07。任务分解见 [`../mos4j-tasks.md`](../mos4j-tasks.md) §12（T7.1–T7.11）。
> **不新增模块**：能力落在既有 `mos4j-tool` / `mos4j-core` / `mos4j-session` / `mos4j-context` / `mos4j-driver` / `mos4j-sdk` / `mos4j-spring-boot-starter`，12 模块边界不变。

## 1. 交付物清单

### 1.1 B16 / Jackson 3（前置批次）

| 项 | 内容 |
|---|---|
| 坐标 | `tools.jackson.core:jackson-databind` / `jackson-core` **3.2.4**；`tools.jackson.dataformat:jackson-dataformat-yaml` **3.2.4**；注解仍 `com.fasterxml.jackson.core:jackson-annotations` **2.22** |
| YAML 后端 | `org.snakeyaml:snakeyaml-engine` **3.0.1**（经 YAML 模块传递引入；`mos4j-tool` 显式声明 YAML 依赖，不靠传递） |
| API 迁移 | `JsonMapper.builder()` 取代 `configure`；`ValueSerializer` / `SerializationContext`；`JacksonException`（**RuntimeException**）；`properties()/propertyNames()`；`writeXxxProperty` |
| 行为差异（已接受并钉住） | Jackson 3 默认 `FAIL_ON_TRAILING_TOKENS`：参数 JSON 尾巴多内容现在**报错**（Jackson 2 静默丢尾）。与 Go `json.Unmarshal` 一致 → `BuiltinSearchToolsTest.trailingContentAfterTheObjectIsRejected` |
| I7 条文 | 修订为「Jackson 家族 / RE2J / SnakeYAML 系 / JDK」；**机械门不动**（父 POM `bannedDependencies` 列表不变） |

### 1.2 J7 技能机制

**生产代码（新增）**

| 文件 | 内容 |
|---|---|
| `mos4j-core/.../core/SkillAudit.java` | 唯一新增的 core 公开接口：`activate(name, description, dir)` + `allowedToolsMismatch(name, missingAllowed)`（均 `throws IOException`） |
| `mos4j-tool/.../tool/skill/SkillMetadata.java` | frontmatter 元数据（name / description / whenToUse / disableModelInvocation / allowedTools） |
| `.../skill/Skill.java` | 技能值对象（元数据 + 目录 + 正文路径） |
| `.../skill/SkillReader.java` / `OsSkillReader.java` | I/O 注入点（`listDirs` / `listFiles` / `readFile`）+ 真实文件系统实现 |
| `.../skill/SkillFrontmatter.java` | `---` 切分 + YAML 解析（Jackson YAML → snakeyaml-engine） |
| `.../skill/SkillCatalog.java` | 目录发现、同名先到先得、坏技能诊断、清单渲染（字节预算多级降级）、正文现读、`/name` 展开、`reload()` 原子快照替换、`allowed-tools` 求交（经 `ToolProbe` 探针 + `setToolProbe`）、`Options.limits(int,int)` |
| `.../skill/SkillReloadResult.java` | `added` / `removed` / `updated` / `notice` + `changed()` + `unchanged()` |
| `.../skill/SkillState.java` | **轮级**状态（首次激活 / 已激活集合） |
| `.../skill/SkillTool.java` | 模型可见工具 `skill(name)`：条件注册、仅用户可调用 fail-closed、审计先于返回正文 |
| `.../skill/SkillArgs.java` | 参数解析 |
| `mos4j-session/.../session/SkillEvent.java` | `skill/activate` 与 `skill/allowed-tools-mismatch` 的载荷（**防御性拷贝** `missingAllowed`） |
| `mos4j-session/.../session/SessionSkillAudit.java` | `SkillAudit` 实现：先落盘再返回；`session == null` → `IllegalArgumentException` |

**生产代码（修改）**

| 文件 | 改动 |
|---|---|
| `mos4j-tool/pom.xml` | +`jackson-dataformat-yaml`（显式）+ `mos4j-core`（显式，`SkillAudit` import 后 `dependency:analyze` 报 `Used undeclared` 才补的） |
| `mos4j-tool/.../ToolRegistry.java` | +`visibleNames()`（`skill` 的 `allowed-tools` 求交探针用；返回当前可见工具名快照） |
| `mos4j-session/.../EventKind.java` | +`SKILL_ACTIVATE("skill/activate")`、+`SKILL_ALLOWED_TOOLS_MISMATCH("skill/allowed-tools-mismatch")` |
| `mos4j-session/.../Event.java` | +`skill` 字段（构造器 **15 参**）、Builder |
| `mos4j-session/.../EventJson.java` | +`writeSkill`（顺序：`seq, kind, turn, time, text, reasoning, toolCalls, toolCall, toolResult, usage, reason, approval, skill, compaction, error`） |
| `mos4j-session/.../Session.java` | 两处 `Event` 构造补 `skill` |
| `mos4j-context/.../PromptSections.java` | `CANONICAL_ORDER` 插 `skills` → `base/environment/context/skills/instructions/tools`；+`insert(rendered, name, content)` / `positionOf(name)` |
| `mos4j-driver/.../Config.java` | +`skills` / `skillTool` 字段与 Builder |
| `mos4j-driver/.../RunContext.java` | +`skillTool` 字段与 Builder |
| `mos4j-driver/.../agent/Agent.java` | 装配顺序固定：**先条件注册 `SkillTool`（顺手 `setToolProbe(tools::visibleNames)`）→ 再渲染清单并 `PromptSections.insert` → 再建 `RunContext`**；门面 `skills()` / `reloadSkills()` / `expandSkill(String)`；`Describe` 加技能 feature + caveat |
| `mos4j-driver/.../driver/ReActDriver.java` | 每轮 `session.nextTurn()` 之后：`skillTool.setState(new SkillState())` + `skillTool.setAudit(new SessionSkillAudit(session, turn))` |
| `mos4j-sdk/.../Mos4j.java` | `Builder.skills(Path...)` + `Builder.skillLimits(int,int)`；装配走 `SkillCatalog.load(Options.of(...).limits(...))` |
| `mos4j-spring-boot-starter/.../Mos4jProperties.java` | +`Skills { dirs, maxBodyBytes, maxCatalogBytes }` |
| `mos4j-spring-boot-starter/.../Mos4jAutoConfiguration.java` | 技能接线 + `skillDirs(List<String>, Path workspace)`（**相对路径按 `mos4j.workspace` 解析，不用进程 CWD**） |

**测试（新增 / 扩写）**

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `mos4j-tool/.../tool/skill/SkillCatalogTest.java` | **33** | 目录发现序、裸 `.md` 不算技能、同名先到先得 + 诊断、三类坏技能各一条诊断（缺 frontmatter / YAML 坏 / 字段不合法）、目录不存在只诊断、`name` 缺失回退目录名、BOM、重复键、裸 `~`（对齐 Go）、`disable-model-invocation`、`allowed-tools` 只诊断、清单五级降级、渲染纯函数、正文现读、超预算报错、`/name` 展开、重扫幂等与差异明细、原子替换、digest 只含元数据、`escapeTag` 只转义框架标签 |
| `mos4j-tool/.../tool/skill/SkillFixtures.java` | — | 用模块内 `target/test-tmp`（**不用 JUnit `@TempDir`**：沙箱系统临时目录不可写），真实文件拓扑 |
| `mos4j-tool/.../tool/skill/SkillToolTest.java` | **15** | 正文 + 目录回执、`trim(name)`、未知技能提示「目录可能已更新」、缺 `name` 报错、`disable-model-invocation` fail-closed（不带正文）、审计先于状态变更（审计失败即失败）、激活留痕、mismatch 只诊断（不进模型结果）、mismatch 写失败被吞、无审计器可工作、换轮状态清空、重复激活幂等、正文损坏报错、工具元数据（经 `ToolRegistry` 读 `properties=[name]` / `required=[name]`） |
| `mos4j-tool/.../tool/ToolContractTest.java`（改） | 4 | **14 个**工具名冻结（`skill` 条件注册；测试里用真目录 + 真 `SKILL.md` 装配） |
| `mos4j-session/.../SessionSkillAuditTest.java` | **5** | `activate` 只写元数据、`mismatch` 只写 `missingAllowed`、技能事件不进投影、载荷防御性拷贝、`null` session 抛 `IllegalArgumentException` |
| `mos4j-session/.../EventJsonTest.java`（改） | +2 | `skill/activate` 只序列化元数据；mismatch 序列化 `missingAllowed` 数组、空 `description`/`dir` 省略 |
| `mos4j-context/.../PromptSectionsTest.java`（改） | +7（共 16） | `skills` 位于 `context` 与 `instructions` 之间、`insert` 命中位置 / 未命中追加 / 宿主整串前缀不动 / 空内容原样 / 空段名报错 / 纯函数 / 自定义段殿后 |
| `mos4j-driver/.../SkillFlowTest.java` | **9** | 无技能不注册 + 门面降级、全部 `disable-model-invocation` 不注册但仍可 `/secret`、有可见技能注册 + 提示词插入位置、模型经 ReAct 加载（正文进 `tool/result` 不进系统提示词 + `skill/activate` 事件）、仅用户可调用被拒、两轮各激活一次（turn 1 / turn 2）、`reloadSkills()` 拾新技能（静态提示词不重渲染 + `available_skills` notice）、手势展开（句中不算）、宿主 `ToolRegistry` 复用 |
| `mos4j-sdk/.../Mos4jTest.java`（改） | +3（共 10） | 门面接线（扫到技能 / `describe().tools()` 含 `skill` / 系统提示词含 `deploy` / `/deploy` 展开）、`skillLimits(100, 0)` 让加载报「超预算」、无技能时工具缺席 |
| `mos4j-spring-boot-starter/.../Mos4jAutoConfigurationTest.java`（改） | +3（共 16） | `mos4j.skills.dirs=skills` 按 workspace 解析、目录可选、`max-body-bytes=100` 触发超预算 |

## 2. 最终基线（`clean verify`，本轮重跑）

| 模块 | 通过 | 跳过 | J7 新增 |
|---|---|---|---|
| core | 7 | 0 | — |
| llm | 51 | 0 | — |
| fs | 37 | 0 | — |
| sandbox | 28 | **1**（Seatbelt 环境限制，历史遗留） | — |
| tool | **147** | 0 | **+48**（`SkillCatalogTest` 33 + `SkillToolTest` 15；`ToolContractTest` 升 14 工具，条数不变） |
| session | **50** | 0 | **+7**（`SessionSkillAuditTest` 5 + `EventJsonTest` 2） |
| hook | 23 | 0 | — |
| execpath | 40 | 0 | — |
| context | **61** | 0 | **+8**（全在 `PromptSectionsTest`，现 16 条） |
| driver | **41** | 0 | **+9**（`SkillFlowTest`） |
| policy | 18 | 0 | — |
| sdk | **22** | 0 | **+3**（`Mos4jTest`） |
| **默认 reactor 合计（13 项）** | **525** | **1** | 其余增量为加固/Starter/B13/Jackson 3 批次累计 |
| starter（按需） | **17**（`Mos4jAutoConfigurationTest` 16 + `Mos4jStarterTurnTest` 1） | 0 | **+3**（`Mos4jAutoConfigurationTest`） |
| **含 starter 合计（14 项）** | **542** | **1** | — |

- 默认 reactor `clean verify` → **BUILD SUCCESS**（13 项）；`-Pstarter clean verify` → **BUILD SUCCESS**（14 项）。日志：`.j7log2/verify-default.log` / `.j7log2/verify-starter.log` / `.j7log2/analyze.log`。
- 依赖审计：12 个 SDK 模块 + starter 全 `No dependency problems found`。
- 源码扫描（C6）：12 个 SDK 模块 `src/main` **+ `src/test`** 的 `import org.springframework` = 0、`import java.util.regex` = 0（`SkillCatalogTest` 的计数辅助方法刻意用 `String.indexOf`）；裸 NUL 字节（0x00）= 0。
- 与历史对比：J1 107 → J2 172 → J3 266 → J4 340 → J5 379 → J6 416 → 加固 428 → Starter 430 → 含 Starter 444 → B13 449 → Jackson 3 ~450 → **J7 525（含 starter 542）** → **J8 543（含 starter 560）**。
- **J8 后基线推移**（2026-11-07）：本表是 **J7 时点**的权威记录；J8 改动后的当前基线是 **543 通过 / 6 跳过**（含 starter **560/6**），增量 = `SandboxBoundaryTest` +13（14 → 27）、`LandlockScopeTest` +5（非 Linux 上整类跳过）；原有 1 条跳过（Seatbelt **强制效果**真机用例）保留——profile **语法**层已在本机验证。以 [`J8-STATUS.md`](J8-STATUS.md) §5 为准。

## 3. 契约变更（已获批准并落地）

| # | 变更 | 落地证据 |
|---|---|---|
| 1 | 字段冻结清单 **75/68/`fd7b6c81…` → 80/71/`3d35911d810641c25b8ac0da032ac6f8c1d80dd90c240a4aafe5e95d4714912c`**（只并入 `skill`/`name`/`description`/`dir`/`missingAllowed` 5 条） | `docs/contract/fieldnames.json` 与 `mos4j-core/src/main/resources/contract/fieldnames.json` 两份一致；本轮用脚本重算条数/SHA 复核通过 |
| 2 | 事件 +2：`skill/activate`、`skill/allowed-tools-mismatch` | `EventKind` / `SkillEvent` / `Event.skill` / `EventJson.writeSkill` |
| 3 | 模型可见工具面 **13 → 14**（`skill(name)` **条件注册**） | `ToolContractTest`（14 工具冻结）；`SkillFlowTest`（无技能不注册） |
| 4 | `mos4j-core` +1 公开接口 `SkillAudit` | `SkillAudit` + `SessionSkillAudit` |
| 5 | `PromptSections.CANONICAL_ORDER` 插 `skills` | `PromptSectionsTest`（`CANONICAL_ORDER.subList(0,6)`） |
| 6 | Starter 配置元数据 **20 → 23** | 实测 `spring-configuration-metadata.json` `count=23`，含 `mos4j.skills.dirs` / `.max-body-bytes` / `.max-catalog-bytes` |
| 7 | I7 条文改「Jackson 家族 + SnakeYAML 系」 | `docs/contract/INVARIANTS.md`；机械门（`bannedDependencies`）**未改** |

## 4. 关键语义决定（实现口径，不得擅改）

1. **审计落点 = 方案 A**：`mos4j-core` 出接口、`mos4j-session` 出实现、工具侧持 `AtomicReference<SkillAudit>` 槽，driver 每轮装入当前会话审计器。**不改 `ExecPath.Security`**（技能不是安全边界；改了就会造出第二套安全真相）。
2. **状态是轮级、审计是会话级**：`SkillState` 每轮新建（防「首次激活」跨轮泄漏）；`SessionSkillAudit` 按 `(session, turn)` 每轮重建（防跨会话串写）。工具实例长生命——工具不该知道会话是什么。
3. **正文不驻留快照**：`reload()` 的 digest 只含元数据（name / description / whenToUse / disableModelInvocation / 排序后的 tools / dir）；正文**调用时现读**——改了 `SKILL.md` 立刻生效，不用重扫、不用重启。
4. **清单变化走 user 侧完整替换通知**：`SkillReloadResult.notice()` 里放**当前完整清单**（不是增量），静态系统提示词**不重渲染**（保 prompt cache）。框架不自动盯盘：扫描要读盘，自动扫就把「读盘多少钱」藏进每次调用的成本里。
5. **条件注册即能力证据**：`modelVisible()` 为空 → **根本不注册** `skill`；全部 `disable-model-invocation` 时模型面与没装技能一致，但 `/name` 手势仍可用。
6. **审计先于加载（fail-closed）**：`skill/activate` 写失败 → 本次调用失败，正文不外泄；`allowed-tools` mismatch 写失败**不拦加载**（诊断不是契约）。
7. **`allowed-tools` 只诊断不生效**：装配期一条 warning；激活时与 `ToolRegistry.visibleNames()` 求交 → `missingAllowed`（排序）→ 单独事件；**执行工具面一个字都不改**。
8. **技能事件不进投影**：与 `approval/*` 同口径——审计留痕不是对话内容（`SessionSkillAuditTest` 守护）。
9. **提示词插入用字符串层函数**：宿主常把 prompt 当整串交给 `Config`（不经 `Builder`），技能段仍必须落在中心顺序位置，否则 prompt cache 全失效 → `PromptSections.insert`（纯函数，命中 `## name` 插在其前，未命中追加）。
10. **正文只走专用工具**：技能目录是**宿主给的路径**，不走工作区 jail；正文不经 `read` 的文件工具面（两套权限面分开）。
11. **重扫是数据面刷新**：`SKILL.md` 是**数据**不是组件——所以显式重扫不违反治理规则禁止的「运行期插件/组件替换」；但必须显式、幂等、全量、原子快照替换。
12. **Starter 相对路径锚 `mos4j.workspace`**：不用进程 CWD（换目录启动不应该换技能集合）。

## 5. 与 Go 的有意差异（登记，不擅改）

| 项 | Go | Java | 理由 |
|---|---|---|---|
| 模块划分 | 独立的 `agent/skill` 包 | `mos4j-tool` 的 `tool/skill` 包 + core 接口 + session 实现 + driver 接线 | **J7 不新增模块**（保持 12 模块边界，已批准） |
| 技能级工具收窄 | `allowed-tools` 只解析、不生效 | 同口径；另把求交结果写进 `skill/allowed-tools-mismatch` 事件 | 不造第二套安全真相 |
| 正文预算超限 | 报错 | 报错（`技能 "x" 正文超预算（n 字节，上限 m）`） | 一致 |
| 裸 `~` 标量 | `yaml.v3` → null | Jackson YAML → 字符串 `~` | 已知差异；测试钉住现状（不假装等价） |
| 清单渲染降级级数 | 三级 | 五级（多出「最小形态」与「不注入」两级） | 预算被宿主调到极小值时也要有诚实行为 |

## 6. 诚实边界（未做 / 不可由机械测试代替）

1. **G1–G6 人工语义评审未完成**：`REVIEW-SHEET.md` 已把 J7 新增项（SI-26…SI-29、B14 收口）摊成可签字行，但**判定必须由人填**。
2. **Seatbelt 真机强制效果** 仍是唯一跳过用例（`sandbox_apply: Operation not permitted`），J7 未改变。
3. **技能目录重扫的并发语义未做压测**：`reload()` 是原子快照替换（旧读者看到旧快照），但没有多线程并发 reload + 读取的随机化测试。**Go 侧也没有对应用例**（`agent/skill/reload_test.go` 7 条都不是并发）→ 属自选加固，不是对拍缺口。
4. **CLI 未接技能面**：`mos4j` 单轮 CLI 未加 `--skills` 参数（SDK / Starter 已接）。**Go 侧同样没有**（Go 的 CLI 是 `examples/chat/lab` 示例程序，不带技能开关）→ 属**可选增强**，不是缺口；要做的话是一个新参数（需评审）。
5. **`SkillTool` 的 mismatch 诊断不进模型结果**：**与 Go 一致**（Go 侧 `agent/skill/allowed_tools_test.go:TestMismatchNotInModelResult` 明确断言「诊断不进给模型的返回值」——工具面模型自己看不见，说了也无从行动）。**不是缺口**，是同口径；宿主想让模型知道「你声明的工具不可用」需要自己读日志。
6. ~~**工作区不是 Git 仓库**~~ → **已解除**（J8 期间工作区已初始化为 Git 仓库）：改动可用 `git diff` 汇总。