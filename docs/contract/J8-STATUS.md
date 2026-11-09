# J8 状态：路径强制（沙箱后端）——**macOS 优先**

> **布局说明（2026-11-08）**：SDK 已从**十二模块**合并为**六模块**（`core` / `model` / `runtime` / `tool` / `session` / `agent`）。本文记录的是合并**之前**的批次状态，文中模块名保留当时的写法；现行布局见 [`README.md`](../../README.md) 与 [`docs/contract/API.md`](API.md)。

> 权威口径。J8 回答的问题是：**MoS 有 Linux/Windows 的 Sandbox，MoS4J 能不能同步**。
> 用户裁决口径：**「与 MoS」**（缺省装配 + fail-closed），随后进一步收敛为
> **「先过 Mac 的，Linux 和 Windows 暂时不验证」**（2026-11-07）。
>
> **因此本轮的口径是**：macOS = 唯一**已验证 + 缺省装配**的平台；
> Linux = 代码在位但**未验证 → 默认不装配**；Windows = 未实现。

- 起止：2026-11-07（J8-a macOS 收口、J8-b Linux 代码在位、J8-c Windows 未开工）
- 前置：J1–J7 + B13 + B16 全部收口（基线 525/1，含 starter 542/1）
- 契约影响：**字段冻结清单不变**（80 条 / 71 个唯一名称 / `3d35911d…`）、事件不变、
  12 模块边界不变、`PathScope` 接口不变。变的只有：`Describe` 的 caveat 文本、
  `PathScopes.supported()` 的语义、SDK 缺省装配行为。

---

## 1. 交付物清单

