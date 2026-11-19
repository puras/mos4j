# model —— 纯模型示例

直连 `ChatModel` 对接模型能力：不过门面、没有工具、没有会话。

## 这个示例讲什么

模型能力对接的最小面就是 `ChatModel` 的两个方法——`chat`（非流式）与
`chatStream`（流式），外加 `capabilities()`。没有工具就没有工具调用循环，
没有会话就没有事件日志：一个请求进，一个响应出，状态归宿主
（想带历史就把上一轮的两条消息留着拼进下一次请求）。

分层口径：`ChatModel` 协议无关——同一个调用面，换协议只是换装配
（OpenAI / Anthropic / Gemini 各自的 Config + ChatModel）。能力缺口 fail-closed：
要流式就先问 `supports(Capability.STREAMING)`，不支持的协议直接不用，
不「试试看能不能用」。

调用面之外还有**装配面**（`ModelSurface`）：协议选择走注册表、横切能力用装饰器显式叠加。

**配置全部是 Java 常量**（`ModelConfig`）：协议、密钥来源、基址、模型名、提示词、
循环命令词都在一处，取值顺序是「常量 → 同名环境变量 → 协议缺省」。

**循环对话**（`ModelChatLoop`）：纯模型层没有会话，所以循环对话就是宿主自己留历史——
每条 `user` / `assistant` 消息攒在宿主的一个列表里，每轮整段带上。

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini          # 可省略，缺省看 ModelConfig

# 用例（不碰网络）：脚本化替身真跑一遍调用面
./mvnw -Pexamples -pl examples/model -am test

# 真跑：一遍走完调用面与装配面
./mvnw -Pexamples -pl examples/model -am package
java -cp "examples/model/target/classes:$(cat cp.txt)" \
     net.hegq.mos4j.examples.model.ModelExample "只回答：1+1=?"

# 循环对话（--loop）
java -cp "examples/model/target/classes:$(cat cp.txt)" \
     net.hegq.mos4j.examples.model.ModelExample --loop
```

`cp.txt` 是运行期 classpath（`mos4j-model` 及其传递依赖），示例不自带打包脚本，
最省事的是用 `mvn dependency:build-classpath` 生成。

换协议：把 `ModelConfig.PROTOCOL` 改成 `anthropic` / `gemini`
（分别要配 `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`）。

## 循环对话怎么用

进 `--loop` 之后是一个 `> ` 提示符接着一个提示符，一行一个问题：

| 输入 | 作用 |
| --- | --- |
| 任意文本 | 问一句；宿主把这一轮追加进历史，下一轮整段带上 |
| `/history` | 看当前历史条数与轮数 |
| `/reset` | 清空历史（系统提示词留着） |
| `/exit`、`/quit`、Ctrl+D | 退出 |

三条口径写在 `ModelChatLoop` 的注释里：**失败不进历史**（这一轮抛异常就什么都不追加，
可以原样重问）、**空回答不算回答**（模型既没正文也没工具调用时抛错且不追加，
否则下一轮会带着一条空 assistant 消息）、**历史不可变出去**（`history()` 返副本）。
单轮失败不会带走整个进程：打印原因、继续读下一行——真实调用会失败，
一轮失败就要从头再来是不能接受的。

历史只在进程内存里：不落盘、不压缩、不裁剪。那是会话层（`mos4j-session`）的活，
纯模型示例刻意不碰。

**流式**：`ModelConfig.STREAM_IN_LOOP`（默认 `true`）决定循环走 `askStream` 还是 `ask`。
开了流式之后增量会**实时打到注入的 `PrintStream`**（不是 `System.out`——否则终端看着正常、
用例里一个字都没有）；模型不支持流式时**自动退回非流式**，开头一行 `[模式]` 说清是哪一种（不静默换挡）。
流式与历史的交接口径不变：仍是聚合后的正文进 `commit`，空回答依旧不算一轮。

## 想看就把配置改这里（`ModelConfig`）

所有可调项都是 `public static final` 常量，改完重编译就生效，不用改任何逻辑代码：

| 常量 | 作用 |
| --- | --- |
| `PROTOCOL` | 用哪个方言（`openai` / `anthropic` / `gemini`） |
| `OPENAI_API_KEY` / `_BASE_URL` / `_MODEL` | OpenAI 方言的三个连接项 |
| `ANTHROPIC_*` / `GEMINI_*` | 另两个方言的同名三项 |
| `DEFAULT_*_MODEL` | 常量与环境变量都没给时的缺省模型名 |
| `ENV_*` | 环境变量名（唯一真源；示例不读 `.env`） |
| `SYSTEM_PROMPT` | 系统提示词（system 消息就是消息列表第一条） |
| `CMD_EXIT` / `CMD_QUIT` / `CMD_RESET` / `CMD_HISTORY` / `PROMPT` | 循环对话的命令词与提示符 |
| `STREAM_IN_LOOP` | 循环对话是否用流式（`true`；模型不支持时自动退回非流式） |
| `REQUEST_TIMEOUT` / `CALL_TIMEOUT` | 请求超时与装饰器演示里「每次尝试」的超时 |

**密钥常量默认留空是刻意的**：把真密钥写进源码，它会跟着 git、jar、日志一起传播。
`Provider.toString()` 会给密钥打码（`***`）——示例代码不该有「一 print 就把密钥打进日志」的形状。

填充密钥有三种写法，按需要选：

```java
// 1. 直接填常量（本地临时测试；别提交）
public static final String OPENAI_API_KEY = "sk-...";

