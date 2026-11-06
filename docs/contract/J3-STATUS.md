# J3 推进状态（文件与沙箱）

> 更新：2026-11-05。J3 = 文件 jail + 写前观察门 + 命令沙箱 + 路径强制 + 降级声明（任务列表 §5）。
> 配套：[任务列表](../mos4j-tasks.md)、[推进方案](../mos4j-execution.md)、[契约目录](.)、[J1 状态](J1-STATUS.md)、[J2 状态](J2-STATUS.md)。

## 一句话状态

**T3.1–T3.11 全部完成并有测试；G3 三项机械验收全绿。**
唯一未验证项：**Seatbelt 的强制效果在当前环境无法应用**（`sandbox-exec: sandbox_apply: Operation not permitted`），
运行时用例如实标为**跳过**，不假装通过；profile 生成、wrapper 形状、只收窄不放开的语义都有静态测试兜底。

## 产出文件

| 模块 | 文件 | 说明 |
|---|---|---|
| mos4j-fs | `fs/{Root,RootSet}.java` | 活根集合：锚点稳定、加根追加、下一次调用立即生效、变更留痕、挂载名消费首段 |
| mos4j-fs | `fs/SandboxedFs.java` | 逐段解析符号链接；Lexical + Realpath 双校验（**读也走 Realpath**）；深度上限 32 |
| mos4j-fs | `fs/PathDeniedError.java` | 结构化拒绝：带「该操作允许的根」 |
| mos4j-fs | `fs/ObserveGate.java` | 写前观察门（M7）：只给**事实**（原因码 + 令牌），文案在工具层 |
| mos4j-fs | `fs/VersionToken.java` | 版本令牌：`dev:ino:size:mtimeNs`（Unix）/ `size:mtimeNs`（退化） |
| mos4j-fs | `fs/FileSystemBoundary.java` | 文件边界快照（`Describe` 的事实来源） |
| mos4j-sandbox | `sandbox/{RunRequest,RunResult,Runner}.java` | 运行请求 / 结果 / 运行器接口 |
| mos4j-sandbox | `sandbox/LocalRunner.java` | 本地运行器：宿主固定工作目录、超时上界、输出上限、环境白名单裁剪 |
| mos4j-sandbox | `sandbox/ResourceLimits.java` | `ulimit -t/-f`（macOS 跳过 `-v` 并显式报告跳过项） |
| mos4j-sandbox | `sandbox/{ProcessGroupKiller,DescendantsKiller}.java` | 进程组击杀接口 + 降级实现（尽力而为、非原子） |
| mos4j-sandbox | `sandbox/{PathScope,PathScopes,SeatbeltScope}.java` | 路径强制：只收窄不放开；macOS Seatbelt 后端；不支持平台明确报错（**J8 扩为三平台：+ `LandlockScope`/`LandlockLauncher` + `PathScopes.derive` + SDK 缺省装配**，见 [`J8-STATUS.md`](J8-STATUS.md)） |
| mos4j-sandbox | `sandbox/SandboxBoundary.java` | 沙箱边界快照（`Describe` 的事实来源） |
| mos4j-tool | `tool/builtin/{ReadFileTool,WriteFileTool,EditFileTool}.java` | 文件工具：观察门接线、原子写、唯一命中编辑 |
| mos4j-tool | `tool/builtin/{TextContent,FileOps,BuiltinArgs}.java` | 文本归一 / BOM 保留 / 二进制拒绝；拒绝文案拼装；参数容错 |
| mos4j-tool | `tool/builtin/ShellTool.java` | shell 工具：非零退出是正常结果，末尾 `[exit code: N]` |
| mos4j-driver | `agent/{Config,Agent}.java` | 装配入口：`fileSystem()` / `sandbox()`；`describe()` 聚合边界快照 |

## 任务完成情况

