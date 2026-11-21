---
title: 噪声交易者风险（Noise Trader Risk）
tags:
  - behavioral-finance
  - limits-of-arbitrage
  - sentiment
  - delong
  - shleifer
aliases:
  - Noise Trader Risk
  - 噪声交易者
  - DSSW
---

# 噪声交易者风险（Noise Trader Risk）

> "Many traders cannot distinguish between relevant information and irrelevant information."
> — Fischer Black (1986)

## 概念来源

- **Fischer Black (1986)**：将噪声定义为"与信息无关的交易"，将基于噪声交易的称为"噪声交易者"
- **De Long, Shleifer, Summers & Waldmann (1990)**：提出噪声交易者风险的理论模型（DSSW 模型）
- **Lee, Shleifer & Thaler (1991)**：用于解释封闭式基金折价之谜

## DSSW 模型核心逻辑

```
噪声交易者的情绪（乐观/悲观）→ 推动价格偏离基本面
     ↕（双向反馈）
套利者面临被进一步推离的风险 → 不敢大举套利
     ↕
错误定价得以持续
```

### 关键结论

1. **噪声交易者情绪本身是一种风险因子**——不仅仅是短期噪音
2. 理性套利者面对噪声交易者时，不是无限套利，而是**有限套利**
3. 对资产的定价中包含了噪声交易者风险溢价

## 金融市场中的表现

| 市场 | 噪声交易者风险的表现 |
|------|-------------------|
| 封闭式基金折价 | 折价变化反映个人投资者情绪 |
| IPO 首日狂热 | 噪声交易者推高首日价格 |
| 商品期货 | 橘子汁期货价格波动 > 基本面解释（Roll 1985） |
| 加密货币 | 极端噪声驱动型市场 |
| A 股 | 散户主导 → 噪声交易者风险较高 |

## 套利者困境

> "You can be right about the direction but get your timing wrong, and the noise can get worse before it gets better."

噪声交易者风险意味着：
- 即使你确定某资产被低估 30%，它可能先跌 50% 再反转
- 如果你有变现压力（季度赎回、margin call），你可能死在黎明前

## 与 Kelly 框架的关系

噪声交易者风险解释了 Kelly 框架中仓位硬上限的必要性：

| 问题 | Kelly 的解法 |
|------|------------|
| 低估后可能继续跌 | f\* ≤ 20% NAV 限制最大暴露 |
| 无法预判噪声情绪何时反转 | 趋势形成仓 15% NAV 分步建仓 |
| 需要时间证明判断 | 持有期容忍 + [[结构状态分析]] 持续监控 |

**核心原则**：不要试图对抗噪声，要留出安全边际。

---

## 参考文献

- Black, F. (1986). Noise. *Journal of Finance*, 41(3), 529-543.
- De Long, J. B., Shleifer, A., Summers, L. H., & Waldmann, R. (1990). Noise Trader Risk in Financial Markets. *Journal of Political Economy*, 98(4), 703-738.
- [[Beyond Greed and Fear]] Ch4（有限套利框架）& Ch20（Roll 橘子汁期货案例）
- [[有限套利]]