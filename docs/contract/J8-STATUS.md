# J8 状态：三平台路径强制（沙箱后端）

> 权威口径。J8 回答的问题是：**MoS 有 Linux/Windows 的 Sandbox，MoS4J 能不能同步**。
> 用户裁决口径：**「与 MoS」**——即与 Go 参考实现同行为（缺省装配 + fail-closed），
> 不是「宿主显式 opt-in 才隔离」。

- 起止：2026-11-07 ～ 2026-11-07（J8-a / J8-b 落地，J8-c 未开工）
- 前置：J1–J7 + B13 + B16 全部收口（基线 525/1，含 starter 542/1）
- 契约影响：**字段冻结清单不变**（80 条 / 71 个唯一名称 / `3d35911d…`）、事件不变、
  12 模块边界不变、`PathScope` 接口不变。变的只有：`Describe` 的 caveat 文本、
  `PathScopes.supported()` 的语义、SDK 缺省装配行为。

---

## 1. 交付物清单

| 文件 | 类型 | 内容 |
|---|---|---|
| `mos4j-sandbox/.../SeatbeltScope.java` | 改 | profile 补 `(literal "/dev/null")`；写根**软链规范化**（`toRealPath`，失败退回 `absolutize`）；SBPL 字符串转义；`wrap` 先探 `sandbox-exec` 可执行，缺失即报错（fail-closed） |
| `mos4j-sandbox/.../PathScopes.java` | 改 | `supported()` 从「是不是 macOS」改为「**有没有可用后端**」；新增 `derive(workDir)`（对齐 Go `deriveScope`）；`create` 三平台分派，无后端平台抛 `UnsupportedOperationException` 且消息点明缺什么 |
| `mos4j-sandbox/.../LandlockScope.java` | 新 | Linux Landlock LSM 后端：JDK 25 FFM（`java.lang.foreign`）直调 `landlock_create_ruleset` / `landlock_add_rule` / `landlock_restrict_self` + `prctl(PR_SET_NO_NEW_PRIVS)`；ABI V1/V2/V3 分档；`wrap` 拼「重新执行自己的 java 启动器」 |
| `mos4j-sandbox/.../LandlockLauncher.java` | 新 | 启动器：解写根载荷 → 给本进程套 Landlock → 起命令并透传退出码；失败统一 `exit 125` + 可读 stderr |
| `mos4j-sandbox/.../LocalRunner.java` | 改 | `wrap` 抛错（后端不可用 / 拿不到 java）→ 返回 `RunResult.notStarted("路径强制不可用: …")`，**命令一个字节都不跑**（fail-closed） |
| `mos4j-sdk/.../Mos4j.java` | 改 | `fileTools()` 缺省装配：`PathScopes.supported()` 为真就 `pathScope(PathScopes.derive(anchor))`（与 Go 缺省强制同口径） |
| `mos4j-sandbox/src/test/.../SandboxBoundaryTest.java` | 改 | 14 → 21：`/dev/null` 放行、软链写根规范化、`wrap` 失败即 fail-closed、`derive` 写根口径、**Landlock `wrap` 形状与写根载荷往返（不碰 native，任何平台可验）**、`/` 逃生门、收窄不能放开 |
| `mos4j-sandbox/src/test/.../LandlockScopeTest.java` | 新 | 5 条**只在 Linux 执行**（`@EnabledOnOs(OS.LINUX)`）：ABI 探测、写根内可写、写根外被内核拒、`/dev/null` 可写、启动器失败码 |

---

## 2. 三平台能力矩阵（本轮实测口径）

| 平台 | 后端 | 装配 | 真机验证状态 |
|---|---|---|---|
| **macOS** | Seatbelt（`sandbox-exec -p`） | ✅ SDK 缺省装配（写根 = 工作区） | ⚠️ **本机 `sandbox_apply: Operation not permitted`（退出码 71）**：profile 生成与 fail-closed 管道已实测，**内核强制效果未验证** |
| **Linux** | Landlock LSM（FFM 直调，零依赖零 JNI） | ✅ SDK 缺省装配 | ❌ **本机是 Darwin，无法验证**；`LandlockScopeTest` 在 Linux 上自动执行（CI `ubuntu-latest` 会跑） |
| **Windows** | 未实现 | — | `PathScopes.create` 抛 `UnsupportedOperationException`（fail-closed，不假装隔离） |