| 任务 | 状态 | 证据（真实测试） |
|---|---|---|
| T3.1 jail 双校验 | ✅ | `SandboxedFsTest`（18 条，含 `l3_12_readAlsoGoesThroughRealpath`） |
| T3.2 新建路径向上回溯 | ✅ | `SandboxedFsTest.l3_13_leafMayNotExistButMiddleComponentStillChecked` / `newFileUnderNormalDirectoryIsAllowed` |
| T3.3 symlink 三形态 + 深度上限 | ✅ | `SandboxedFsTest.l3_12_*` / `l3_13_symlinkAsMiddleComponentIsDenied` / `l3_14_symlinkLoopIsDeniedInsteadOfHanging` |
| T3.4 `RootSet` | ✅ | `RootSetTest`（8 条：锚点稳定 / 加根即时生效 / 并发加根 / 变更留痕 / 挂载首段消费 / 挂载优先） |
| T3.5 结构化拒绝 | ✅ | `PathDeniedError` + `SandboxedFsTest.denialCarriesTheAllowedRoots` |
| T3.6 Local Runner | ✅ | `LocalRunnerTest`（14 条：超时上界 / 输出上限 / 环境裁剪 / 工作目录固定 / exit 127 不是启动失败） |
| T3.7 资源限制 | ✅ | `ResourceLimits` + `SandboxBoundaryTest.macOsSkipsVirtualMemoryLimit` / `appliedLimitsAreReported` |
| T3.8 `ProcessGroupKiller` + 降级声明 | ✅ | `DescendantsKiller` + `SandboxBoundaryTest.descendantsKillerDeclaresItsWeakness` / `descendantsKillerKillsTheProcess` |
| T3.9 `PathScope` + Seatbelt | ⚠️ 部分 | 静态部分全绿（profile / wrapper / 只收窄 / 逃生门 / 不支持平台报错）；**强制效果未验证**（见下） |
| T3.10 写前观察门 + 版本令牌 | ✅ | `ObserveGateTest`（11 条）+ `FileToolsTest.l3_15_*` / `l3_16_editWithoutReadIsDenied` / `l3_17_newFileDoesNotRequireRead` |
| T3.11 read / write / edit / shell 工具 | ✅ | `FileToolsTest`（22 条）+ `ShellToolTest`（6 条） |

## 场景覆盖（真实计数）

| 层 | 绿 | 未做 | 说明 |
|---|---|---|---|
| L3 D2（文件与沙箱） | **9/9**（L3-11 … L3-19） | — | L3-19（J1 已有的截断拒执行）也在 D2 段内 |
| L3 D3（Java 差异降级声明） | **2/2**（L3-20 / L3-21） | — | 击杀强度与版本令牌口径都进了 `Describe()` 快照 |
| Seatbelt 强制效果（真机） | 0/1 | **跳过** | 当前环境不允许应用；用例已写好，在允许的 macOS 上会自动从「跳过」变「通过」 |

## 验证记录（真实输出）

```
$ mvn -Dmaven.repo.local=.m2repo clean verify
mos4j-core     Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
mos4j-llm      Tests run: 35, Failures: 0, Errors: 0, Skipped: 14
mos4j-fs       Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
mos4j-sandbox  Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
mos4j-tool     Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
mos4j-session  Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
mos4j-hook     Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
mos4j-execpath Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
mos4j-driver   Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
mos4j-policy   Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

合计 **266 个用例通过 / 15 个跳过**（共 281 个跑过）。
跳过的 15 条 = Anthropic + Gemini 的 L1 协议场景 14 条（适配器在 J6）+ Seatbelt 强制效果 1 条（当前环境不允许应用）。

同一套用例用 Maven Wrapper 重跑了一遍（wrapper 固定 Maven 3.9.9，计数完全一致）：

```
$ MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify
13 个 reactor 项全部 SUCCESS
BUILD SUCCESS
```

依赖门与源码纪律：

```
$ mvn -Dmaven.repo.local=.m2repo dependency:analyze -Dmdep.analyze.failOnWarning=true
10 个已实现模块全部 No dependency problems found
（mos4j-context / mos4j-sdk 仍是空骨架，CI 的 C2b 门显式排除）

$ mvn -Dmaven.repo.local=.m2repo dependency:tree -Dscope=runtime
外部依赖只有 jackson-databind / jackson-annotations / jackson-core / re2j —— I7 成立

