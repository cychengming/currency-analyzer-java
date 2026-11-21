---
title: 双曲贴现（Hyperbolic Discounting）
tags:
  - behavioral-finance
  - self-control
  - thaler
  - retirement
aliases:
  - Hyperbolic Discounting
  - 双曲贴现
  - 现时偏向
  - Present Bias
---

# 双曲贴现（Hyperbolic Discounting）

> "People discount the immediate future at a higher rate than the distant future."
> — Thaler (1981)

## 定义

与传统指数贴现不同，双曲贴现的折扣率随时间递减：

- **指数贴现**（理性）：今天 vs 明天 = 1年 vs 1年+1天（折扣率恒定）
- **双曲贴现**（现实）：今天 vs 明天 的折扣率 >> 1年 vs 1年+1天

## 经典实验

| 选择 A | 选择 B | 大多数人选 |
|--------|--------|-----------|
| 今天拿 $10 | 明天拿 $11 | A（等不了 1 天多拿 $1） |
| 1 年后拿 $10 | 1 年+1 天后拿 $11 | B（1 天无所谓） |
| 今天开始戒烟 | 明天开始戒烟 | 明天（永远在明天） |

逻辑上两个问题等价，但选择截然不同 → **时间不一致偏好**（Time-Inconsistent Preferences）

## 金融市场的三个表现

### 1. 退休储蓄不足

- "应该存钱，但下个月开始" → 永远下个月
- 401(k) 加入率：Opt-out 系统 85–95% vs Opt-in 系统 15–40%

### 2. [[Save More Tomorrow]]（SMarT 计划）

Thaler & Benartzi (2004) 的设计：
- 承诺**未来加薪时**自动增加储蓄率
- 利用了双曲贴现的心理机制——"未来的痛苦不是现在的痛苦"
- 实施效果：储蓄率从 3.5% → 13.6% 在 40 个月内

### 3. 信用卡债务

- 今天的消费 → 明天再还
- 高利率信用卡债务积累 = 双曲贴现的经典体现

## 与 Kelly 框架的关系

双曲贴现对投资纪律的影响：

| 场景 | 双曲贴现反应 | Kelly 框架的解法 |
|------|------------|----------------|
| 浮亏中想补仓 | "现在抄底摊平成本"（冲动） | Cron 监控 + 规则决策，不临时决策 |
| 浮盈中想卖出 | "先落袋为安"（短视） | 结构状态 p 定位 + 止盈规则 |
| 定投中断 | "下月再补" | Cron 自动化执行 |

**核心**：双曲贴现导致人倾向于用短期情绪替代长期计划。制度化执行（Cron、规则、自动化）是唯一的解法。

---

## 参考文献

- Thaler, R. H., & Benartzi, S. (2004). Save More Tomorrow: Using Behavioral Economics to Increase Employee Saving. *Journal of Political Economy*, 112(S1), S164-S187.
- Laibson, D. (1997). Golden Eggs and Hyperbolic Discounting. *Quarterly Journal of Economics*, 112(2), 443-478.
- [[Beyond Greed and Fear]] Ch11