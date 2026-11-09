# MoS4J 示例集

这些示例是**给人读的装配说明书**：每个示例一个 Maven 模块，讲清一件事，
并且带一个**不碰网络**的用例（用脚本化模型替身真跑一遍装配与循环）。

示例**不在默认 reactor 里**（`./mvnw verify` 不带它们），避免稀释
「一次依赖拿到能跑的智能体」这条交付承诺。

## 构建

```bash
# 六个不依赖 MCP 的示例
./mvnw -Pexamples verify

# 连 MCP 示例一起（需要官方 MCP SDK 的构件在本机仓库里）
./mvnw -Pexamples,mcp verify
```

MCP 示例要 `-Pexamples,mcp`：`mcp` 是 examples 侧的**局部 profile**（只负责把 MCP 示例模块加进示例 reactor）。
MCP 接入本身已并入 `mos4j-tool`（在默认 reactor 里），官方 MCP SDK 在那里是 **optional 依赖**——示例自己显式声明官方 SDK 坐标。

## 目录

| 模块 | 讲什么 | 主程序 |
| --- | --- | --- |
| [minimal](minimal/README.md) | 一次装配，一个问题，一句回答 | `net.hegq.mos4j.examples.minimal.MinimalExample` |
| [chat](chat/README.md) | 多轮会话、审批问人、策略 | `net.hegq.mos4j.examples.chat.ChatExample` |
| [agents](agents/README.md) | 多智能体：拆解 → 执行 → 验收 | `net.hegq.mos4j.examples.agents.AgentsExample` |
| [skills](skills/README.md) | 技能目录：清单进提示词、正文现读 | `net.hegq.mos4j.examples.skills.SkillsExample` |
| [subagent](subagent/README.md) | 子代理：点名派活、固定 JSON 回执 | `net.hegq.mos4j.examples.subagent.SubAgentExample` |
| [recovery](recovery/README.md) | 崩在半路：派生现场 + 显式续跑 | `net.hegq.mos4j.examples.recovery.RecoveryExample` |
| [mcp](mcp/README.md) | 接一台 MCP 服务器（三档曝光） | `net.hegq.mos4j.examples.mcp.McpExample` |

共享的测试替身在 [testkit](testkit)（脚本化模型 `StubModel`）。

## 跑起来要什么

示例**不读 `.env`、不解析环境变量表**：模型由宿主给（门面按协议 + 环境变量装，
或者直接 `.model(...)` 注入）。所以要真跑，只需要：

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini
```

每个模块的 README 里写了具体怎么跑。

## 一条纪律

示例里的降级**必须看得见**：这台机器装不了路径强制（受管 Mac 常常如此）时，
框架在装配期报错而不是退化成「没隔离的假装隔离」；示例里用
`MOS_NO_PATH_SCOPE=1` 显式关掉它，代价是 shell 不再受路径强制保护——
这条代价会写进装配快照的 caveats 里。