> 读这里最容易误读的一句：**「后端已实现」≠「已验收」**。macOS 那条本机被宿主策略拒绝，
> Linux 那条本机跑不了，Windows 那条只有报错分支。三条都要按右列读。

---

## 3. 与 Go 的对齐点

| # | Go 的做法 | MoS4J 的落点 |
|---|---|---|
| 1 | `deriveScope(WorkDir)`：缺省写根 = 工作目录 | `PathScopes.derive(anchor)`，SDK 里无条件装配 |
| 2 | `effectiveScope`：请求只能**收窄**，放宽须在装配期声明 | `narrowTo` 的 covered 校验（Seatbelt / Landlock 两处一致） |
| 3 | `writeOpen(eff)` = 写根含 `/` → 原样返回（显式逃生门） | 两后端 `unrestricted()` 同口径，且写进 `description()` |
| 4 | Seatbelt profile：`deny file-write*` + `(literal "/dev/null")` + 每个写根 `(subpath …)` | 逐条对齐（本轮补上 `/dev/null`） |
| 5 | Landlock 权限集按 ABI 分档（V2 加 `REFER`、V3 加 `TRUNCATE`） | `applyToCurrentProcess` 同档，文件规则只给 `WRITE_FILE`(+`TRUNCATE`) |
| 6 | 无后端平台 `ErrPathScopeUnsupported` fail-closed | `UnsupportedOperationException` + `LocalRunner` 转 `notStarted` |
| 7 | `sandbox-exec` 缺失 → `ErrPathScopeUnsupported` | `wrap` 先探可执行位（**装配期**就发现，比 Go 更早） |

## 4. 与 Go 的有意差异（登记，不打算改）

| # | 差异 | 理由 |
|---|---|---|
| D-J8-1 | Linux 载荷走**命令行参数**（base64 + `\n` 分隔），Go 走管道 fd | `LocalRunner` 不接管子进程 stdin；走 argv 不必改运行器。代价：写根极多会撞 `ARG_MAX`（128KB，实测可放上千个根） |
| D-J8-2 | Landlock **ABI 探测在启动器进程内**，不在 `wrap` 阶段 | 宿主进程不碰 native，就不会在宿主 stderr 上打 `--enable-native-access` 警告；代价是「内核没 Landlock」要等命令执行才暴露（退出码 125 + 原因） |
| D-J8-3 | 启动器额外传 `--enable-native-access=ALL-UNNAMED`、`-XX:-UsePerfData`、`-Djava.io.tmpdir=<首个写根>` | JDK 25 的 FFM 受限方法告警 + 启动器自身要写 tmp/hsperfdata，而这些都在写根之外 |
| D-J8-4 | `PathScopes.supported()` 现在是「后端可用」，不是「平台是 macOS」 | 与 Go 的「有后端就强制」一致；也避免宿主拿 `supported()` 当平台判断用 |
| D-J8-5 | Windows 后端**没做** | 机制不同（低完整性 token + Low 标签写 ACE + Job Object），且整条 Unix 假设要拆（`LocalRunner.SHELL="/bin/sh"`、`ResourceLimits.shellSnippet()` 的 `ulimit` 前缀）→ **Windows 上 shell 工具现在根本跑不起来**；无验证环境不可验收 |

---

## 5. 机械验证（真实输出）