| 文件 | 类型 | 内容 |
|---|---|---|
| `mos4j-sandbox/.../SeatbeltScope.java` | 改 | profile 与 Go 的 `seatbeltProfile` **逐条对齐且同形态（一行，DSH 同款）**：`deny file-write*` + `(literal "/dev/null")` + 每个写根 `(subpath …)`；写根**软链规范化**（`toRealPath`，失败退回绝对规范化）；SBPL 字符串转义；`narrowTo` 补**绝对路径校验**（对齐 Go `validateScope`）；`wrap` 先探 `sandbox-exec` 可执行，缺失即报错（fail-closed） |
| `mos4j-sandbox/.../PathScopes.java` | 改 | `supported()` = 「**有已验证的后端**」（当前 = macOS Seatbelt）；新增 `status()`（平台后端状态，进 Describe caveat）；新增 `derive(workDir)`（对齐 Go `deriveScope`）；`create` 三平台分派，无后端平台抛 `UnsupportedOperationException` 且消息点明缺什么 |
| `mos4j-sandbox/.../SandboxBoundary.java` | 改 | 「路径强制=未装配」的 caveat 追加**平台后端状态**——不再只说「没装」，还说「为什么没装」 |
| `mos4j-sandbox/.../LocalRunner.java` | 改 | `wrap` 抛错（后端不可用 / 拿不到 java）→ 返回 `RunResult.notStarted("路径强制不可用: …")`，**命令一个字节都不跑**（fail-closed） |
| `mos4j-sdk/.../Mos4j.java` | 改 | `fileTools()` 缺省装配：`PathScopes.supported()` 为真就 `pathScope(PathScopes.derive(anchor))`（写根 = 工作区，与 Go 缺省强制同口径） |
| `mos4j-sandbox/.../LandlockScope.java` | 新（Linux，**暂不验证**） | Landlock LSM 后端：JDK 25 FFM 直调 `landlock_create_ruleset` / `landlock_add_rule` / `landlock_restrict_self` + `prctl(PR_SET_NO_NEW_PRIVS)`；ABI V1/V2/V3 分档；`wrap` 拼「重新执行自己的 java 启动器」 |
| `mos4j-sandbox/.../LandlockLauncher.java` | 新（Linux，**暂不验证**） | 启动器：解写根载荷 → 给本进程套 Landlock → 起命令并透传退出码；失败统一 `exit 125` + 可读 stderr |
| `mos4j-sandbox/.../PathScopeFactory.java` | 新（Q2′） | 「按写根造一个作用域」的工厂接口（`create(List<Path>)`）——把「怎么造 scope」留在 sandbox 层，平台分支不上浮到门面 |
| `mos4j-sandbox/.../PathScopeProbe.java` | 新（Q5） | 路径强制**可用性探针**接口 + `Result(available, detail)`；可注入 → 测试与受管宿主不必真跑 apply |
| `mos4j-sandbox/.../LocalRunner.java` | 改（Q1/Q2′/Q5） | 新增 `pathScopeSupplier(Supplier<List<Path>>)`（**每次 run 现取写根**）、`pathScopeFactory(PathScopeFactory)`（每次现造 scope）、`noPathScope(String reason)`（显式关闭并记录原因）；写根为空 / 来源抛错 → **fail-closed**（命令不启动） |
| `mos4j-sandbox/.../SandboxBoundary.java` | 改（Q1/Q2′） | 快照区分三种状态：静态装 / **动态装（说「已装配（动态写根）」而不是谎报「未装配」）** / 未装（并说清是「平台没有」还是「宿主显式关闭」） |
| `mos4j-sdk/.../Mos4j.java` | 改（Q1/Q4/Q5） | 新增 `pathScope(PathScope)` / `noPathScope()` / `pathScopeProbe(PathScopeProbe)` / `requireFinish(Boolean)` / `maxTokens(Integer)`；缺省装配前**先探一次可用性**，不通则**装配期报错**（给两条出路） |
| `mos4j-sdk/.../cli/Main.java` | 改（Q1） | 新增 `--no-path-scope`（显式不装路径强制） |
| `mos4j-spring-boot-starter/.../Mos4jProperties.java` + `Mos4jAutoConfiguration.java` | 改（Q1/Q4/Q5） | 新增属性 `mos4j.path-scope`（auto / disabled）、`mos4j.require-finish`、`mos4j.max-tokens`；支持注入 `PathScopeProbe` Bean 覆盖探测 |
| `mos4j-sandbox/src/test/.../SandboxBoundaryTest.java` | 改 | 14 → 27：`/dev/null` 放行、软链写根规范化、`wrap` 失败即 fail-closed、`derive` 写根口径、**profile 形态与规则顺序**、**SBPL 转义**、**多写根**、**profile 被内核解析器接受（macOS 真机）**、**语法错可被检出（反例）**、绝对路径校验、`/` 逃生门、收窄不能放开、Landlock `wrap` 形状与载荷往返 |
| `mos4j-sandbox/src/test/.../LandlockScopeTest.java` | 新（Linux，**暂不验证**） | 5 条**只在 Linux 执行**（`@EnabledOnOs(OS.LINUX)`）：ABI 探测、写根内可写、写根外被内核拒、`/dev/null` 可写、启动器失败码 |

---

## 2. 能力矩阵（本轮实测口径）

| 平台 | 后端 | 缺省装配 | 验证状态 |
|---|---|---|---|
| **macOS** | Seatbelt（`sandbox-exec -p`） | ✅ 装（写根 = 工作区） | ✅ **profile 已被内核解析器接受**（真机用例：非 65 退出、stderr 无 `unbound variable`；反例用例证明该判据有效）<br>⚠️ **强制效果未验证**：本机 `sandbox_apply: Operation not permitted`（exit 71），宿主策略不允许应用沙箱 |
| **Linux** | Landlock LSM（FFM 直调，零依赖零 JNI） | ❌ **不装**（代码在位但未验证 → 缺省不放未验证的隔离面到生产路径） | ❌ 未在 Linux 上跑过；`LandlockScopeTest` 在非 Linux 上整类跳过 |
| **Windows** | 未实现 | — | `PathScopes.create` 抛 `UnsupportedOperationException`（fail-closed，不假装隔离） |

> 读这张表最容易误读的一句：**「代码在位」≠「可缺省启用」≠「已验收」**。
> macOS 目前是「已验证（语法层）+ 未验收（强制层）」；Linux 是「代码在位 + 默认不启用」。

---

## 3. 与 Go 的对齐点（macOS 侧）

