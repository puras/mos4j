# agents —— 多智能体

拆解 → 执行 → 验收。

## 这个示例讲什么

MoS4J 给的是**一个能跑的智能体**；多智能体是宿主用几个智能体拼出来的编排——
框架不替你定「有几个角色、怎么分工」，那是业务的事。

- 每个角色一个**会话**：拆解的中间过程、执行的动作、验收的意见各在各的日志里，
  互不污染；宿主拿到的只是「上一环的结果」这一段文本。
- 每个角色一个**提示词**，工具面可以再各自收窄。

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

./mvnw -Pexamples -pl examples/agents -am test

java -cp ... net.hegq.mos4j.examples.agents.AgentsExample "写一篇项目周报"
```

## 看什么

- `data/planner.jsonl`、`data/worker.jsonl`、`data/reviewer.jsonl`：三个会话各在各的。
- 三个角色各调一次模型，提示词各不相同。

## 边界

- 这个编排是**宿主代码**：框架不会替你记住「谁接谁的活」。
- 角色之间的传递是「上一环的文本」，不是结构化协议；需要结构化就自己在宿主侧约定。
