# Spring Boot Starter（按需模块）

> **定位**：MoS4J 的 SDK 是本体；starter 只是把「装配」翻译成 Spring 的样子——
> **不实现任何智能体语义**。语义（裁决顺序、载荷冻结、fail-closed、Describe 快照）全部还在
> `mos4j-driver` / `mos4j-execpath` / `mos4j-hook` 里。starter 如果自己写一套执行语义，
> 就出现了第二份真相——那比没有 starter 更危险。
>
> **不进默认 reactor**：SDK 优先。非 Spring 宿主不该被拖入 Spring；
> 默认 `./mvnw verify` 只构建 12 个 SDK 模块，starter 用 `-Pstarter` 带上。

---

## 1. 依赖与构建

```xml
<dependency>
  <groupId>net.hegq</groupId>
  <artifactId>mos4j-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```bash
./mvnw -Pstarter verify        # 构建 + 测试 starter（CI 里是独立 job）
```

版本由 Spring Boot BOM 管理（BOM 版本在父 POM 的 `spring-boot.version` 属性里）。

---

## 2. 最小用法

```yaml
mos4j:
  enabled: true
  protocol: openai
  api-key: ${OPENAI_API_KEY}
  model: gpt-4o-mini
  workspace: /srv/app
  log-dir: /var/lib/app/mos4j/sessions
```

```java
@Service
public class ChatService {

    private final Mos4jRunner runner;

    public ChatService(Mos4jRunner runner) {   // 自动装配
        this.runner = runner;
    }

    public String ask(String userId, String question) throws IOException {
        return runner.run("user-" + userId, question);   // 会话按 id 自动开 / 恢复
    }
}
```

自动装配出来的 Bean：

| Bean | 用途 |
|---|---|
| `Mos4j` | SDK 门面（装配快照、session 存取） |
| `Agent` | 可跑的智能体（等价于 `mos4j.agent()`） |
| `JsonlPersistence` | 会话持久化（append-only 日志） |
| `Mos4jRunner` | 跑一轮 turn（会话按 id 自动开/恢复；容器关闭时取消在途请求） |

宿主自己提供 `ChatModel` / `ToolRegistry` / `ApprovalChannel` / `GuardBus` / `HookBus` /
`PostHook` Bean 时，starter 会优先用宿主的（`@ConditionalOnMissingBean` 让宿主能整体覆盖）。

---

## 3. 配置项全表

| 属性 | 缺省 | 说明 |
|---|---|---|
| `mos4j.enabled` | `true` | 总开关。`false` 时**一个 Bean 都不给**（状态可见，不是半残的） |
| `mos4j.protocol` | `openai` | `openai` / `anthropic` / `gemini` |
| `mos4j.api-key` | 环境变量兜底 | 优先属性，其次 `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`（宿主自带 `ChatModel` 时不需要） |
| `mos4j.base-url` | 协议官方地址 | 兼容网关在这里给 |
| `mos4j.model` | 环境变量 → 协议常用名 | 模型名 |
| `mos4j.system-prompt` | 无 | 系统提示词 |
| `mos4j.workspace` | 当前目录 | 文件工具的 jail 锚点（**会被绝对化**：相对路径会让边界随进程 CWD 漂移） |
| `mos4j.log-dir` | `.mos4j/sessions` | 会话日志目录 |
| `mos4j.file-tools` | `true` | 是否装配文件四件套（read/write/edit/shell） |
| `mos4j.concurrency` | `0` | 工具并发上限（0 = 沿用驱动缺省；并发始终显式 opt-in） |
| `mos4j.max-decision-rounds` | `0` | 单轮决策次数上限（0 = 不限） |
| `mos4j.stream` | `false` | 是否走流式决策 |
| `mos4j.approvals.required` | `false` | 要求人工审批；`true` 时容器里没有 `ApprovalChannel` 就启动失败 |
| `mos4j.output.enabled` | `true` | 工具输出限流（行/字节双限） |
| `mos4j.output.max-lines` | `2000` | 行数上限 |
| `mos4j.output.max-bytes` | `524288` | 字节上限（含截断标记本身） |
| `mos4j.output.spill-dir` | 无 | 截断内容落盘目录（**相对 workspace**，且目录必须已存在；越界或没有可读回它的文件工具 → 启动失败） |
| `mos4j.compaction.context-window` | `0` | 上下文窗口（token）；**0 = 不配置 = 不压缩** |
| `mos4j.compaction.reserve-tokens` | `1024` | 给回答预留的 token |
| `mos4j.compaction.keep-recent-tokens` | `4096` | 保留最近多少 token |
| `mos4j.skills.dirs` | 无 | 技能目录（多个逗号分隔；**相对路径按 `mos4j.workspace` 解析，不以进程 CWD 为锚**）；不配 = 不装技能（也不注册 `skill` 工具） |
| `mos4j.skills.max-body-bytes` | `65536` | 单个技能正文上限（字节）；超预算 → **调用时报错**，不回半截正文 |
| `mos4j.skills.max-catalog-bytes` | `8192` | 进系统提示词的技能清单字节预算；超预算多级降级（只列名 → 部分条目 + 省略数 → 最小提示 → 不注入） |

配置元数据（IDE 提示）由 `spring-boot-configuration-processor` 生成，产物在
`META-INF/spring-configuration-metadata.json`（**23 个属性**：J7 新增 `mos4j.skills.*` 三个）。

### 3.1 技能怎么用

```yaml
mos4j:
  workspace: ./work
  skills:
    dirs: skills,extra-skills   # ./work/skills 与 ./work/extra-skills
