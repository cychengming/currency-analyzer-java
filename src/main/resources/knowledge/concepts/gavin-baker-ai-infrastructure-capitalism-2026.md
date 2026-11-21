---
tags: [investing, ai-infrastructure, semiconductor, gavin-baker]
source: https://www.youtube.com/watch?v=Mmj_G9RlW-I
raw_pdf: ../raw/sources/youtube_Mmj_G9RlW-I_2026-05-24.pdf
created: 2026-05-24
---

# Gavin Baker on AI Infrastructure & Capitalism (2026)

> Patrick O'Shaughnessy × Gavin Baker（Atreides Management 合伙人），第六次对谈。2026年3-4月 AI 回调背景下的深度讨论。

## Speakers

- **Gavin Baker**: Atreides Management 合伙人，前 Fidelity 基金经理。专注科技/半导体深度研究。
- **Patrick O'Shaughnessy**: 主持。

## Core Framework

### 两种回撤

```
1. 错误回撤 — 公司误判、假设被证伪 → 认赔出局
2. 失效回撤 — 深度了解的公司价格与价值背离 → 可以加仓（积累未来alpha）
```

Gavin 认为 2026年3月属于 **第二种**。NASDAQ 在跌，但 AI 基本面正处于"资本主义史上最非凡时刻"。

### AI 的史无前例

- **Anthropic** 一年内新增 $11B ARR
- 对比过去10年最成功的三家 SaaS（Palantir + Snowflake + Databricks）合计花了10年才到达的规模
- Anthropic 一个月就完成了这三家的总和
- **"空前。不是在我的职业生涯中空前，是整个资本主义史上空前。"**

### DeepSeek 与第二次 AI 买入机会

- 2025年1月 DeepSeek Monday → AI 抛售
- 但实际：亚洲 GPU 价格早已翻倍、GPU 可用性下降
- 推理模型比非推理模型消耗更多算力 → 客观上利好算力需求
- 只需要观察"价格信号"（DRAM 价格、GPU 租金）就能得出结论
- **Gavin: 2026年3-4月给了第三次机会，估值和 DeepSeek 时一样便宜，但 AI 转折点更明确**

## Watts & Wafers（电力 vs 晶圆）

### 电力（Watts）

- 关税战导致美国天然气价格 -20%，而欧洲/亚洲翻倍/三倍 → 美国制造业竞争力大幅提升
- 资本主义会解决电力短缺（27/28年缓解），长线看轨道计算是终局方案
- **轨道计算不是"太空中的五角大楼"，而是机架在太空中通过激光互联**
  - Starlink V3 单卫星 20kW，Blackwell 机架仅 100kW
  - SpaceX 已有世界最大卫星舰队（占轨道卫星98-99%），具备冷却和激光链路经验
  - 推理适合轨道计算，训练仍在地面
  - 对地面数据中心并非利空——"我们会吸干任何能用的能源"

### 晶圆（Wafers）

- **TSMC 是避免泡沫的关键防线** — 若按 Jensen 想要的产能扩张，NVDA 可在 26/27 年卖 $2-3T GPU，但 TSMC 控制着扩张节奏
- 如果 TSMC 维持对 Intel/Samsung 的领先优势并保持晶圆供给约束 → 可避免过度建设
- 跟踪泡沫的核心指标：**TSMC 的产能决策**
- 本轮基建 vs 2000年泡沫的差异：
  - 资金来源是经营现金流（而非债务）
  - 每块 GPU 100% 利用率（2000年光纤 99% 未利用）

## Valuation 矛盾

**跨行业估值极度不一致：**
- 半导体设备公司交易在 40x 下季度年化盈利
- DRAM 公司仅在中单位数 PE
- 上一周期峰值为 5x vs 12x，当前差距更大（3x vs 45x 接近）
- NVDA 在4月初的估值相对市场是过去10-12年最便宜之一
- 但 GE Vernova（核电概念）估值很高 — **暗示市场在 NVDA 上已经隐含了荒谬的份额丢失**

**结构信号：最高成本的企业群涨最多**（典型的商品牛市特征）— 低质量公司从破产边缘到现金滚滚。历史上，质量股在低谷期跑赢，但牛市中低成本/高杠杆企业爆发。

## 框架层面

### Paro Frontier（性价比前沿）

