package com.currencyanalyzer.trade;

import com.currencyanalyzer.trade.TradeJournalRepository.DailyRiskAggregate;
import com.currencyanalyzer.trade.TradeJournalRepository.NewTradeRow;
import com.currencyanalyzer.trade.TradeJournalRepository.TradeRecord;
import com.currencyanalyzer.trade.TradeJournalRepository.UpdatedTradeRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TradeJournalService {

    private static final double DEFAULT_DAILY_RISK_LIMIT_PCT = 3.0;
    private static final double DEFAULT_FALLBACK_EQUITY = 10_000.0;
    private static final int MAX_PAIR_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final String SIDE_LONG = "long";
    private static final String SIDE_SHORT = "short";
    private static final String STATUS_CLOSED = "closed";

    private final TradeJournalRepository repository;
    private final Clock clock;

    public TradeJournalService(TradeJournalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public double resolveDailyRiskLimitPct(String username) {
        return repository.findUserDailyRiskLimitPct(username)
            .or(repository::findGlobalDefaultDailyRiskLimitPct)
            .orElse(DEFAULT_DAILY_RISK_LIMIT_PCT);
    }

    @Transactional
    public double saveUserDailyRiskLimitPct(String username, Double requestedLimitPct) {
        if (requestedLimitPct == null || !Double.isFinite(requestedLimitPct)
            || requestedLimitPct <= 0.0 || requestedLimitPct > 100.0) {
            throw new IllegalArgumentException("daily_risk_limit_pct must be between 0 and 100");
        }
        double rounded = round(requestedLimitPct, 4);
        repository.upsertUserDailyRiskLimitPct(username, rounded, clock.instant());
        return rounded;
    }

    public RiskSummary computeSummary(String username, Double rawEquity, Double rawPlannedRiskUsd) {
        double equity = rawEquity != null && Double.isFinite(rawEquity) && rawEquity >= 0.0
            ? rawEquity
            : DEFAULT_FALLBACK_EQUITY;
        double plannedRisk = rawPlannedRiskUsd != null && Double.isFinite(rawPlannedRiskUsd)
            ? rawPlannedRiskUsd
            : 0.0;

        double dailyLimitPct = resolveDailyRiskLimitPct(username);
        double dailyLimitUsd = round(equity * (dailyLimitPct / 100.0), 2);

        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        DailyRiskAggregate todayAgg = repository.aggregateOpenedOnUtcDate(username, todayUtc);
        DailyRiskAggregate openAgg = repository.aggregateOpenRisk(username);

        double openedTodayUsd = round(todayAgg.totalRiskUsd(), 2);
        double activeRiskUsd = round(openAgg.totalRiskUsd(), 2);
        double remainingUsd = round(dailyLimitUsd - openedTodayUsd, 2);
        double projectedRemainingUsd = round(remainingUsd - plannedRisk, 2);
        boolean canOpen = equity > 0.0 && projectedRemainingUsd >= -1e-6;

        return new RiskSummary(
            dailyLimitPct,
            dailyLimitUsd,
            openedTodayUsd,
            todayAgg.tradeCount(),
            activeRiskUsd,
            openAgg.tradeCount(),
            remainingUsd,
            projectedRemainingUsd,
            canOpen
        );
    }

    public List<Map<String, Object>> listDiaryEntries(String username, int limit) {
        return repository.findByUsername(username, limit).stream()
            .map(TradeJournalService::toEntryMap)
            .toList();
    }

    @Transactional
    public TradeMutationResult createEntry(String username, TradeEntryRequest request) {
        ValidatedTradeFields fields = validateTradeFields(request);
        RiskSummary summary = computeSummary(username, fields.equity(), fields.riskAmountUsd());
        if (!summary.canOpenNewTrade()) {
            return TradeMutationResult.rejected(
                "Projected risk ($" + String.format(Locale.US, "%.2f", fields.riskAmountUsd())
                    + ") exceeds your remaining daily risk budget ($"
                    + String.format(Locale.US, "%.2f", summary.remainingRiskUsd()) + ").",
                summary
            );
        }

        TradeRecord created = repository.insert(new NewTradeRow(
            username,
            fields.pair(),
            fields.side(),
            fields.entryPrice(),
            fields.stopPrice(),
            fields.quantity(),
            fields.riskAmountUsd(),
            fields.riskPctOfEquity(),
            fields.atr(),
            fields.sigma(),
            fields.entryReason(),
            fields.notes(),
            clock.instant()
        ));

        RiskSummary updatedSummary = computeSummary(username, fields.equity(), 0.0);
        return TradeMutationResult.accepted(toEntryMap(created), updatedSummary);
    }

    @Transactional
    public Optional<TradeMutationResult> updateEntry(String username, long id, TradeEntryRequest request) {
        Optional<TradeRecord> existingOpt = repository.findByIdAndUsername(id, username);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        TradeRecord existing = existingOpt.get();
        ValidatedTradeFields fields = validateTradeFields(request);

        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        boolean openedToday = existing.openedAt() != null
            && LocalDate.ofInstant(existing.openedAt(), ZoneOffset.UTC).equals(todayUtc);
        double priorRisk = existing.riskAmountUsd() != null ? existing.riskAmountUsd() : 0.0;
        double deltaRisk = openedToday ? (fields.riskAmountUsd() - priorRisk) : 0.0;

        if (deltaRisk > 1e-6) {
            RiskSummary check = computeSummary(username, fields.equity(), deltaRisk);
            if (!check.canOpenNewTrade()) {
                return Optional.of(TradeMutationResult.rejected(
                    "Updated trade risk exceeds your remaining daily risk budget.",
                    check
                ));
            }
        }

        Double closePrice = STATUS_CLOSED.equalsIgnoreCase(existing.status())
            ? validateOptionalPositive(request.closePrice(), "close_price", existing.closePrice())
            : existing.closePrice();
        String closeReason = STATUS_CLOSED.equalsIgnoreCase(existing.status())
            ? normalizeText(request.closeReason(), "close_reason", false, existing.closeReason())
            : existing.closeReason();

        TradeRecord updated = repository.updateEntry(id, username, new UpdatedTradeRow(
            fields.pair(),
            fields.side(),
            fields.entryPrice(),
            fields.stopPrice(),
            fields.quantity(),
            fields.riskAmountUsd(),
            fields.riskPctOfEquity(),
            fields.atr() != null ? fields.atr() : existing.atr(),
            fields.sigma() != null ? fields.sigma() : existing.sigma(),
            fields.entryReason(),
            fields.notes(),
            closePrice,
            closeReason,
            clock.instant()
        )).orElseThrow(() -> new IllegalStateException("Trade entry disappeared during update"));

        RiskSummary updatedSummary = computeSummary(username, fields.equity(), 0.0);
        return Optional.of(TradeMutationResult.accepted(toEntryMap(updated), updatedSummary));
    }

    @Transactional
    public Optional<TradeMutationResult> closeEntry(String username, long id, CloseTradeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Close trade payload is required");
        }
        Optional<TradeRecord> existingOpt = repository.findByIdAndUsername(id, username);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        String closeReason = normalizeText(request.closeReason(), "close_reason", true, null);
        Double closePrice = validateOptionalPositive(request.closePrice(), "close_price", null);

        TradeRecord closed = repository.closeTrade(id, username, closePrice, closeReason, clock.instant())
            .orElseThrow(() -> new IllegalStateException("Trade entry disappeared during close"));

        double equity = request.equity() != null && Double.isFinite(request.equity()) && request.equity() > 0
            ? request.equity()
            : DEFAULT_FALLBACK_EQUITY;
        RiskSummary summary = computeSummary(username, equity, 0.0);
        return Optional.of(TradeMutationResult.accepted(toEntryMap(closed), summary));
    }

    static Map<String, Object> toEntryMap(TradeRecord record) {
        Double realizedPnl = computeRealizedPnl(record);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.id());
        map.put("pair", record.pair());
        map.put("side", record.side());
        map.put("status", record.status());
        map.put("entry_price", record.entryPrice());
        map.put("stop_price", record.stopPrice());
        map.put("close_price", record.closePrice());
        map.put("quantity", record.quantity());
        map.put("risk_amount_usd", record.riskAmountUsd());
        map.put("risk_pct_of_equity", record.riskPctOfEquity());
        map.put("atr", record.atr());
        map.put("sigma", record.sigma());
        map.put("entry_reason", record.entryReason());
        map.put("close_reason", record.closeReason());
        map.put("notes", record.notes());
        map.put("realized_pnl", realizedPnl);
        map.put("opened_at", formatInstant(record.openedAt()));
        map.put("closed_at", formatInstant(record.closedAt()));
        return map;
    }

    private static Double computeRealizedPnl(TradeRecord record) {
        if (!STATUS_CLOSED.equalsIgnoreCase(record.status())
            || record.closePrice() == null
            || record.entryPrice() == null
            || record.quantity() == null) {
            return null;
        }
        double diff = SIDE_SHORT.equalsIgnoreCase(record.side())
            ? (record.entryPrice() - record.closePrice())
            : (record.closePrice() - record.entryPrice());
        return round(diff * record.quantity(), 2);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static ValidatedTradeFields validateTradeFields(TradeEntryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Trade payload is required");
        }
        String rawPair = request.pair() == null ? "" : request.pair().trim().toUpperCase(Locale.ROOT);
        if (rawPair.isEmpty() || rawPair.length() > MAX_PAIR_LENGTH || !rawPair.contains("/")) {
            throw new IllegalArgumentException("Valid asset pair (e.g. EUR/USD) is required");
        }

        String side = request.side() == null ? "" : request.side().trim().toLowerCase(Locale.ROOT);
        if (!SIDE_LONG.equals(side) && !SIDE_SHORT.equals(side)) {
            throw new IllegalArgumentException("side must be 'long' or 'short'");
        }

        double entryPrice = requirePositive(request.entryPrice(), "entry_price");
        double stopPrice = requirePositive(request.stopPrice(), "stop_price");
        double quantity = requirePositive(request.quantity(), "quantity");

        if (SIDE_LONG.equals(side) && stopPrice >= entryPrice) {
            throw new IllegalArgumentException("For a long trade, stop_price must be below entry_price");
        }
        if (SIDE_SHORT.equals(side) && stopPrice <= entryPrice) {
            throw new IllegalArgumentException("For a short trade, stop_price must be above entry_price");
        }

        double equity = resolvePositiveEquity(request.equity());

        String entryReason = normalizeText(request.entryReason(), "entry_reason", true, null);
        String notes = normalizeText(request.notes(), "notes", false, "");

        double riskAmountUsd = round(Math.abs(entryPrice - stopPrice) * quantity, 2);
        double riskPctOfEquity = round((riskAmountUsd / equity) * 100.0, 4);

        Double atr = validateOptionalPositive(request.atr(), "atr", null);
        Double sigma = validateOptionalPositive(request.sigma(), "sigma", null);

        return new ValidatedTradeFields(
            rawPair, side, entryPrice, stopPrice, quantity, equity,
            riskAmountUsd, riskPctOfEquity, atr, sigma, entryReason, notes
        );
    }

    private static double resolvePositiveEquity(Double rawEquity) {
        return rawEquity != null && Double.isFinite(rawEquity) && rawEquity > 0
            ? rawEquity
            : DEFAULT_FALLBACK_EQUITY;
    }

    private static double requirePositive(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(field + " must be a positive number");
        }
        return value;
    }

    private static Double validateOptionalPositive(Double value, String field, Double fallback) {
        if (value == null) {
            return fallback;
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(field + " must be a positive number");
        }
        return value;
    }

    private static String normalizeText(String raw, String field, boolean required, String fallback) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds maximum length of " + MAX_TEXT_LENGTH);
        }
        return trimmed;
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }

    private record ValidatedTradeFields(
        String pair,
        String side,
        double entryPrice,
        double stopPrice,
        double quantity,
        double equity,
        double riskAmountUsd,
        double riskPctOfEquity,
        Double atr,
        Double sigma,
        String entryReason,
        String notes
    ) {
    }

    public record RiskSummary(
        double dailyLimitPct,
        double dailyLimitUsd,
        double openedTodayRiskUsd,
        int todayTradeCount,
        double activeRiskUsd,
        int openTradeCount,
        double remainingRiskUsd,
        double projectedRemainingRiskUsd,
        boolean canOpenNewTrade
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("daily_limit_pct", dailyLimitPct);
            map.put("daily_limit_usd", dailyLimitUsd);
            map.put("opened_today_risk_usd", openedTodayRiskUsd);
            map.put("today_trade_count", todayTradeCount);
            map.put("active_risk_usd", activeRiskUsd);
            map.put("open_trade_count", openTradeCount);
            map.put("remaining_risk_usd", remainingRiskUsd);
            map.put("projected_remaining_risk_usd", projectedRemainingRiskUsd);
            map.put("can_open_new_trade", canOpenNewTrade);
            return map;
        }
    }

    public record TradeMutationResult(
        boolean accepted,
        String errorMessage,
        Map<String, Object> entry,
        RiskSummary summary
    ) {
        static TradeMutationResult accepted(Map<String, Object> entry, RiskSummary summary) {
            return new TradeMutationResult(true, null, entry, summary);
        }

        static TradeMutationResult rejected(String errorMessage, RiskSummary summary) {
            return new TradeMutationResult(false, errorMessage, null, summary);
        }
    }

    public record TradeEntryRequest(
        String pair,
        String side,
        Double entryPrice,
        Double stopPrice,
        Double quantity,
        Double equity,
        String entryReason,
        String notes,
        Double closePrice,
        String closeReason,
        Double atr,
        Double sigma
    ) {
    }

    public record CloseTradeRequest(
        String closeReason,
        Double closePrice,
        Double equity
    ) {
    }
}
