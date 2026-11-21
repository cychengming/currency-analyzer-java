---
title: 有限套利（Limits of Arbitrage）
tags:
  - behavioral-finance
  - market-efficiency
  - shleifer
  - noise-trader
aliases:
  - Limits of Arbitrage
  - 套利限制
---

# 有限套利（Limits of Arbitrage）

> "Markets are not perfectly efficient because arbitrage is costly and risky."
> — Shleifer & Vishny (1997)

## 定义

传统金融学假设：理性套利者会迅速消除错误定价，使价格回归基本面。

行为金融学的反驳：现实中套利是**有限**的，有三个核心约束。

## 三大套利约束

### 1. 基本面风险（Fundamental Risk）

- 错误定价的资产可能进一步恶化
- 空头可能遇到利好基本面变化
- 套利不是瞬时无风险的

### 2. 噪声交易者风险（Noise Trader Risk）

> De Long, Shleifer, Summers & Waldmann (1990)

- 非理性交易者的情绪可能进一步恶化，导致错误定价更大
- 套利者没有能力预测噪声交易者的下一步行为
- 期限约束时（如季度业绩压力），套利者可能被迫在错误定价最严重时平仓

关键洞察：**即使知道某资产被错误定价，也不一定能从中获利。**

### 3. 资金约束（Implementation Costs）

- 交易成本、卖空成本、法律约束
- 分离管理（Separation of Ownership and Control）：基金经理的资金可能被赎回
- 专业套利者不是用自有资金 → 面临代理人问题

## 与效率市场假设（EMH）的关系

| EMH 的三个版本 | 有限套利的挑战 |
|---------------|--------------|
| 弱式有效 | 不挑战（历史价格模式可存在） |
| 半强式有效 | 挑战（价格长期偏离基本面） |
| 强式有效 | 严重挑战（内幕信息不总能修正价格） |

## 行为金融学 vs 传统金融学的分界线

> 传统：**"If there is a mispricing, arbitrageurs will correct it."**
> 行为：**"If there is a mispricing, arbitrageurs may be unable or unwilling to correct it."**

## 与 Kelly 框架的关系

有限套利解释了为什么可以逆势建仓：
- 结构性低估 → 价格可能继续下跌（噪声交易者风险）
- Kelly 仓位上限 20% NAV 本质上就是在承认 **you cannot time the noise**
- [[结构状态分析]] 中的 p 维度（位置）试图在已有下跌后建仓，减小噪声交易者风险敞口

---

## 参考文献

- De Long, J. B., Shleifer, A., Summers, L. H., & Waldmann, R. J. (1990). Noise Trader Risk in Financial Markets. *Journal of Political Economy*, 98(4), 703-738.
- Shleifer, A., & Vishny, R. W. (1997). The Limits of Arbitrage. *Journal of Finance*, 52(1), 35-55.
- [[Beyond Greed and Fear]] Ch4