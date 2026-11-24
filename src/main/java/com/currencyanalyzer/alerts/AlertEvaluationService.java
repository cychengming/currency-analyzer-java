package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertPreference;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.pubsub.AlertEventPublisher;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);
    private static final String TYPE_PERCENTAGE_CHANGE = "percentage_change";
    private static final String TYPE_PRICE_THRESHOLD = "price_threshold";
    private static final String TYPE_MA_CROSSOVER = "moving_average_crossover";
    private static final String TRIGGER_OUTSIDE_RANGE = "outside_range";
    private static final String STATUS_SKIPPED = "skipped";
    private static final long MIN_COOLDOWN_SECONDS = 60L;

    private final AlertMonitoringRepository repository;
    private final AlertMonitoringService monitoringService;
    private final MarketDataService marketDataService;
    private final AlertEventPublisher eventPublisher;
    private final Clock clock;
    private final long alertCooldownSeconds;

    public AlertEvaluationService(
        AlertMonitoringRepository repository,
        AlertMonitoringService monitoringService,
        MarketDataService marketDataService,
        AlertEventPublisher eventPublisher,
        Clock clock,
        @Value("${app.pubsub.alert-cooldown-seconds:3600}") long alertCooldownSeconds
    ) {
        this.repository = repository;
        this.monitoringService = monitoringService;
        this.marketDataService = marketDataService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.alertCooldownSeconds = Math.max(MIN_COOLDOWN_SECONDS, alertCooldownSeconds);
    }

    public EvaluationSummary evaluateTick() {
        if (!monitoringService.isMonitoringActive()) {
            return new EvaluationSummary(STATUS_SKIPPED, "monitoring_disabled", 0, 0, List.of());
        }
        Map<String, Object> settings = monitoringService.getSettings();
        if (!Boolean.TRUE.equals(settings.get("enable_alerts"))) {
            return new EvaluationSummary(STATUS_SKIPPED, "alerts_disabled", 0, 0, List.of());
        }

        double defaultThreshold = ((Number) settings.get("trend_threshold")).doubleValue();
        int defaultPeriod = ((Number) settings.get("detection_period")).intValue();
        Map<String, LiveRate> liveRates = marketDataService.fetchLiveRates();

        int evaluated = 0;
        List<AlertEntry> triggered = new ArrayList<>();
        for (AlertPreference preference : monitoringService.getEffectivePreferences()) {
            if (!preference.enabled()) {
                continue;
            }
            LiveRate liveRate = liveRates.get(preference.pair());
            if (liveRate == null || liveRate.rate() <= 0) {
                continue;
            }
            evaluated++;
            evaluatePreference(preference, liveRate, defaultThreshold, defaultPeriod)
                .flatMap(match -> persistAndPublish(preference.pair(), preference.alertType(), match))
                .ifPresent(triggered::add);
        }

        log.info("Monitoring tick evaluated={} triggered={}", evaluated, triggered.size());
        return new EvaluationSummary("ok", null, evaluated, triggered.size(), List.copyOf(triggered));
    }

    private Optional<AlertEntry> persistAndPublish(String pair, String alertType, RuleMatch match) {
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(alertCooldownSeconds);
        if (!repository.claimCooldownWindow(pair, now, cutoff)) {
            log.debug("Skipping alert for {} because cooldown window is still active", pair);
            return Optional.empty();
        }

        AlertEntry saved = repository.insertAlert(
            pair,
            round2(match.percentChange()),
            round4(match.oldRate()),
            round4(match.newRate()),
            now,
            alertType,
            round4(match.triggerValue()),
            round4(match.thresholdValue())
        );

        try {
            eventPublisher.publish(AlertTriggeredEvent.from(saved));
        } catch (RuntimeException exception) {
            log.warn("Alert {} saved for {} but Pub/Sub publish failed: {}", saved.id(), pair, exception.getMessage());
        }
        return Optional.of(saved);
    }

    private Optional<RuleMatch> evaluatePreference(
        AlertPreference preference,
        LiveRate liveRate,
        double defaultThreshold,
        int defaultPeriod
    ) {
        return switch (preference.alertType()) {
            case TYPE_PERCENTAGE_CHANGE -> evaluatePercentageChange(preference, liveRate, defaultThreshold, defaultPeriod);
            case TYPE_PRICE_THRESHOLD -> evaluatePriceThreshold(preference, liveRate);
            case TYPE_MA_CROSSOVER -> evaluateMovingAverageCrossover(preference, liveRate);
            default -> Optional.empty();
        };
    }

    private Optional<RuleMatch> evaluatePercentageChange(
        AlertPreference preference,
        LiveRate liveRate,
        double defaultThreshold,
        int defaultPeriod
    ) {
        double threshold = preference.customThreshold() != null ? preference.customThreshold() : defaultThreshold;
        int period = preference.customPeriod() != null ? preference.customPeriod() : defaultPeriod;
        double current = liveRate.rate();

        double oldRate;
        List<HistoricalPoint> history = List.of();
        if (period <= 1) {
            oldRate = current - liveRate.change();
        } else {
            history = marketDataService.fetchHistorical(preference.pair(), period);
            if (history.size() < 2) {
                return Optional.empty();
            }
            oldRate = history.getFirst().rate();
        }

        if (oldRate <= 0) {
            return Optional.empty();
        }

        double percentChange = (current - oldRate) / oldRate * 100.0;
        if (Math.abs(percentChange) < threshold) {
            return Optional.empty();
        }
        if (preference.enableTrendConsistency() && !isTrendConsistent(history, percentChange >= 0)) {
            return Optional.empty();
        }

        return Optional.of(new RuleMatch(percentChange, oldRate, current, Math.abs(percentChange), threshold));
    }

    private Optional<RuleMatch> evaluatePriceThreshold(AlertPreference preference, LiveRate liveRate) {
        double current = liveRate.rate();
        String triggerType = preference.triggerType() == null ? TRIGGER_OUTSIDE_RANGE : preference.triggerType();
        Double high = preference.priceHigh();
        Double low = preference.priceLow();

        boolean hitHigh = high != null && high > 0 && current >= high;
        boolean hitLow = low != null && low > 0 && current <= low;

        if (("above".equals(triggerType) || TRIGGER_OUTSIDE_RANGE.equals(triggerType)) && hitHigh) {
            double pct = (current - high) / high * 100.0;
            return Optional.of(new RuleMatch(pct, high, current, current, high));
        }
        if (("below".equals(triggerType) || TRIGGER_OUTSIDE_RANGE.equals(triggerType)) && hitLow) {
            double pct = (current - low) / low * 100.0;
            return Optional.of(new RuleMatch(pct, low, current, current, low));
        }
        return Optional.empty();
    }

    private Optional<RuleMatch> evaluateMovingAverageCrossover(AlertPreference preference, LiveRate liveRate) {
        int shortPeriod = Math.max(2, preference.maShortPeriod());
        int longPeriod = Math.max(shortPeriod + 1, preference.maLongPeriod());

        List<HistoricalPoint> history = marketDataService.fetchHistorical(preference.pair(), longPeriod + 5);
        List<Double> closes = new ArrayList<>(history.size() + 1);
        for (HistoricalPoint point : history) {
            if (point.rate() > 0) {
                closes.add(point.rate());
            }
        }
        if (closes.isEmpty() || Math.abs(closes.getLast() - liveRate.rate()) > 1e-9) {
            closes.add(liveRate.rate());
        }
        if (closes.size() < longPeriod + 1) {
            return Optional.empty();
        }

        int n = closes.size();
        double prevShort = trailingAverage(closes, n - 1, shortPeriod);
        double prevLong = trailingAverage(closes, n - 1, longPeriod);
        double currShort = trailingAverage(closes, n, shortPeriod);
        double currLong = trailingAverage(closes, n, longPeriod);

        boolean goldenCross = prevShort <= prevLong && currShort > currLong;
        boolean deathCross = prevShort >= prevLong && currShort < currLong;
        String signalType = preference.signalType() == null ? "both" : preference.signalType();

        boolean matched = ("golden_cross".equals(signalType) && goldenCross)
            || ("death_cross".equals(signalType) && deathCross)
            || ("both".equals(signalType) && (goldenCross || deathCross));
        if (!matched) {
            return Optional.empty();
        }

        double pct = currLong > 0 ? (currShort - currLong) / currLong * 100.0 : 0.0;
        return Optional.of(new RuleMatch(pct, currLong, liveRate.rate(), currShort, currLong));
    }

    private static boolean isTrendConsistent(List<HistoricalPoint> history, boolean upward) {
        if (history.size() < 3) {
            return true;
        }
        int alignedSteps = 0;
        int totalSteps = 0;
        for (int index = 1; index < history.size(); index++) {
            double delta = history.get(index).rate() - history.get(index - 1).rate();
            if (Math.abs(delta) < 1e-12) {
                continue;
            }
            totalSteps++;
            if ((upward && delta > 0) || (!upward && delta < 0)) {
                alignedSteps++;
            }
        }
        return totalSteps == 0 || ((double) alignedSteps / totalSteps) >= 0.55;
    }

    private static double trailingAverage(List<Double> values, int exclusiveEnd, int window) {
        double sum = 0.0;
        for (int index = exclusiveEnd - window; index < exclusiveEnd; index++) {
            sum += values.get(index);
        }
        return sum / window;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record RuleMatch(
        double percentChange,
        double oldRate,
        double newRate,
        double triggerValue,
        double thresholdValue
    ) {
    }

    public record EvaluationSummary(
        String status,
        String reason,
        int evaluated,
        int triggered,
        List<AlertEntry> alerts
    ) {
    }
}