// 2. 留空 + 环境变量（推荐）
export OPENAI_API_KEY=sk-...

// 3. 换成你自己的密钥来源（配置中心 / KMS / 启动参数）
//    改 ModelConfig.provider(...) 里那一行 getenv 就行
```

## 看什么

### 调用面（`ModelExample`）

- **非流式**：`chatOnce` —— 消息列表（system + user）进，响应出；打印结束原因与
  token 用量（`freshInput()` 是跨协议唯一可比的输入口径）。
- **流式**：`chatStream` —— 增量块边收边打（终端实时出字），返回值是聚合后的完整文本；
  不支持流式的模型在调用前就拒绝（fail-closed）。
- **多轮**：`multiTurn` —— 状态归宿主，上一轮的两条消息拼进下一次请求。
- **取消**：`chatWithDeadline` —— 宿主给 `CancellationToken`，协作式检查点如实抛
  取消；期限多久、何时取消归宿主决定。

### 装配面（`ModelSurface`）

- **装配**：`registry()` —— 名字 → 工厂（`api_key` / `base_url` / `model` 三个键）。
  未注册的名字 fail-closed 并列出已注册的名字，不静默挑一个默认协议。
  所以「换协议」不是改代码里的 `switch`，而是改配置里的一个名字。
- **目录**：`catalog(...)` —— 每项**kind 与句柄一起报**（`ModelOption.chat(...)`）。
  今天只有 `CHAT` 这一 kind 有装配面，embedding / rerank / 图像 / 视频随
  `protocol.<方言>.<kind>` 逐个落地；「kind + 泛型句柄」这层不用再动。
- **横切能力**：`decorate(...)` —— 组合顺序即执行顺序，缺省叠法是
  观测（最外）→ 降级 → 重试 → 超时 → 裸协议模型。理由写在方法注释里：
  观测在内层会让一次重试留下多条日志（事后分不清「模型真调了三次」与「框架重试了三次」），
  超时在外层会让一次超时打死整条重试链。
- **缓存**：`withCache(...)` —— 只缓非流式、失败不缓存、取消优先于命中。它**不**在缺省叠层里：
  缓存改变了「同样的请求第二次还会不会打服务端」，这是宿主该明确决定的事。

### 为什么这里没有异步任务与产物

模型模块还有两条底座：异步任务（`TaskHandle` / `TaskStatus` / `TaskPoller`，提交 → 轮询 → 取产物）
与产物（`Artifact` 的 `InMemory` / `OnDisk` 两种形态）。**它们不在这个示例里，因为
chat 不走那条路**：`ChatModel.chat()` 是同步的「一次请求换一次响应」，没有任务号、没有轮询。
那两条底座服务的是图像 / 视频生成、文档解析这类 kind。

在 chat 示例里演一段假状态转移、配一张编造的 PNG，就是「假装它们能跑」——
所以上一版里的 `printAssembly` 异步段与 `ScriptedTaskPoller` 已经**删掉**。
它们的语义由 mos4j-model 自己的 `TaskHandleTest` / `ArtifactTest` 守；
真场景随第一个非 chat kind 落地时单独出示例（届时复用同一套「刷新一次归框架、
节奏与超时归宿主」的口径）。

## 边界

- 示例不读 `.env`：密钥、基址、模型名都从环境变量取（与 `Mos4j.defaultModel` 同名单），
  常量优先于环境变量（想钉死一个值就填 `ModelConfig` 里的常量）。
- **历史只活在进程内存**：`ModelChatLoop` 不落盘、不压缩、不裁剪——存续与裁剪是会话层的活。
- **循环对话不做重试**：一轮失败就如实打印原因、历史不变、继续下一行；重不重试是宿主决定
  （要重试就在 `decorate` 里叠，别在循环里默默重发）。
- 真实调用失败就如实失败：网络错误、协议错误不降级、不静默吞。
- **横切能力默认不打开**：主程序装的是裸模型（一次输入 = 一次调用）；重试会改变
  调用次数语义，所以由宿主在 `decorate` 里显式打开。
- **只有 chat 有装配面**：embedding / rerank / 图像 / 视频 / TTS / ASR / Realtime 目前只有
  kind 与公共底座（`ModelKind` / `Artifact` / `TaskHandle` / `TaskPoller`），语句层适配器还没落地。
  **这些底座不在本示例的代码里**（包括异步轮询与产物处理）——chat 不走那条生命周期，
  在本示例里演示它们就是假装它们能跑。
- 报文级的完整协议语义（工具调用、思维链、用量合并等）由 mos4j-model 自己的
  L1 语料用例守；本示例的用例只抽样最常用的一条通路，证明「换协议只是换装配」。
