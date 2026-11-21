---
title: 心理账户（Mental Accounting）
tags:
  - behavioral-finance
  - thaler
  - framing
  - portfolio
aliases:
  - Mental Accounting
  - 心理账户
  - 行为资产组合
  - Behavioral Portfolio Theory
---

# 心理账户（Mental Accounting）

> "People treat money differently depending on its source, intended use, and the mental account it sits in."
> — Richard Thaler

## 定义

人们将资金分割到不同的"心理账户"中，每个账户有单独的风险容忍度、目标和规则，**违反了经济学的可替代性（Fungibility）原则**。

## 核心问题

**经济学假设**：$1 = $1，不管来源或用途

**现实**：
- "奖金"和"工资"花法不同
- "意外之财" vs "辛苦赚来的钱"
- "本金" vs "浮盈"——浮盈部分更敢冒险

## 行为资产组合理论（Behavioral Portfolio Theory）

Shefrin & Statman (2000) 替代 Markowitz 均值方差模型：

```
金字塔上层:  高风险投机 (期权/垃圾债/成长股)
    ↓
金字塔中层:  平衡型基金
    ↓
金字塔底层:  保本 (国债/现金/保险)
```

每一层有自己的目标：
- 底层 → 避免 **贫困**
- 上层 → 博取 **暴富**

**与传统组合理论的区别**：
- 传统：整体优化（correlation 矩阵）
- 行为：分层独立管理，各层之间的相关性被忽略

## 金融市场中的表现

| 现象 | 心理账户解释 |
|------|------------|
| 处置效应 | 每只股票一个独立账户，单独盯盈亏 |
| 红利偏好 | 公司将红利看成独立于资本利得的"收入账户" |
| 自住房 vs 出租房 | 对自住房的市值波动不敏感，对出租房敏感 |
| "赌桌上的钱效应" | 浮盈被归入"庄家的钱"账户 → 更冒险 |

## 与 Kelly 框架的关系

心理账户是 Kelly 框架的**组织敌人**：

- 心理账户鼓励按标的独立看盈亏 → Kelly 需要全局仓位优化
- 心理账户导致在某一标的浮亏时不断加仓摊平（"输家的筹码"）
- **解法**：全局 NAV 视角 + 统一的风控规则（f\* = 20% NAV 上限对所有标的统一执行）

---

## 参考文献

- Shefrin, H., & Statman, M. (2000). Behavioral Portfolio Theory. *Journal of Financial and Quantitative Analysis*, 35(2), 127-151.
- Thaler, R. H. (1999). Mental Accounting Matters. *Journal of Behavioral Decision Making*, 12(3), 183-206.
- [[Beyond Greed and Fear]] Ch10