# minimal —— 最小示例

一次装配，一个问题，一句回答。

## 这个示例讲什么

装配层够用。一个真实可跑的智能体，装配只要十几行；其余都是宿主自己的事
（选工具、策略、审批问人）。

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

# 用例（不碰网络）：脚本化模型替身真跑一遍装配
./mvnw -Pexamples -pl examples/minimal -am test

# 真跑（示例主程序）
./mvnw -Pexamples -pl examples/minimal -am package
java -cp "examples/minimal/target/classes:$(cat cp.txt)" \
     net.hegq.mos4j.examples.minimal.MinimalExample "只回答：1+1=?"
```

`cp.txt` 是运行期 classpath（`mos4j-agent` 及其传递依赖），示例不自带打包脚本，
最省事的是用 `mvn dependency:build-classpath` 生成。

## 看什么

主程序先打印**装配快照**：装了哪些工具、开了哪些扩展、**没装什么**（caveats）。
降级必须看得见——这是 MoS4J 的纪律，不是示例的额外要求。

会话日志落在 `data/minimal.jsonl`：事件日志就是事实，回放看它。

## 边界

- 受管 Mac 上 `sandbox-exec` 不许 apply 时，框架在**装配期**报错（不假装有隔离）。
  这时显式加 `MOS_NO_PATH_SCOPE=1` 关掉路径强制，代价是 shell 不受路径强制保护
  （会写进 caveats）。
- 示例不读 `.env`：模型由门面按协议 + 环境变量装。
