---
title: 展望理论（Prospect Theory）
tags:
  - behavioral-finance
  - kahneman
  - tversky
  - framing
aliases:
  - 前景理论
  - Prospect Theory
---

# 展望理论（Prospect Theory）

> Kahneman & Tversky (1979), *Econometrica*

行为金融学最核心的理论基础，用于描述人们在风险下的实际决策方式，替代传统经济学的期望效用理论（Expected Utility Theory）。

## 三个核心特征

### 1. S 型价值函数

```
收益域（Gains）:   concave  → 风险规避
损失域（Losses）:  convex   → 风险寻求
```

- 参考点（Reference Point）依赖——不是看最终财富，而是看相对于参考点的变化
- **损失厌恶**（Loss Aversion）：损失带来的心理痛苦 ≈ 等量收益快乐的 **2–2.5 倍**
- 边际敏感递减：10→20 的感知差异 > 210→220

### 2. 非线性概率权重函数

- 小概率被**高估** → 买彩票、买保险
- 中大概率被**低估**
- 确定性效应（Certainty Effect）：从 95%→100% 的权重增加 > 从 45%→50%

### 3. 框架效应

同一问题在不同表述下得出不同结论：
- "存活率 90%" vs "死亡率 10%"
- 处置效应：同一只股票，浮盈 vs 浮亏采用不同风险态度

## 与金融市场的关联

| 现象 | 展望理论解释 |
|------|------------|
| [[处置效应]] | 浮亏区 risk-seeking → 持有亏损股；浮盈区 risk-averse → 卖出盈利股 |
| 股权溢价之谜 | 损失厌恶 + 短视 → 要求过高风险溢价 |
| [[双曲贴现]] | 近端时间折扣率 > 远端 → 今天 vs 明天的冲突 |

## 与 Kelly 框架的关系

Kelly 仓位优化基于期望对数财富最大化（长期几何增长）。Prospect Theory 揭示了人类**无法**按此理性执行的原因：

- 损失厌恶 → 浮亏时不敢按 Kelly 止损
- 参考点依赖 → 对买入价锚定 → 拒绝斩仓
- 概率权重扭曲 → 高估小概率暴跌风险 → 仓位不足

**解法**：用制度化规则（f\* 上限 20%、单笔止损 8% NAV）绕过 Prospect Theory 的自动决策系统。

---

## 参考文献

- Kahneman, D., & Tversky, A. (1979). Prospect Theory: An Analysis of Decision under Risk. *Econometrica*, 47(2), 263-291.
- Kahneman, D. (2011). *Thinking, Fast and Slow*.
- [[Beyond Gree[[beyond-greed-and-fear]]d and Fear]] Ch3