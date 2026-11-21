---
title: 处置效应（Disposition Effect）
tags:
  - behavioral-finance
  - shefrin
  - odean
  - prospect-theory
  - loss-aversion
aliases:
  - 卖出赢家留住输家
  - Get-Evenitis
  - Disposition Effect
---

# 处置效应（Disposition Effect）

> "Sell winners too early, ride losers too long."
> — Shefrin & Statman (1985)

## 定义

投资者倾向于：
- 过早卖出**盈利**的股票（锁定收益）
- 过长时间持有**亏损**的股票（拒绝认亏）

## 关键数据

| 指标 | 数值 |
|------|------|
| 卖出赢家的概率 | 14.8% |
| 卖出输家的概率 | 9.8% |
| 赢家/输家卖出比率 | ≈1.5x |
| 税后回报受影响 | 显著低于最优税务管理 |

来源：Odean (1998) 对美国折价经纪商账户的实证研究。

## 心理学解释

基于 [[展望理论]]：

- **浮盈区**（gain domain）：价值函数 concave → 风险规避 → 急于卖
- **浮亏区**（loss domain）：价值函数 convex → 风险寻求 → 持期待反弹
- 核心问题：**参考点锚定在买入价**，而不是基于基本面重新判断

## 三个变体

1. **Get-Evenitis** — "等回本就卖" → 一直等，越亏越多
2. **圣诞效应** — 12 月卖出输家做税务损失收割的比例上升（证明投资者知道应该卖，但缺乏纪律）
3. **公司层面** — Malmendier & Tate 的 CEO 过度坚守低效收购决策

## 与 Kelly 框架的关系

处置效应是 Kelly 框架最大的行为敌人之一：
- 信号要求卖出时 → 浮亏状态下拒绝执行 → 小亏变巨亏
- 信号要求持有或加仓时 → 浮盈状态下提前卖出 → 错失大趋势

**制度化解法**：单笔亏损超 8% NAV 强制止损 + Cron 仓位监控自动化执行。

---

## 参考文献

- Shefrin, H., & Statman, M. (1985). The Disposition to Sell Winners Too Early and Ride Losers Too Long. *Journal of Finance*, 40(3), 777-790.
- Odean, T. (1998). Are Investors Reluctant to Realize Their Losses? *Journal of Finance*, 53(5), 1775-1798.
- [[beyond-greed-and-fear]] Ch9