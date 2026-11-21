---
title: 代表性启发式（Representativeness Heuristic）
tags:
  - behavioral-finance
  - heuristic-bias
  - kahneman
  - tversky
aliases:
  - 代表性启发式
  - Representativeness
  - Representativeness Heuristic
---

# 代表性启发式（Representativeness Heuristic）

> "If it looks like a duck, swims like a duck, and quacks like a duck, then it probably is a duck."
> — 但金融市场上看起来像好公司的，不一定是好股票。

## 定义

人们根据事物与某个**原型**的相似程度（代表性）来判断其概率，而忽略不显眼但更关键的统计信息（如基率）。

## Kahneman & Tversky 的经典实验

### 琳达问题（Conjunction Fallacy）

> "Linda is 31 years old, single, outspoken, and very bright. She majored in philosophy. As a student, she was very concerned with issues of discrimination and social justice, and also participated in anti-nuclear demonstrations."

排序以下选项的概率：
- (A) Linda is a bank teller.
- (B) Linda is a **bank teller and is active in the feminist movement**.

**结果**：85% 的人认为 B 比 A 更可能。

逻辑错误：P(A∩B) ≤ P(A) 永远成立，但代表性的匹配度使人们判断颠倒。

## 金融市场的三个典型谬误

### 1. 优秀公司 ≠ 优秀投资

| 分类 | 大众直觉 | 现实 |
|------|---------|------|
| 好公司 | 产品好、管理层优秀 → 股票好 | 股价可能已充分反映 |
| 差公司 | 问题多 → 股票不好 | 股价已充分反映负面 → 潜在机会 |

**基率忽视**：忽略"所有公司整体回报分布"的基率，用人公司特征替代股票回报判断。

### 2. 过度外推近期走势

- 连续 3 年高增长 → 预期持续高增长 → 成长股溢价
- 连续 3 年低增长 → 预期持续低增长 → 价值股折价
- De Bondt & Thaler (1985)：3–5 年后反转

### 3. 赌徒谬误

- 连续 5 根阳线 → "应该回调了吧"（错误认为小样本代表整体）
- 实际：动量可能延续

**赌徒谬误与动量效应的冲突**：
- 代表性 → 认为近期模式代表整体趋势 → 过度外推 → 驱动动量
- 赌徒谬误 → 认为趋势会逆转 → 驱动反转

两者在不同时间尺度上起作用。

## 与其他偏差的关系

| 偏差 | 关系 |
|------|------|
| [[过度自信]] | 代表性导致对模式识别的过度自信 |
| [[处置效应]] | 用近期价格模式判断未来方向 → 拒绝止损 |
| [[有限套利]] | 代表性驱动的错误定价 → 套利者有风险 |

## 与 Kelly 框架的关系

代表性启发式是 Kelly 分析需要刻意**对抗**的偏差：

- **Kelly 结构状态分析**中的 p/R/b 三变量 → 用数据结构替代模式识别
- **"好公司 ≠ 好股票"** → Kelly 框架不关注公司基本面质量，只关注结构状态
- **历史时间线的展示** → 冷却代表性驱动的"追逐热点"冲动

---

## 参考文献

- Tversky, A., & Kahneman, D. (1974). Judgment under Uncertainty: Heuristics and Biases. *Science*, 185(4157), 1124-1131.
- De Bondt, W. F. M., & Thaler, R. (1985). Does the Stock Market Overreact? *Journal of Finance*, 40(3), 793-805.
- [[Beyond Greed and Fear]] Ch2