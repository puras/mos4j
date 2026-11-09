# mcp —— 接一台 MCP 服务器

三档曝光（DIRECT / DEFERRED / HIDDEN）、按 Go 规则命名、失败看得见。

## 这个示例讲什么

- **官方 SDK 是 optional 依赖**：MCP 接入已并入 `mos4j-tool`（在默认六模块里），
  但官方 MCP SDK 在那里是 `optional`（传递依赖不进 SDK 依赖基线）——
  **本示例自己显式声明官方 SDK 坐标**，再 `attach` 一次即可。
- **工具命名有规矩**：`mcp__<服务器>__<工具>`（与子代理的 `subagent__<名字>`
  同一套命名空间纪律）。
- **三档曝光**：DIRECT（直接进模型清单）/ DEFERRED（要 `tool_search` 才激活）/
  HIDDEN（模型看不见，但程序化调用仍可）。
- **失败要看得见**：连不上的服务器、没就绪的工具都写进装配快照，不静默消失。
- **上游 `isError` 必须变成工具失败**：上游说失败，就是失败，不当成功文本回给模型。

## 自带一台服务器

示例里带了一台最小 MCP 服务器（`EchoMcpServer`，stdio 子进程，只有一个
`echo` 工具）——不装任何外部 MCP 服务器也能真连一次。

接真实服务器：把 `McpExample.echoServer()` 换成

```java
McpServerConfig.stdio("fs", "npx",
        List.of("-y", "@modelcontextprotocol/server-filesystem", dir))
```

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

./mvnw -Pexamples,mcp -pl examples/mcp -am test

java -cp ... net.hegq.mos4j.examples.mcp.McpExample
```

## 看什么

- 快照：`echo：CONNECTED（直接 1，延迟 0，隐藏 0）`。
- 工具面里有 `mcp__echo__echo`。
- DIRECT 的工具进模型清单，DEFERRED 的不进。

## 边界

- MCP 接线在**工具层**（`mos4j-tool` 的 `net.hegq.mos4j.mcp` 包）与**装配层**（`Config` / `Agent`），
  不进 SDK 门面——门面要是引官方 SDK，它的传递依赖就进了六模块基线。
- 服务器说明每会话取一次（连接会掉，文本不能拍死）。
- 逐工具曝光覆盖顺序：精确工具名 > `"*"` > 服务器默认。
