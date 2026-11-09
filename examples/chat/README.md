# chat —— 多轮对话

会话恢复、审批问人、策略。

## 这个示例讲什么

- **会话是事实**：每轮的 user / assistant / tool / turn-end 事件落 JSONL，
  重启后按 id 重开即恢复上下文（不用宿主自己拼历史）。
- **审批不是自动的**：守卫先判，判成 `ask`（要人点头）才会走审批通道；
  守卫直接拒绝的压根不问人。示例里 `write` / `edit` / `shell` 要问人。
- **四档结局分得清**：`approved` / `rejected` / `cancelled` / `unavailable`。
  问不到人（终端没了）是 `unavailable`，**不是**「用户拒绝了」——
  两者在审计日志里必须能分开。
- **策略与可见性同源**：被拒的工具既跑不了，也不进模型的工具清单。

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

./mvnw -Pexamples -pl examples/chat -am test

# 交互式：恢复上次会话
java -cp ... net.hegq.mos4j.examples.chat.ChatExample
# 开新会话 / 指定会话
java -cp ... net.hegq.mos4j.examples.chat.ChatExample --new
java -cp ... net.hegq.mos4j.examples.chat.ChatExample --session s2
```

退出：输入 `q`。审批提示会出现在终端上（`放行吗？(y/N)`）。

## 看什么

- `data/chat.jsonl`：多轮之后，两句用户输入都在日志里；重开会话历史还在。
- 审批被拒时：文件**没有**被创建，但这一轮**不算失败**——模型自己收尾。
- 审批不可用时：`approval/decided` 的 outcome 是 `unavailable`（不是 `rejected`）。

## 边界

- 审批通道不装 = 「不需要人确认」，不是「默认放行」：守卫判成 `ask` 却没有通道时，
  那一调用 fail-closed 被拒。
- 批准只对**本次调用**生效，不缓存。