$ grep -rn "import org.springframework" --include="*.java" mos4j-*/src/main   → OK: no spring
$ grep -rn "import java.util.regex"        --include="*.java" mos4j-*/src/main   → OK: no java.util.regex
```

## J3 期间改掉的「会静默出事」的问题

| 问题 | 后果 | 修复位置 |
|---|---|---|
| 用 `normalize()` + `toRealPath()` 判越界 | `normalize` 是纯字面操作，遇到符号链接就撒谎：`root/link/../x` 字面是 `root/x`，真实是「link 指向目录的父目录/x」——越界路径被当成合法路径放行 | `SandboxedFs` 改为**逐段解析**（走一段、解一段、每段确认仍在根内）+ `SandboxedFsTest.l3_12_*` |
| 读文件不校验 jail | 根内一个指向 `/etc/passwd` 的 symlink 就能读出边界（写会拦、读不会） | `resolveExisting()` 同样走 Realpath + `SandboxedFsTest.l3_12_readAlsoGoesThroughRealpath` |
| 观察门把「事实」和「文案」混在一起 | 文案散落各写一遍，迟早有一处漏掉「不许绕」半句；而且基础设施层被迫知道模型话语 | `ObserveGate.Verdict` 只给原因码，文案由 `FileOps` 按 `API.md` §4 拼装 |
| `edit` 提供批量替换 | 替模型猜「改哪一处」是数据损坏的常见来源 | `EditFileTool` 改为**唯一命中**，多处命中报错（`FileToolsTest.editReplaceAllIsNotOffered`） |
| shell 工作目录可由请求指定 | 模型可以把命令目录指向 jail 之外 | `LocalRunner` 的工作目录由宿主固定，请求里**没有**可覆盖的字段 |
| 非零退出码当错误 | 模型会自己写 hack 绕开（`; echo "exit=$?"` 把失败掩进输出） | `ShellTool` 把非零退出当**正常结果**，末尾附 `[exit code: N]`（`ShellToolTest.l3_18_*`） |
| 环境变量全量透传 | 宿主密钥会悄悄进到模型发起的子进程里 | `LocalRunner` 默认只透传 `PATH/HOME/LANG/LC_ALL/TMPDIR/USER/LOGNAME` 白名单 |
| 输出超限后停止读管道 | 子进程写满管道后**阻塞**，命令永远不退出，超时形同虚设 | 超限后仍继续消费管道并丢弃多余字节 |
| `sandbox-exec` 不可用时「降级成没隔离」 | 看起来有隔离，实际没有 | 应用失败时命令直接失败（fail-closed）；`SeatbeltScope.description()` 写明这一点 |
| macOS 假装 `ulimit -v` 生效 | 假装生效比不设更坏 | `ResourceLimits` 在 macOS 跳过 `-v`，并在 `skipped()` 里显式报告 |
| 写入侧不拒 NUL 字节 | 写进去的文件自己都读不回来（读工具当二进制拒），文件变成「写进去就废了」的黑洞 | `TextContent.requireNoNul` + `FileToolsTest.writingNulBytesIsRejected` / `editingInNulBytesIsRejected` |

## 未完成 / 待决策

| 项 | 说明 | 影响 |
|---|---|---|
| Seatbelt 强制效果的真机验证 | 当前环境 `sandbox_apply: Operation not permitted`；用例 `SandboxBoundaryTest.seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt` 如实标为跳过 | T3.9 只算**部分完成**；在允许应用 Seatbelt 的 macOS 上重跑即可点亮 |
| 内置工具名与 Go 侧 builtin 的逐字对照 | ✅ 已核对（2026-11-06，Go 只读授权后）：Go = `read`/`write`/`edit`/`shell`，Java 当时 = `read_file`/`write_file`/`edit_file`/`shell`；**且参数形状也不一致**（`edit`/`grant_access`/`grep`/`shell`） | 只改名会造出「假兼容」，故**暂未改名**，改为待人工裁决（A 对齐 / B 记差异）→ **裁决已下并落地（B13-1/2/3，2026-11-06）**：工具名改为 `read`/`write`/`edit`，`edit` 改 `edits[]`，`grep`/`glob`/`ls`/`shell` 补参数；`grant_access` 与 deferred 两步记为有意差异（B13-4/5）。见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §3.2 与 `REVIEW-SHEET.md` §5 B13 |
| 文件工具回执是**纯文本**，不是结构化字段 | Go 侧回执字段逐字对照需授权；Java 侧先用文本回执（内容 + 路径 + 字节数） | 拿到授权后核对 Go 侧回执是否结构化；若需要，补结构化字段（并同步 `fieldnames.json` 冻结清单——不在清单里的字段不许出现） |
| G2 / G3 人工评审 | 机械项全绿；**语义正确性**（拒绝强度、边界口径）需维护者确认 | G2 / G3 门未过 |
| Go 侧原测试名逐条抄录（T2.11） | ✅ 已完成（2026-11-06）：137 个真实 Go 测试名逐条登记，机器复核全部存在 | 见 [`INVARIANT-TESTS.md`](INVARIANT-TESTS.md) §1 |
| `CancellationToken` 未贯穿 driver → planner | 取消目前只在模型 / HTTP 层生效 | 协作式取消的边界仍写在 `Describe()` 里 |
| 模块边界断言（T-INF.3） | 共享值类型类身份、模块边界检查未写 | CI 的 C3 门仍缺位 |
| `mos4j-sdk` 门面 | 仍是空模块（T6.10） | 宿主只能直接用 `Agent.of(Config.builder()...)` |
| 压缩器 | 预算触发 / 切割点 / previous-summary | **J4 已补齐**（见 `J4-STATUS.md`）：L2-9/11/12 已绿，L2 16/16 |
