# 发布收口（MoS4J）

> **状态**：发布配置**部分就绪**——`release` profile 已配好**源码附件 / javadoc 附件 / GPG 签名**，
> `internal-publish` profile 已配好**内部私服的 `distributionManagement`**（地址与仓库 id 由命令行注入，不写死在仓库里），
> POM 已补 **`<scm>`** 与 **`<licenses>`**（MIT，2026-11-07 用户拍板）；
> **仍缺**：**私服地址与账号**（环境属人，不入库）、**人工评审签字**、**Seatbelt 真机验收**。
> **Maven Central 暂缓**（2026-11-07 用户裁决：先发内部私服）——因此不写 OSSRH / Central Portal 的插件与地址。
> 本文是**发布前的清单与步骤**，不是「已经发布过」的记录。
> **版本号的唯一真源是父 POM 的 `<version>`**（`net.hegq:mos4j:0.1.0-SNAPSHOT`）；
> `net.hegq.mos4j.core.Version.VERSION` 是它的对外投影，由 `VersionTest` 机械对齐。

## 1. 发布物清单

| 模块 | 是否发布 | 说明 |
|---|---|---|
| `mos4j`（父 POM） | ✅ | 依赖管理 + 插件配置 |
| `mos4j-core` … `mos4j-agent`（6 个） | ✅ | SDK 本体，是发布主体 |
| `mos4j-spring-boot-starter` | ✅（按需 profile） | Spring Boot 薄封装；构建用 `-Pstarter` |
| `mos4j-examples`（8 个模块：7 个示例 + `testkit`） | ✅（`examples` profile） | 示例集；构建用 `-Pexamples`（MCP 示例再叠 `-Pexamples,mcp`） |

> 按需模块**不进默认 reactor**，但**要发布**：宿主用不用由它决定，坐标必须能解析到。
> 三个 profile 都要构建（`verify` 一次不够，`deploy` 要把 `starter` / `examples` / `examples,mcp` 三套产物都推上去）。

## 2. 版本策略

- 语义化版本：`MAJOR.MINOR.PATCH`；预发布用 `-SNAPSHOT`（当前 `0.1.0-SNAPSHOT`）。
- **改版本号的地方只有两处**：父 POM `<version>` 与 `mos4j-core` 的 `Version.VERSION`。
  漏改后者 → `VersionTest.theVersionMatchesTheParentPom` 变红（发布前必过）。
- **不把构建时间写进版本号**：同样的代码必须得到同样的版本号（可复现构建）。

## 3. 发布前必须过的机械门（全部实跑，不看「应该没问题」）

```bash
# 1) 四套 reactor（默认 / starter / examples / examples+mcp）
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pstarter clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pexamples clean verify
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -Pexamples,mcp clean verify

# 2) 依赖纪律：6 个 SDK 模块「声明即使用」
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B -am \
  -pl mos4j-core,mos4j-model,mos4j-runtime,mos4j-tool,mos4j-session,mos4j-agent \
  dependency:analyze -Dmdep.analyze.failOnWarning=true

# 3) 源码纪律：SDK 模块禁 Spring / 禁 java.util.regex（6 个模块，main + test）
! grep -rn "import org.springframework" --include="*.java" mos4j-*/src/main mos4j-*/src/test
! grep -rn "import java.util.regex"   --include="*.java" mos4j-*/src/main mos4j-*/src/test

# 4) 冻结清单：80 条 / 71 名 / 名字集合摘要 3d35911d…（由 FieldNameContractTest 守）
```

**当前基线**（2026-11-08 六模块合并后实跑）：默认 reactor **846 通过 / 0 失败 / 0 错误 / 7 跳过**；
`-Pstarter` **870 / 7**；`-Pexamples` **871 / 7**；`-Pexamples,mcp` **874 / 7**。
（MCP 接入已并入 `mos4j-tool`，进入默认 reactor；examples 自身分别为 25 与 28 条。）

## 4. 还缺什么（发布配置的缺口，需要人拍板）