| # | Go 的做法 | MoS4J 的落点 |
|---|---|---|
| 1 | `deriveScope(WorkDir)`：缺省写根 = 工作目录 | `PathScopes.derive(anchor)`，SDK 缺省装配 |
| 2 | `effectiveScope`：请求只能**收窄**，放宽须在装配期声明 | `narrowTo` 的 covered 校验（Seatbelt / Landlock 两处一致） |
| 3 | `validateScope`：根必须非空、绝对 | `narrowTo` 补绝对路径校验（`根 X 不是绝对路径`） |
| 4 | `writeOpen(eff)` = 写根含 `/` → 原样返回（显式逃生门） | `unrestricted()` 同口径，且写进 `description()` |
| 5 | `seatbeltProfile`：**一行**、`(version 1) (allow default) (deny file-write*)` + `(literal "/dev/null")` + 每写根 `(subpath …)`，写根先 `EvalSymlinks` | 逐条对齐，且**形态一致（一行）** |
| 6 | `sbplString`：反斜杠与双引号转义 | 同规则（同用例对位） |
| 7 | `sandbox-exec` 缺失 → `ErrPathScopeUnsupported` | `wrap` 先探可执行位（**装配期**就发现，比 Go 更早） |
| 8 | 无后端平台 fail-closed，不做假降级 | `UnsupportedOperationException` + `LocalRunner` 转 `notStarted` |

## 4. 与 Go 的有意差异（登记）

| # | 差异 | 理由 |
|---|---|---|
| D-J8-1 | Linux 载荷走**命令行参数**（base64 + `\n` 分隔），Go 走管道 fd | `LocalRunner` 不接管子进程 stdin；走 argv 不必改运行器。代价：写根极多会撞 `ARG_MAX`（128KB，实测可放上千个根） |
| D-J8-2 | Landlock **ABI 探测在启动器进程内**，不在 `wrap` 阶段 | 宿主进程不碰 native，就不会在宿主 stderr 上打 `--enable-native-access` 警告；代价是「内核没 Landlock」要等命令执行才暴露（退出码 125 + 原因） |
| D-J8-3 | 启动器额外传 `--enable-native-access=ALL-UNNAMED`、`-XX:-UsePerfData`、`-Djava.io.tmpdir=<首个写根>` | JDK 25 的 FFM 受限方法告警 + 启动器自身要写 tmp/hsperfdata，而这些都在写根之外 |
| D-J8-4 | `PathScopes.supported()` = 「**有已验证的后端**」，不是「平台是 macOS」也不是「代码在位」 | Go 是「有后端就强制」；但我们的 Linux 后端**没在 Linux 上验证过**，缺省装它等于把未验证的隔离面顶到生产路径上。**这是本轮对「与 MoS」的收窄**，用户口径「先过 Mac」支持这个收窄 |
| D-J8-5 | Windows 后端**没做** | 机制不同（低完整性 token + Low 标签写 ACE + Job Object），且整条 Unix 假设要拆（`LocalRunner.SHELL="/bin/sh"`、`ResourceLimits.shellSnippet()` 的 `ulimit` 前缀）→ **Windows 上 shell 工具现在根本跑不起来**；无验证环境不可验收 |
| D-J8-6 | ~~Go 的**动态 Scope**（`Local.Scope` 函数）未对位~~ → **已补（Q2′，2026-11-07）**：`LocalRunner.pathScopeSupplier`（每次调用现取写根）+ `PathScopeFactory`（按平台现造 scope）；SDK 把 `RootSet` 的活根接进 supplier → 会话里 `grant_access` 授权的新目录，**shell 跟着能写**（不再出现「工具能写、命令写不了」的错位） | 代价：每条命令重新生成一次 profile（macOS）/ 重新起一次 launcher（Linux，未装配）；写根为空或来源抛错 → fail-closed（命令不启动） |
| D-J8-7 | **请求期 `RunRequest.pathScope` 有意不补** | Go 的 `sandbox.Request.PathScope` 只给「直接调 Runner 的宿主」用，**Go 自己的 shell 工具不传它**；Java 的 `Runner` 只有一个 `run(RunRequest)`，补它会多出一套「请求期范围」语义而没有真实使用者。已登记为有意差异（**不是缺口**） |

