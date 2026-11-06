<!-- MoS4J PR 模板：防线 3（评审规范）。全不适用时逐项写 N/A，不留空。 -->

## 触及的契约（必勾）

- [ ] 不变式（列出编号，见 `docs/contract/INVARIANTS.md`）：
- [ ] 机制（列出编号，见 `docs/contract/MECHANISMS.md`）：
- [ ] 场景测试（列出用例，见 `docs/contract/L1/L2/L3-SCENARIOS.md`）——**没碰场景要说明为什么**：
- [ ] 事件字段 / 公共 API（是 → 附 `fieldnames.json` diff + 迁移说明，**两人评审**）：

## 自创机制（I5 侧栏）

- [ ] 无自创机制
- [ ] 有（→ 附理由 + 风险 + 对位分析，登记 `docs/contract/SELF-INVENTED.md`，单独列出供重点评审）

## 验证结果（真实输出）

- `mvn -q verify`：
- 场景测试：L1 <n/n> · L2 <n/n> · L3 <n/n>
- 字段名守护：
- 边界检查 / 依赖审计：

## 降级声明（如触及六处 Java 差异）

- （进程组击杀 / 版本令牌 / 取消 / Schema 反射 / 协议 DTO / RE2J —— 是否新增或修改降级面？）