| 缺口 | 现状 | 补法 | 谁来做 |
|---|---|---|---|
| **许可证** | ✅ **已定并落地**（2026-11-07 用户拍板 **MIT**）：仓库根的 `LICENSE` + POM `<licenses>` 元数据 | — | — |
| **发布通道** | ✅ **已定方向**（2026-11-07 用户裁决：**先发内部私服**，Maven Central 暂缓）；`internal-publish` profile 已配 `distributionManagement`，**地址与仓库 id 由 `-D` 注入**，仓库里不写死环境信息；未注入时 deploy **fail-closed** | 发布时传 `-Dmos4j.distribution.releaseUrl=… -Dmos4j.distribution.snapshotUrl=…`（见 §5） | 需人提供私服地址 |
| **私服凭证** | 走 `~/.m2/settings.xml` 的 `<servers>`（**不入库**）；`<server><id>` 需与仓库 id（默认 `internal-releases` / `internal-snapshots`）对得上 | 需人提供账号 / token | 需人提供 |
| SCM 元数据 | ✅ **已补**（`<scm>`：git + ssh + 网页地址） | — | — |
| 源码 / javadoc 附件 | ✅ **已配**（`release` profile：`maven-source-plugin` 3.3.1 + `maven-javadoc-plugin` 3.6.3，`doclint=none`） | 发布前人扫一眼生成的 javadoc | — |
| GPG 签名 | ✅ **已配**（`release` profile：`maven-gpg-plugin` 3.2.4），但**需要本机有 gpg 与密钥** | 密钥**不入库**（环境属人）；私服不校验签名时可 `-Dgpg.skip=true` | 需人提供密钥（或决定跳过） |
| 人工评审签字 | `REVIEW-SHEET.md` 仍有 **86 条待签** | 评审人逐行勾 | **必须是人做** |
| Seatbelt 强制效果真机验收 | 本机 `sandbox_apply: Operation not permitted`（7 条跳过里含它） | 在允许 `sandbox-exec` 的 macOS 上跑 `tools/seatbelt-acceptance/` | **必须是人做（换环境）** |

> 上面「需人提供」的（密钥 / 账号 / 私服地址）**不写死在仓库里**：凭证属环境，属人，不属代码。
> `release` 与 `internal-publish` 两个 profile **默认都不激活** → 默认构建零影响（附件与签名只在发布时生成）。

## 5. 发布步骤（发内部私服）

```bash
# 0) 一次性准备（环境侧，不入库）：
#    ~/.m2/settings.xml 里配 <server>，id 与下面的仓库 id 对得上：
#      <id>internal-releases</id>  <username>…</username>  <password>…</password>
#      <id>internal-snapshots</id> …

# 1) 定版本：改父 POM <version> 与 Version.VERSION（两处都要改，测试会替你抓漏）
# 2) 四套 profile 跑一次 verify（门全绿）
# 3) 带签名与源码附件发布（release 管附件/签名，internal-publish 管上传地址，两个 profile 正交）
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B \
  -Prelease,internal-publish \
  -Dmos4j.distribution.releaseUrl=https://nexus.example.internal/repository/maven-releases/ \
  -Dmos4j.distribution.snapshotUrl=https://nexus.example.internal/repository/maven-snapshots/ \
  clean deploy
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B \
  -Prelease,internal-publish -Pstarter \
  -Dmos4j.distribution.releaseUrl=… -Dmos4j.distribution.snapshotUrl=… \
  clean deploy
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B \
  -Prelease,internal-publish -Pexamples \
  -Dmos4j.distribution.releaseUrl=… -Dmos4j.distribution.snapshotUrl=… \
  clean deploy
MAVEN_USER_HOME=$PWD/.m2repo ./mvnw -Dmaven.repo.local=.m2repo -B \
  -Prelease,internal-publish -Pexamples,mcp \
  -Dmos4j.distribution.releaseUrl=… -Dmos4j.distribution.snapshotUrl=… \
  clean deploy
# 4) 用空项目验证可解析（把 -Dartifact 的版本换成实际发布的版本）：
#    mvn dependency:get -Dartifact=net.hegq:mos4j-agent:0.1.0
```

> **为什么地址靠 `-D` 注入**：`mos4j.distribution.releaseUrl` / `snapshotUrl` 在父 POM 里**故意没有默认值**——
> 没传就带着未解析的属性名失败（fail-closed），不会默默发到某个地址。仓库 id 有默认值
> （`internal-releases` / `internal-snapshots`），可按私服实际布局用 `-Dmos4j.distribution.releaseRepoId=…` 覆盖。
> **签名**：`maven-gpg-plugin` 需要本机 `gpg` 与密钥（密钥不入库）；私服不校验签名时加 `-Dgpg.skip=true`。
> **Maven Central 暂缓**：若日后要发 Central，需重新核实当时的官方通道（OSSRH staging 与 Central Portal 的插件不同），
> 那时再加一个 profile，**不要**把内部私服的地址替换成 Central 地址。

## 6. 发布说明

见 [`CHANGELOG.md`](../CHANGELOG.md)（0.1.0 的条目已按批次写好，发布时补发布日期与 tag 链接）。
