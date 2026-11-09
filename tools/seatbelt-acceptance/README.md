# macOS Seatbelt 强制效果真机验收包

> **为什么单独一份**：仓库里那条自动化用例
> `SandboxBoundaryTest.seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt`
> 只能在「允许应用沙箱」的 macOS 主机上从**跳过**变成**通过**；
> 在受限主机（如开发机的 `sandbox_apply: Operation not permitted`）它只能如实跳过——
> **跳过不是验收**。这个目录把同一件事做成「拿到另一台 macOS 上就能直接跑」的形态。

## 0. 前置条件

- 一台 **macOS** 主机，且**宿主允许应用沙箱**（不是「装了 Xcode 工具链」就算）。
- JDK 25（本仓库的 `--release 25` 要求）。
- 仓库根目录（命令都在仓库根执行）。

先判断这台机器能不能应用沙箱（一条命令，0 = 可以）：

```bash
/usr/bin/sandbox-exec -p '(version 1) (allow default)' /usr/bin/true; echo "exit=$?"
```

- `exit=0` → 可以验收，往下走；
- `exit=71`（`sandbox_apply: Operation not permitted`）→ 这台机器**不能**验收（受限宿主），换机器；
- `exit=65` → profile 解析失败（实现缺陷，不是环境问题），把输出发回来。

## 1. A 组：自动化用例（推荐先跑这个）

```bash
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B \
  -pl mos4j-runtime -am test \
  -Dtest='SandboxBoundaryTest#seatbeltEnforcementIsVerifiedWhenThePlatformAllowsIt+seatbeltProfileIsAcceptedByTheKernelParser+seatbeltProfileSyntaxErrorsAreDetectable' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

判读：

| 结果 | 含义 | 下一步 |
|---|---|---|
| 3 条全 `passed` | **强制效果已验证**（含 profile 语法正反例） | 回填 §4 |
| 强制效果那条 `skipped` | 这台机器仍不允许 apply（退出码 2 的那种情况） | 换机器；这条**不算通过** |
| 强制效果那条 `failed` | 沙箱生效了但写根外**仍被写进去**（或命令没跑起来） | 这是**真失败**，把输出发回来 |

> 这条用例的判定是两条独立事实：①命令**真的跑起来了**（写根内的 marker 存在）；
> ②写根**外**的文件没被创建。只有 ①成立时 ②才有意义——否则「没写」只是因为「没跑」。

## 2. B 组：真机探针脚本（同一件事的可复制形态）

```bash
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -pl mos4j-runtime -am -DskipTests package
java -cp mos4j-runtime/target/classes tools/seatbelt-acceptance/SeatbeltAcceptance.java /tmp/mos4j-accept
```

退出码口径：

| 退出码 | 含义 |
|---|---|
| **0** | 强制效果已验证（写根内可写 + 写根外被拦） |
| **1** | 强制**没有**生效（真失败） |
| **2** | 未验证（这台机器不允许应用沙箱，或命令没起来）——**不是失败** |

脚本会打印逐项结论（探测结果、命令退出码、三条判定），把那段输出原样贴回来即可回填文档。

## 3. C 组：上层链路的真机抽检（可选）

缺省装配（SDK / Starter）在能应用沙箱的机器上会**真的把路径强制装上**，
所以这一组能顺带证明「装配 → 探测 → 命令 → 边界」整条链：

```bash
# 探测（应返回 available=true）
java -cp mos4j-runtime/target/classes tools/seatbelt-acceptance/SeatbeltAcceptance.java /tmp/mos4j-accept
```

（SDK/Starter 层的装配口径已有自动化用例覆盖：`PathScopeRuntimeTest`、`Mos4jTest`、
`Mos4jStarterKnobsTest`；真机差异只在「apply 是否被宿主允许」这一处。）

## 4. 结果回填清单（跑完按这个改，别只改一处）

| # | 位置 | 改成什么 |
|---|---|---|
| 1 | `docs/contract/J8-STATUS.md` §2 能力矩阵 macOS 行 | 「强制效果未验证」→「✅ 已验证（日期 + 主机口径）」 |
| 2 | `docs/contract/J8-STATUS.md` §5 机械验证表 | 补一行「真机强制效果验收」的真实输出 |
| 3 | `docs/contract/J8-STATUS.md` §6 诚实边界第 1 条 | 删掉/改写「强制效果仍未验证」 |
| 4 | `docs/contract/J8-STATUS.md` §7 下一步 | 「验证 macOS 强制效果」标为 ✅ 完成 |
| 5 | `docs/contract/REVIEW-SHEET.md` §5 B3 | ⚠️ 需人做 → ✅ 已验收（贴证据） |
| 6 | `docs/contract/REVIEW-SHEET.md` §8 机械门 | 跳过数从 7 变 6（那条用例不再跳过），基线数字同步 |
| 7 | `docs/contract/INVARIANT-TESTS.md` §4 | 「Seatbelt 强制效果的真机验证」行：⏸ → ✅ |
| 8 | `README.md` 状态段 | 「强制效果在本机仍拿不到」→「已验证」 |
| 9 | `docs/mos4j-tasks.md` T8.9 | `[ ]` → `[x]` |

## 5. 本机（受限 macOS）的对照结果

开发机（宿主拒绝 apply）的实测输出，用于对照：

```
[1] 探测: available=false detail=宿主不允许应用 macOS 沙箱（sandbox_apply: Operation not permitted）
→ 这台机器不允许应用 Seatbelt
退出码 2：未验证（不是失败）
```

自动化用例在同机上是 **skipped**（不是 passed）——两条路径都如实说「未验证」。