---

## 5. 机械验证（真实输出）

| 项 | 命令 | 结果 |
|---|---|---|
| 默认 reactor | `./mvnw -Dmaven.repo.local=.m2repo -B clean verify` | **BUILD SUCCESS**，12 个测模块，**567 通过 / 0 失败 / 0 错误 / 7 跳过**（Q1–Q8 实施后的基线；日志 `.j7log2/final2-default.log`）。**后续批次的最新基线：默认 633 / starter 655 / MCP 704**（`docs/mos4j-tasks.md` §14） |
| 含 starter | 同上 `-Pstarter` | **BUILD SUCCESS**，13 个测模块，**589 / 0 / 0 / 7**（`.j7log2/final2-starter.log`） |
| 依赖审计 | 12 模块 `dependency:analyze -Dmdep.analyze.failOnWarning=true` | **BUILD SUCCESS**（FFM 是 JDK 自带，**没有新增任何外部依赖**） |
| C6 源码扫描 | `import org.springframework` / `import java.util.regex` | 均 **0** |
| **profile 语法真机验证** | `sandbox-exec -p '<profile>' /usr/bin/true` | 正确 profile → **exit 71**（`sandbox_apply: Operation not permitted`：语法过了，apply 被宿主拒）；故意写坏的 profile → **exit 65** + `unbound variable`（解析阶段就失败）。**判据已固化成两条自动化用例**（一条测正确 profile、一条测反例） |
| 真实行为探针 | `java -cp mos4j-sandbox/target/classes tools/seatbelt-acceptance/SeatbeltAcceptance.java /tmp/mos4j-accept` | 本机（宿主拒 apply）：`available=false` + `宿主不允许应用 macOS 沙箱（sandbox_apply: Operation not permitted）` → **退出码 2（未验证，不是失败）**；脚本在能应用沙箱的机器上会返回 **0 = 强制效果已验证** |
| 强制效果用例的判定加固 | `SandboxBoundaryTest.seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt` | 原写法只断言「写根外的文件不存在」——**apply 被拒时命令根本没跑，文件当然也不存在，会假绿**。现改为两条独立事实：①写根内的 marker 存在（命令真的跑起来了）②写根外的文件未被创建。marker 不存在 → 如实跳过（不判通过也不判失败） |

**基线推移**：525/1 → **543/6**（sandbox 模块 28 → 46：`SandboxBoundaryTest` 14→27，新增 `LandlockScopeTest` 5 条在 macOS 上整类跳过）；含 starter 542 → **560**。
→ **567/7**（Q1/Q2′/Q4/Q5 实施后：sandbox 模块 46 → 57；新增 `PathScopeRuntimeTest` 11 条）；含 starter 560 → **589**。

**测试抓到的两个真 bug**（都已修）：
1. `landlock_path_beneath_attr` 用 `withByteAlignment(1)` 模拟 packed → 类初始化时抛 `Invalid alignment constraint`（group layout 不能压到自然对齐以下）；改为 16 字节自然对齐布局，字段偏移仍是 0 与 8，内核只读前 12 字节。
2. libc 绑定写在类静态字段里 → 在 macOS 上装载 `LandlockScope` 就炸（`libc 没有 prctl`）；改为嵌套懒加载 holder，只有真正调 native 时才解析。

> 7 条跳过 = 原有 1 条（Seatbelt 强制效果的**真机**用例）+ 5 条（`LandlockScopeTest`，非 Linux 平台）+ 1 条（`PathScopeRuntimeTest.factoryBuildsTheLandlockBackend`，非 Linux 平台）。
> **profile 语法那两条在本机是「跑过」的**（macOS），不是跳过。

---

## 6. 诚实边界