| 项 | 命令 | 结果 |
|---|---|---|
| 默认 reactor | `./mvnw -Dmaven.repo.local=.m2repo -B clean verify` | **BUILD SUCCESS**，12 个测模块，**537 通过 / 0 失败 / 0 错误 / 6 跳过**（日志 `.j7log2/j8-default-final.log`） |
| 含 starter | 同上 `-Pstarter` | **BUILD SUCCESS**，13 个测模块，**554 / 0 / 0 / 6**（`.j7log2/j8-starter-final.log`） |
| 依赖审计 | 12 模块 `dependency:analyze -Dmdep.analyze.failOnWarning=true` | **BUILD SUCCESS**（FFM 是 JDK 自带，**没有新增任何外部依赖**） |
| C6 源码扫描 | `import org.springframework` / `import java.util.regex` | 均 **0** |
| 真实行为探针 | `java -cp mos4j-sandbox/target/classes .j7log2/ScopeProbe.java` | `supported=true`；写根内的命令 `exit=71` + stderr `sandbox-exec: sandbox_apply: Operation not permitted`；写根外文件**未被创建** |

**基线推移**：525/1 → **537/6**（sandbox 模块 28 → 40：`SandboxBoundaryTest` 14→21，新增 `LandlockScopeTest` 5 条在 macOS 上整类跳过）；含 starter 542 → **554**。

**测试抓到的两个真 bug**（都已修）：
1. `landlock_path_beneath_attr` 用 `withByteAlignment(1)` 模拟 packed → 类初始化时抛 `Invalid alignment constraint`（group layout 不能压到自然对齐以下）；改为 16 字节自然对齐布局，字段偏移仍是 0 与 8，内核只读前 12 字节。
2. libc 绑定写在类静态字段里 → 在 macOS 上装载 `LandlockScope` 就炸（`libc 没有 prctl`）；改为嵌套懒加载 holder，只有真正调 native 时才解析。**这两条都是「本机不能验内核效果」时仍然能验到的部分**——所以它们被写成了平台无关的用例。

> 6 条跳过 = 原有 1 条（Seatbelt 真机强制）+ 新增 5 条（`LandlockScopeTest`，非 Linux 平台）。

---

## 6. 诚实边界

1. **macOS 的 Seatbelt 内核强制仍未真机验证**：本机 `sandbox_apply` 被宿主策略拒绝（退出码 71），
   装配链路与 fail-closed 行为是实测的，**「写根外真的写不进去」这句话在本机没有被证明**。
2. **Linux Landlock 后端没有在 Linux 上跑过**（本机 Darwin）。代码能编译、非 Linux 分支行为已测、`wrap` 形状与载荷往返已测，
   但 `landlock_*` 三支系统调用 + FFM 变参调用 + ABI 分档**只在 Linux 上才能证明**。
   `LandlockScopeTest` 是给那一刻准备的——它在非 Linux 上是**跳过，不是通过**。
3. **Windows 后端完全没做**，且 Windows 上 shell 工具当前不可用（见 D-J8-5）。
4. **「与 MoS 对齐」的代价要写清**：缺省装配后，**宿主不允许应用沙箱的机器上 shell 命令会直接失败**
   （本机就是这种情况，退出码 71）。这正是 fail-closed 的意图——但它会让「以前能跑」的宿主
   变成「现在不能跑」，**升级前必须知道**。
5. **`SeatbeltScope` 的软链规范化只在写根存在时生效**（不存在 → 退回绝对规范化路径）：
   声明一个「以后才创建」的软链写根，匹配范围可能与真实路径不一致（写它会失败，不会放宽）。
6. **沙箱系统临时目录不可写 + 工作区非 Git 仓库**：探针与日志只能落在模块内 `target/`、`.j7log2/`；
   本轮无法给出 `git diff` 形式的改动清单。

## 7. 下一步

| 项 | 需要的条件 |
|---|---|
| 验证 macOS 强制效果 | 一台允许 `sandbox-exec` 的 macOS 主机：`-Dtest=SandboxBoundaryTest` 会把那 1 条跳过跑起来 |
| 验证 Linux 强制效果 | 任意 Linux ≥5.13（内核 `CONFIG_SECURITY_LANDLOCK=y`）：`./mvnw -pl mos4j-sandbox -am test` 自动执行 `LandlockScopeTest` 5 条；建议同时加 CI `runner.os == 'Linux'` 的显式 job |
| J8-c Windows | 先有 Windows 验证环境；随后要拆 `LocalRunner` 的 `/bin/sh` 与 `ulimit` 前缀（8–12 人日量级） |