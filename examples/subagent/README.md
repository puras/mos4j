# subagent —— 子代理

点名派活，固定 JSON 回执。

## 这个示例讲什么

子代理就是**一个普通工具**：

- **清单**（name / description）在装配期进静态系统提示词——父模型进门就看得见
  有哪些人可派。
- **工具面收窄**：子代理实际可用工具 = 声明白名单 ∩ 父当前可见工具集，
  并且恒定排除 `subagent__*` 与 `tool_search`（子代理不能再派子代理）。
- **独立会话**：子代理跑在自己的会话里，父会话只拿到一段回执。
- **固定回执**：`{status, summary, findings, error}`；
  子代理失败（出错、超时、坏回执）也只是一次**工具失败**，
  不会把父这一轮带崩。
- **目录就是授权边界**：框架只读你声明的子代理目录。

## 目录形态

```
agents/
  reviewer/
    AGENT.md      # frontmatter: name / description / model / tools
  researcher/
    AGENT.md
```

`AGENT.md` 的 frontmatter 字段：`name`（缺省回退目录名）、`description`
（缺省则该定义不安装）、`model`（可选覆盖）、`tools`（可选白名单）。

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

./mvnw -Pexamples -pl examples/subagent -am test

java -cp ... net.hegq.mos4j.examples.subagent.SubAgentExample "帮我审一下改动"
```

## 看什么

- 系统提示词里有子代理清单，没有子代理正文（正文调用时现读）。
- 模型看得见 `subagent__reviewer` / `subagent__researcher`。
- 子代理回一句非 JSON 时：整段降级成 `summary`，回执状态仍是 `completed`；
  非法回执转成 `status=error` 的普通工具结果。

## 边界

- 子代理**不能并发**（不声明 `concurrencySafe`）。
- `model` 覆盖必须存在于宿主的模型目录里，否则**装配期硬失败**
  （声明了却没装 = 假能力，不静默降级到父模型）。
- `findings` 是子代理**自述的线索**，父模型必须自己复核。