1. **macOS 的 Seatbelt 强制效果仍未验证**：本机 `sandbox_apply` 被宿主策略拒绝（exit 71）。
   已证明的是「profile 被内核解析器接受」+「装配链路与 fail-closed 生效」；
   **「写根外真的写不进去」这句话在本机没有被证明**。
   → 验收方式已备成可复制的一包：[`tools/seatbelt-acceptance/README.md`](../../tools/seatbelt-acceptance/README.md)
   （A 组：一条命令跑三条用例；B 组：`SeatbeltAcceptance.java` 探针脚本，退出码 0=已验证 / 1=真失败 / 2=未验证）。
2. **Linux Landlock 后端没有在 Linux 上跑过**，且**默认不装配**——本轮口径就是「暂不验证」。
   代码在位是为了以后接上（`new LandlockScope(roots)` 显式装配即可），但**不能当成可用能力对外说**。
3. **Windows 后端完全没做**，且 Windows 上 shell 工具当前不可用（见 D-J8-5）。
4. **「与 MoS 对齐」的代价要写清**：macOS 上缺省装配后，**宿主不允许应用沙箱的机器上 shell 命令会直接失败**
   （本机就是这种情况，exit 71）。这正是 fail-closed 的意图——但它会让「以前能跑」的宿主
   变成「现在不能跑」，**升级前必须知道**。
5. **`SeatbeltScope` 的软链规范化只在写根存在时生效**（不存在 → 退回绝对规范化路径）：
   声明一个「以后才创建」的软链写根，匹配范围可能与真实路径不一致（写它会失败，不会放宽）。
6. ~~**Go 的动态 Scope 未对位**（D-J8-6）~~ → **已补（Q2′）**：会话里新授权的目录，shell 现在能写了。
   **但这不等于内核级强制被验收**——动态写根链路是用假 `PathScopeFactory` 验证的（每次调用取到新根），
   真正的「写根外真的写不进去」仍要在允许 `sandbox-exec` 的 macOS 上验。
7. **受限 macOS 上的缺省行为变了（Q5）**：宿主不允许应用沙箱时，**SDK / Starter 会在装配期直接报错**（而不是等到模型调 shell 才失败）。
   两条出路写在错误信息里：①换一台允许应用沙箱的 macOS；②显式关闭（SDK `noPathScope()` / Starter `mos4j.path-scope=disabled` / CLI `--no-path-scope`）。
   **显式关闭会被 Describe 如实声明**（「路径强制=未装配（原因：宿主显式关闭…）」），不是静默降级。
8. **沙箱系统临时目录不可写**：探针与日志只能落在模块内 `target/`、`.j7log2/`。
   （~~工作区非 Git 仓库~~ → **已解决**：工作区已是 Git 仓库，本轮改动可用 `git diff` 汇总；
   但**提交**仍未做——提交与推送属于人的决定，本轮不代劳。）

## 7. 下一步

| 项 | 需要的条件 | 状态 |
|---|---|---|
| 验证 macOS **强制效果** | 一台允许 `sandbox-exec` 的 macOS 主机（`SandboxBoundaryTest` 里那 1 条跳过会自动变通过） | **待办**（本机做不到）→ **验收包已备好**：[`tools/seatbelt-acceptance/README.md`](../../tools/seatbelt-acceptance/README.md)（A 组自动化用例 + B 组真机探针脚本 + 结果回填清单） |
| 验证 Linux 强制效果 | 任意 Linux ≥5.13（`CONFIG_SECURITY_LANDLOCK=y`） | **暂缓**（用户口径） |
| J8-c Windows | Windows 验证环境 + 拆 `LocalRunner` 的 `/bin/sh` 与 `ulimit` 前缀（8–12 人日量级） | **暂缓**（用户口径） |
| 补 Go 的动态 Scope（D-J8-6） | `LocalRunner` 加 `pathScopeSupplier`（每次调用现取）+ `PathScopes.factory()` | ✅ **已完成（Q2′）**：动态写根 + 工厂 + SDK 接线 + 守护用例（`DynamicPathScopeTest`） |
| Q5 探测的**真机**验证 | 一台允许 `sandbox-exec` 的 macOS（探测应返回「可用」） | **待办**（本机 apply 被拒 → 探测返回不可用，测试用注入探针绕开） |
