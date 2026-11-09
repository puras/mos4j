# skills —— 技能

技能目录：清单进系统提示词、正文按需现读。

## 这个示例讲什么

- **装配只有三行关键代码**（技能目录 + 提示词），其余都是宿主的活。
- **清单**（name / description）在装配期渲染进**静态**系统提示词：进门就看得见，
  且不随调用变化（保 prompt cache）。
- **正文不进提示词**：模型调 `skill` 工具时才从磁盘现读——改完 `SKILL.md`
  下次调用即生效，不用重扫、不用重启。
- **宿主手势**：`/deploy 参数` 这种写法由宿主就地展开正文，不经过模型判断。
- **技能目录列表就是授权边界**：框架只读这里声明的目录。

## 目录形态

```
skills/
  deploy/
    SKILL.md      # frontmatter: name / description（+ 可选 allowed-tools）
  rollback/
    SKILL.md
```

## 怎么跑

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-4o-mini

./mvnw -Pexamples -pl examples/skills -am test

java -cp ... net.hegq.mos4j.examples.skills.SkillsExample "帮我把 app 部署到测试环境"
java -cp ... net.hegq.mos4j.examples.skills.SkillsExample "/deploy app 测试环境"
```

## 看什么

- 系统提示词里有 `deploy` / `rollback` 的名字与描述，**没有**技能正文。
- 模型看得见 `skill` 工具（它要自己决定读哪份正文）。
- `/deploy …` 手势直接展开正文（宿主侧展开，不经过模型）。

## 边界

- `allowed-tools` 只做**诊断**：技能声明了当前不可用的工具时记一条
  `skill/allowed-tools-mismatch` 事件，不据此收窄工具面
  （不造第二套安全真相——真正的边界是策略与守卫）。
- 技能清单进系统提示词，**不进会话 JSONL 日志**（日志记的是事实，提示词是装配产物）。