- 9个月前 Google 主导（TPU V8 保守决策导致失去成本领导力）
- 现在 Anthropic、OpenAI 主导，Grok 4.3 也进入前沿
- Gemini 3.1 靠补贴勉强维持——违反 Richard Sutton 的 Bitter Lesson 是最大风险

### 三大核心问题

1. **Bitter Lesson 是否会在 ASI 阶段被暂时违反？**
   - ASI 最先做的是让自己更高效（"它制造自己的 DeepSeek"）
   - 随着 IQ 提升（300→400→500→600），可能阶段性出现"违反"
2. **Frontier tokens 是否继续捕获绝大部分经济价值？**
   - 前沿模型 vs 开源/低成本模型的差距在扩大，而非缩小
   - Gavin 的实证：Gemini 3.1 Pro 曾令人震撼，现在"不可忍受"
   - 重要性：若前沿令牌溢价下降，将释放应用层价值爆炸
3. **连续性学习能否实现？何时？**
   - 目前只有可验证内容的粗陋版本（RL 在中间训练中进行）
   - 若实现 → 快速起飞（fast takeoff）
   - 三大问题：Bitter Lesson 违反、Frontier Token 溢价、连续性学习

### 芯片设计的"铁三角"

- 坦克设计铁三角：**攻击 × 防御 × 机动性**
- 芯片设计类似：**在 TSMC 设计规则约束下做取舍**
- 预填充（Prefill）= 装炮弹（内存容量受限）
- 解码（Decode）= 开火（内存带宽受限）
- 分离这两者 → 给芯片初创公司更丰富的设计空间
- 但要做"不同且难"的事：做更好的 GPU 是死路（NVDA 的飞轮碾压）
- Cerebras（晶圆级计算）是"不同且难"的正面案例

## 主要公司点评

| 公司            | 状态                                                             |
| ------------- | -------------------------------------------------------------- |
| **Google**    | 失去 TPU 优势，但拥有最大算力基数和 YouTube 数据；等待 Google IO 2026 成果           |
| **Meta**      | Zuckerberg 是真 AI-first CEO，Muse 模型令人惊喜（虽未进 Paro Frontier 但很接近） |
| **Amazon**    | Tranium 进展中，零售端机器人 18 个月内产生 P&L 效益；Nova 被低估                    |
| **Microsoft** | Satya 做出了勇敢但痛苦的决定（自留算力做产品而非卖给 OpenAI）→ 市场惩罚了他，但长期正确            |
| **NVDA**      | Jensen 是逻辑玩家，可随时推出自己的模型（commoditize your complement）           |

## 值得注意的论点

- **用途转变是超级利好**：AI 从"吃到饱"转向"按杯卖"（类似手机从包月制转向按量付费的历史），每次按量转型都带来了需求爆发
- **GPU 实际使用寿命可能延长到 10-15 年**（而非质疑者说的 1-2 年）：预填充/解码分离 + 异构化让旧 GPU 继续服役 → 利好 private credit（GPU 融资成本可从 7%+ 降至 5-6%）
- **Agent 最有用场景**：Gavin 自己最常用的 agent 是 "从6小时播客中提炼我感兴趣的要点"
- **安全提示**：建立家庭/公司安全词，防备 AI 驱动的深度伪造诈骗（冒充子女视频通话要钱）
- **AI 应用层目前净毁灭价值**：即使算上 Cursor、Cognition，AI 原生公司在应用层毁灭了万亿美元价值
- **GPUs per Human 是最佳价值创造指标**：表现最好的公司是"每一名员工驱动最多 GPU 利用率"的企业

## 对知识库框架的呼应

- **回撤定性框架** → 与 Kelly 框架中的结构状态分析呼应：区分"错误回撤 vs 失效回撤"类似于区分"结构破坏 vs 结构回调"
- **价格信号优先** → 与 [[kelly-framework-analysis]] 的"数据源优先级"一致：看价格（GPU 租金、DRAM 价格）比看叙事可靠
- **泡沫与经济结构** → Carlota Perez 的泡沫理论（泡沫→基建→崩溃→黄金时代）在 AI 本轮又有体现
- **Bitter Lesson** → 与 [[quantitative-tools]] 中的验证逻辑一致：更多算力 + 数据 > 人类算法创新

## References

- [[index]]
- [[kelly-framework-analysis]]
- [原视频链接](https://www.youtube.com/watch?v=Mmj_G9RlW-I)