```

每个技能 = 一个目录 + 一个 `SKILL.md`：

```markdown
---
name: deploy          # kebab-case，≤ 64
description: 发布流程   # ≤ 1024；进系统提示词清单
allowed-tools: [shell] # 只诊不断（当前不生效），缺失时记 mismatch 事件
---

具体操作步骤……（正文在模型调 skill 工具时现读，不进快照）
```

- 装配时扫目录，清单进系统提示词 `skills` 段（`base/environment/context/skills/instructions/tools`）；
- **只有存在模型可见技能时才注册 `skill` 工具**（全部 `disable-model-invocation: true` = 不注册）；
- 每次加载记一条 `skill/activate`，声明工具不可用时另记 `skill/allowed-tools-mismatch`；两个事件**只进日志不进投影**；
- 技能目录是**数据**：运行中改了 `SKILL.md` 正文立即生效（现读）；**新增/删除技能**要宿主显式 `agent().reloadSkills()`，变化经 `notice()` 交给宿主补给模型（静态提示词不重渲染，保 prompt cache）；
- `/skill-name` 手势（用户输入首 token）：`agent().expandSkill(text)` 自行展开；SDK 不自动展开（误阳风险）。

---

## 4. fail-closed 口径（与 SDK 一致）

| 情况 | 行为 | 为什么 |
|---|---|---|
| 启用但没有 API 密钥（属性与环境变量都没有） | **启动失败**，报错指名 `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` | 等到第一次调模型才发现没钥匙，错误会看起来像「服务商故障」 |
| 宿主自带 `ChatModel` Bean | **不要求 API 密钥**（那个模型自己知道怎么认证，要一把用不上的密钥只是噪音） | 配置在骗人 |
| `mos4j.approvals.required=true` 但没有 `ApprovalChannel` Bean | **启动失败** | 不装配审批通道 = 需要人确认的调用全部 fail-closed 拒绝；运行期才炸就晚了 |
| `mos4j.enabled=false` | 不给任何 Bean | 唯一允许的「不装配」——宿主显式关的，状态可见 |
| `mos4j.output.spill-dir` 越界（`../` 逃出 workspace）或目录不存在 | **启动失败** | 事实被写到 jail 外面 = 边界形同虚设；默默写到别处 = 配置在骗人 |
| 配了 `spill-dir` 但没有可读回它的文件工具（宿主自带 `ToolRegistry` 或 `file-tools=false`） | **启动失败** | 模型读不到那个文件时，上下文里「完整内容在 X」是骗模型 |
| 未配置 `context-window` | 不装压缩 | 窗口只有服务商知道，猜了就是用错误的事实做决定 |

**没做**（诚实边界）：多轮 REPL、tty 审批交互、流式打点——starter 只提供「跑一轮」的能力，
交互形态由宿主自己搭（SDK 的 `Agent` 支持流式与取消）。

---

## 5. 审批通道怎么接

```java
@Bean
ApprovalChannel approvalChannel() {
    // 返回什么由宿主决定（例如推消息给人，或在 Web UI 上等）
    return request -> ApprovalResult.approved();
}
```

有了这个 Bean，starter 会把它接进 `Config`，`Agent.describe()` 里就不再有
「当前未装配审批通道」这条 caveat。
**注意**：批准**只对本次调用生效**（不缓存）——批准一次 ≠ 永久放行。

---

## 6. 取消

`Mos4jRunner` 每轮一个独立令牌；容器关闭时（`close()`）在途令牌全部取消。
取消是**协作式**的：正在执行的工具不会被强杀（见 `docs/contract/SELF-INVENTED.md` SI-1 / SI-24）。

```java
CancellationToken token = CancellationToken.create();
runner.run("s1", "长任务", token);   // 宿主可随时 token.cancel()
```

---

## 7. 测试

| 测试 | 条数 | 覆盖 |
|---|---|---|
| `Mos4jAutoConfigurationTest` | 16 | 缺省装配、显式关闭、缺密钥 fail-fast、环境变量兜底、宿主自带模型不要密钥、审批必装/接上、限流缺省装、压缩按窗口装/不装、落盘目录在 jail 内可用 / 越界失败 / 无可读工具失败、**技能：`dirs` 相对 workspace 解析 / 目录可选 / `max-body-bytes` 进清单** |
| `Mos4jStarterTurnTest` | 1 | **端到端真跑一轮**（真实 HTTP 回放 OpenAI 响应 + 会话落盘 + Header） |

> 合计 **17 条**（J7 后实测，`-Pstarter clean verify`）。

守护命令：

```bash
./mvnw -Pstarter -pl mos4j-spring-boot-starter -am test
```

---

## 8. 与依赖纪律的关系（I7）

- Spring **只**出现在这个模块；12 个 SDK 模块的运行时依赖仍然只有 Jackson 家族 / RE2J / SnakeYAML 系 / JDK。
- 父 POM 的 enforcer 门（禁 Spring / Guava / ORM / Lombok / SLF4J）对 SDK 生效；
  在 starter 里**不是整条关掉，而是改窄**：只放行 Spring，
  Guava / ORM / Lombok / SLF4J / Logback 照旧禁（starter 也没理由用它们）。
  窄化规则已做**负向验证**：临时加 `org.slf4j:slf4j-api` → 构建报
  `org.slf4j:slf4j-api:jar:2.0.13 <--- banned via the exclude/include list`（已回滚）。
- CI 的 C6 源码扫描用**显式的 12 模块列表**，不用 `mos4j-*/src/main` 通配：
  通配会把 starter 合法存在的 Spring import 判成违规。
