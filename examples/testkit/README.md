# testkit —— 示例共用的测试替身

脚本化模型 `StubModel`：让示例的用例**不碰网络**也能真跑一遍装配、工具链与循环。

示例的真正内容是「怎么装配」，不是「模型会答什么」。用替身跑一遍，
示例里的装配代码才不会退化成「看起来对、其实没跑过」。

```java
StubModel model = StubModel.of(
        StubModel.toolCall("c1", "read", "{\"path\":\"a.txt\"}"),
        StubModel.text("读完了"));
```

- `StubModel.text(String)`：一段纯文本回答。
- `StubModel.toolCall(id, name, args)`：一次工具调用（args 是原始 JSON 字符串）。
- `StubModel.of(Object...)`：按轮次脚本化；某一步给 `RuntimeException` 就是那一步直接失败。
- `model.requests()` / `systemPromptOf(i)` / `toolNamesOf(i)`：断言「模型看见了什么」。

替身只在**测试作用域**使用：示例模块自己声明 `test` scope 依赖，
不进任何运行时依赖。
