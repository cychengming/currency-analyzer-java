package com.currencyanalyzer.ai.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative gate deciding whether pre-turn RAG reference context should be fetched.
 * Pure operational market-data or alert-rule commands skip pre-retrieval (while still retaining
 * agentic {@code search_notes} access), whereas conceptual/document/follow-up turns proceed.
 */
public final class RetrievalIntentGate {

    private static final Pattern MARKET_PAIR = Pattern.compile("(?i)\\b[a-z]{2,8}/[a-z]{3}\\b");

    private static final List<String> KNOWLEDGE_CUES = List.of(
        "guide", "note", "notes", "doc", "document", "upload", "explain", "how to",
        "formula", "theory", "kelly", "memo", "memos", "principle", "strategy",
        "exposure", "what is a ", "what are ", "scenario", "playbook", "runbook", "sop",
        "what should", "should i", "how do i", "how should", "handle", "fomc", "cpi",
        "nfp", "boj", "ecb", "unwind", "shock", "gap", "losing streak", "risk limit",
        "conflict", "disagree", "override",
        "原则", "公式", "理论", "解释", "备忘录", "笔记", "文档", "指南", "敞口",
        "怎么设置", "如何配置", "什么叫", "什么是", "怎么办", "如何应对", "怎么处理",
        "预案", "剧本", "风控上限", "连亏", "央行", "非农", "跳空"
    );

    private static final List<String> ALERT_OPERATIONAL_CUES = List.of(
        "my alert", "alert inbox", "list alert", "create alert", "pause alert",
        "resume alert", "re-arm", "rearm", "我的预警", "创建预警", "暂停预警",
        "恢复预警", "未读预警", "预警规则"
    );

    private static final List<String> MARKET_OPERATIONAL_CUES = List.of(
        "rate", "price", "spot", "quote", "rsi", "macd", "sma", "ema",
        "bollinger", "technical", "volatility", "drawdown", "position size",
        "stop loss", "lot size", "compare", "correlation", "history", "historical",
        "汇率", "价格", "现价", "报价", "技术指标", "仓位计算", "止损", "相关性"
    );

    private RetrievalIntentGate() {
    }

    public static boolean shouldPreRetrieve(String latestUserMessage, String rewrittenQuery) {
        if (rewrittenQuery == null || rewrittenQuery.isBlank()) {
            return false;
        }
        if (latestUserMessage == null || latestUserMessage.isBlank()) {
            return true;
        }
        if (RetrievalQueryRewriter.isFollowUp(latestUserMessage)) {
            return true;
        }
        String lower = latestUserMessage.toLowerCase(Locale.ROOT).trim();
        if (containsAnyCue(lower, KNOWLEDGE_CUES)) {
            return true;
        }
        if (containsAnyCue(lower, ALERT_OPERATIONAL_CUES)) {
            return false;
        }
        if (MARKET_PAIR.matcher(latestUserMessage).find() && containsAnyCue(lower, MARKET_OPERATIONAL_CUES)) {
            return false;
        }
        return true;
    }

    private static boolean containsAnyCue(String lowerText, List<String> cues) {
        for (String cue : cues) {
            if (lowerText.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
