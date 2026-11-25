package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.UserAlertRuleRepository.AlertRule;
import com.currencyanalyzer.alerts.UserAlertRuleRepository.UserAlertEntry;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(UserAlertRuleService.class);
    private static final int MAX_RULES_PER_USER = 100;
    private static final int MAX_FAVORITES_PER_USER = 50;
    private static final int MAX_ALERTS_RETURNED = 200;
    private static final int MAX_PAIR_LENGTH = 64;
    private static final int MAX_NICKNAME_LENGTH = 120;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MIN_COOLDOWN_SECONDS = 300;
    private static final int MAX_COOLDOWN_SECONDS = 604_800;
    private static final int DEFAULT_COOLDOWN_SECONDS = 3600;
    private static final Set<String> VALID_CONDITIONS = Set.of(
        UserAlertRuleRepository.COND_CROSS_ABOVE,
        UserAlertRuleRepository.COND_CROSS_BELOW,
        UserAlertRuleRepository.COND_PCT_MOVE,
        UserAlertRuleRepository.COND_MA_CROSSOVER
    );
    private static final Set<String> VALID_SIGNALS = Set.of("both", "golden_cross", "death_cross");
    private static final Set<String> VALID_FREQUENCIES = Set.of(
        UserAlertRuleRepository.FREQ_ONCE,
        UserAlertRuleRepository.FREQ_RECURRING
    );
    private static final Set<String> VALID_EDITABLE_STATUSES = Set.of(
        UserAlertRuleRepository.STATUS_ARMED,
        UserAlertRuleRepository.STATUS_PAUSED
    );

    private final UserAlertRuleRepository repository;
    private final MarketDataService marketDataService;
    private final Clock clock;

    public UserAlertRuleService(
        UserAlertRuleRepository repository,
        MarketDataService marketDataService,
        Clock clock
    ) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.clock = clock;
    }

    public List<AlertRuleView> listRules(long userId) {
        List<AlertRule> rules = repository.findRulesByUser(userId);
        Map<String, LiveRate> liveRates = safeLiveRates();
        Instant now = clock.instant();
        return rules.stream()
            .map(rule -> toView(rule, liveRates.get(rule.pair()), now))
            .toList();
    }

    public AlertRuleView createRule(long userId, RuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Alert rule payload is required");
        }
        List<AlertRule> existing = repository.findRulesByUser(userId);
        if (existing.size() >= MAX_RULES_PER_USER) {
            throw new IllegalArgumentException("Maximum of " + MAX_RULES_PER_USER + " alert rules per user reached");
        }

        String pair = normalizePair(request.pair());
        String conditionType = normalizeCondition(request.conditionType());
        Double targetValue = validateTargetValue(conditionType, request.targetValue());
        int windowDays = validateWindowDays(request.windowDays());
        int maShort = validateMaShort(request.maShortPeriod());
        int maLong = validateMaLong(request.maLongPeriod(), maShort);
        String signalType = normalizeSignalType(request.signalType());
        String frequency = normalizeFrequency(request.frequency());
        int cooldownSeconds = validateCooldownSeconds(request.cooldownSeconds());
        boolean notifyEmail = request.notifyEmail() == null || request.notifyEmail();
        String nickname = normalizeNickname(request.nickname(), pair, conditionType);

        Map<String, LiveRate> liveRates = safeLiveRates();
        LiveRate live = liveRates.get(pair);
        Double initialRate = live != null && live.rate() > 0 ? live.rate() : null;

        Instant now = clock.instant();
        AlertRule rule = new AlertRule(
            UUID.randomUUID(),
            userId,
            pair,
            nickname,
            conditionType,
            targetValue,
            windowDays,
            maShort,
            maLong,
            signalType,
            frequency,
            cooldownSeconds,
            notifyEmail,
            UserAlertRuleRepository.STATUS_ARMED,
            initialRate,
            null,
            now,
            now
        );
        repository.insertRule(rule);
        return toView(rule, live, now);
    }

    public Optional<AlertRuleView> updateRule(long userId, UUID ruleId, RuleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Alert rule payload is required");
        }
        Optional<AlertRule> existingOpt = repository.findRuleByIdForUser(ruleId, userId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        AlertRule current = existingOpt.get();
        String pair = request.pair() == null ? current.pair() : normalizePair(request.pair());
        LiveRate live = safeLiveRates().get(pair);
        Instant now = clock.instant();
        AlertRule updated = buildUpdatedRule(current, request, pair, live, now);
        if (!repository.updateRuleForUser(updated)) {
            return Optional.empty();
        }
        return Optional.of(toView(updated, live, now));
    }

    private AlertRule buildUpdatedRule(
        AlertRule current,
        RuleRequest request,
        String pair,
        LiveRate live,
        Instant now
    ) {
        String conditionType = request.conditionType() == null
            ? current.conditionType()
            : normalizeCondition(request.conditionType());
        Double targetValue = resolveUpdatedTarget(current, request, conditionType);
        int maShort = request.maShortPeriod() == null ? current.maShortPeriod() : validateMaShort(request.maShortPeriod());
        int maLong = resolveUpdatedMaLong(current, request, maShort);
        String status = request.status() == null ? current.status() : normalizeEditableStatus(request.status());
        boolean resetBaseline = shouldResetBaseline(current, pair, conditionType, targetValue, status);

        return new AlertRule(
            current.id(),
            current.userId(),
            pair,
            request.nickname() == null ? current.nickname() : normalizeNickname(request.nickname(), pair, conditionType),
            conditionType,
            targetValue,
            request.windowDays() == null ? current.windowDays() : validateWindowDays(request.windowDays()),
            maShort,
            maLong,
            request.signalType() == null ? current.signalType() : normalizeSignalType(request.signalType()),
            request.frequency() == null ? current.frequency() : normalizeFrequency(request.frequency()),
            request.cooldownSeconds() == null ? current.cooldownSeconds() : validateCooldownSeconds(request.cooldownSeconds()),
            request.notifyEmail() == null ? current.notifyEmail() : request.notifyEmail(),
            status,
            resetBaseline ? positiveRateOrNull(live) : current.lastEvaluatedRate(),
            resetBaseline ? null : current.lastTriggeredAt(),
            current.createdAt(),
            now
        );
    }

    private static Double resolveUpdatedTarget(AlertRule current, RuleRequest request, String conditionType) {
        Double raw = (request.targetValue() != null || request.conditionType() != null)
            ? request.targetValue()
            : current.targetValue();
        return validateTargetValue(conditionType, raw);
    }

    private static int resolveUpdatedMaLong(AlertRule current, RuleRequest request, int maShort) {
        if (request.maLongPeriod() == null) {
            return Math.max(maShort + 1, current.maLongPeriod());
        }
        return validateMaLong(request.maLongPeriod(), maShort);
    }

    private static boolean shouldResetBaseline(
        AlertRule current,
        String pair,
        String conditionType,
        Double targetValue,
        String status
    ) {
        boolean rearming = UserAlertRuleRepository.STATUS_ARMED.equals(status)
            && !UserAlertRuleRepository.STATUS_ARMED.equals(current.status());
        boolean changed = !pair.equals(current.pair())
            || !conditionType.equals(current.conditionType())
            || !java.util.Objects.equals(targetValue, current.targetValue());
        return rearming || changed;
    }

    private static Double positiveRateOrNull(LiveRate live) {
        return live != null && live.rate() > 0 ? live.rate() : null;
    }

    public boolean deleteRule(long userId, UUID ruleId) {
        return repository.deleteRuleForUser(ruleId, userId);
    }

    public AlertInboxResponse getInbox(long userId) {
        List<UserAlertEntry> alerts = repository.listAlertsByUser(userId, MAX_ALERTS_RETURNED);
        long unreadCount = alerts.stream().filter(entry -> entry.readAt() == null).count();
        String alertEmail = repository.findAlertEmail(userId);
        List<String> favorites = repository.listFavoritePairs(userId);
        return new AlertInboxResponse(alerts, unreadCount, alertEmail, favorites);
    }

    public boolean markRead(long userId, long alertId) {
        return repository.markAlertReadForUser(alertId, userId, clock.instant());
    }

    public int markAllRead(long userId) {
        return repository.markAllAlertsReadForUser(userId, clock.instant());
    }

    public int clearInbox(long userId) {
        return repository.clearAlertsForUser(userId);
    }

    public UserAlertProfile getProfile(long userId) {
        return new UserAlertProfile(
            repository.findAlertEmail(userId),
            repository.listFavoritePairs(userId)
        );
    }

    public UserAlertProfile updateProfile(long userId, ProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Profile payload is required");
        }
        Instant now = clock.instant();
        if (request.alertEmail() != null) {
            String email = normalizeEmail(request.alertEmail());
            repository.upsertAlertEmail(userId, email, now);
        }
        if (request.favorites() != null) {
            List<String> normalizedFavorites = normalizeFavorites(request.favorites());
            repository.replaceFavoritePairs(userId, normalizedFavorites, now);
        }
        return getProfile(userId);
    }

    private AlertRuleView toView(AlertRule rule, LiveRate liveRate, Instant now) {
        Double spot = liveRate != null && liveRate.rate() > 0 ? liveRate.rate() : null;
        Double distancePct = null;
        if (spot != null && rule.targetValue() != null && rule.targetValue() > 0
            && (UserAlertRuleRepository.COND_CROSS_ABOVE.equals(rule.conditionType())
                || UserAlertRuleRepository.COND_CROSS_BELOW.equals(rule.conditionType()))) {
            distancePct = Math.round(((rule.targetValue() - spot) / spot * 100.0) * 100.0) / 100.0;
        }
        long cooldownRemaining = computeCooldownRemaining(rule, now);
        return new AlertRuleView(
            rule.id(),
            rule.pair(),
            rule.nickname(),
            rule.conditionType(),
            rule.targetValue(),
            rule.windowDays(),
            rule.maShortPeriod(),
            rule.maLongPeriod(),
            rule.signalType(),
            rule.frequency(),
            rule.cooldownSeconds(),
            rule.notifyEmail(),
            rule.status(),
            spot,
            distancePct,
            cooldownRemaining,
            rule.lastTriggeredAt() == null ? null : rule.lastTriggeredAt().toString(),
            rule.createdAt() == null ? null : rule.createdAt().toString()
        );
    }

    private static long computeCooldownRemaining(AlertRule rule, Instant now) {
        if (!UserAlertRuleRepository.STATUS_ARMED.equals(rule.status())
            || !UserAlertRuleRepository.FREQ_RECURRING.equals(rule.frequency())
            || rule.lastTriggeredAt() == null) {
            return 0L;
        }
        Instant expiresAt = rule.lastTriggeredAt().plusSeconds(rule.cooldownSeconds());
        if (!expiresAt.isAfter(now)) {
            return 0L;
        }
        return Duration.between(now, expiresAt).getSeconds();
    }

    private Map<String, LiveRate> safeLiveRates() {
        try {
            Map<String, LiveRate> rates = marketDataService.fetchLiveRates();
            return rates == null ? Map.of() : rates;
        } catch (RuntimeException exception) {
            log.debug("Live rates unavailable while servicing alert rule request: {}", exception.getMessage());
            return Map.of();
        }
    }

    private static String normalizePair(String rawPair) {
        if (rawPair == null || rawPair.isBlank()) {
            throw new IllegalArgumentException("pair is required");
        }
        String pair = rawPair.trim().toUpperCase(Locale.ROOT);
        if (pair.length() > MAX_PAIR_LENGTH) {
            throw new IllegalArgumentException("pair exceeds maximum length");
        }
        return pair;
    }

    private static String normalizeCondition(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("condition_type is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID_CONDITIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported condition_type: " + normalized);
        }
        return normalized;
    }

    private static Double validateTargetValue(String conditionType, Double targetValue) {
        if (UserAlertRuleRepository.COND_MA_CROSSOVER.equals(conditionType)) {
            return null;
        }
        if (targetValue == null || !Double.isFinite(targetValue) || targetValue <= 0) {
            throw new IllegalArgumentException("target_value must be a positive number");
        }
        if (UserAlertRuleRepository.COND_PCT_MOVE.equals(conditionType) && targetValue > 1000.0) {
            throw new IllegalArgumentException("target_value percentage must be <= 1000");
        }
        return targetValue;
    }

    private static int validateWindowDays(Integer value) {
        int resolved = value == null ? 30 : value;
        if (resolved < 1 || resolved > 365) {
            throw new IllegalArgumentException("window_days must be between 1 and 365");
        }
        return resolved;
    }

    private static int validateMaShort(Integer value) {
        int resolved = value == null ? 20 : value;
        if (resolved < 2 || resolved > 200) {
            throw new IllegalArgumentException("ma_short_period must be between 2 and 200");
        }
        return resolved;
    }

    private static int validateMaLong(Integer value, int maShort) {
        int resolved = value == null ? Math.max(50, maShort + 1) : value;
        if (resolved <= maShort || resolved > 365) {
            throw new IllegalArgumentException("ma_long_period must be greater than ma_short_period and <= 365");
        }
        return resolved;
    }

    private static String normalizeSignalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "both";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID_SIGNALS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported signal_type: " + normalized);
        }
        return normalized;
    }

    private static String normalizeFrequency(String raw) {
        if (raw == null || raw.isBlank()) {
            return UserAlertRuleRepository.FREQ_ONCE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_FREQUENCIES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported frequency: " + normalized);
        }
        return normalized;
    }

    private static int validateCooldownSeconds(Integer value) {
        int resolved = value == null ? DEFAULT_COOLDOWN_SECONDS : value;
        if (resolved < MIN_COOLDOWN_SECONDS || resolved > MAX_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException("cooldown_seconds must be between 300 and 604800");
        }
        return resolved;
    }

    private static String normalizeEditableStatus(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_EDITABLE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be ARMED or PAUSED");
        }
        return normalized;
    }

    private static String normalizeNickname(String raw, String pair, String conditionType) {
        if (raw == null || raw.isBlank()) {
            return pair + " " + conditionType.replace('_', ' ');
        }
        String cleaned = raw.trim();
        if (cleaned.length() > MAX_NICKNAME_LENGTH) {
            throw new IllegalArgumentException("nickname exceeds maximum length of " + MAX_NICKNAME_LENGTH);
        }
        return cleaned;
    }

    private static String normalizeEmail(String raw) {
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("alert_email exceeds maximum length");
        }
        int at = cleaned.indexOf('@');
        if (at <= 0 || at == cleaned.length() - 1 || cleaned.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("alert_email must be a valid email address");
        }
        return cleaned;
    }

    private static List<String> normalizeFavorites(List<String> rawFavorites) {
        Set<String> unique = new LinkedHashSet<>();
        for (String item : rawFavorites) {
            if (item == null || item.isBlank()) {
                continue;
            }
            unique.add(normalizePair(item));
            if (unique.size() > MAX_FAVORITES_PER_USER) {
                throw new IllegalArgumentException("Maximum of " + MAX_FAVORITES_PER_USER + " favorite pairs allowed");
            }
        }
        return new ArrayList<>(unique);
    }

    public record RuleRequest(
        String pair,
        String nickname,
        @JsonProperty("condition_type") String conditionType,
        @JsonProperty("target_value") Double targetValue,
        @JsonProperty("window_days") Integer windowDays,
        @JsonProperty("ma_short_period") Integer maShortPeriod,
        @JsonProperty("ma_long_period") Integer maLongPeriod,
        @JsonProperty("signal_type") String signalType,
        String frequency,
        @JsonProperty("cooldown_seconds") Integer cooldownSeconds,
        @JsonProperty("notify_email") Boolean notifyEmail,
        String status
    ) {
    }

    public record ProfileRequest(
        @JsonProperty("alert_email") String alertEmail,
        List<String> favorites
    ) {
    }

    public record AlertRuleView(
        UUID id,
        String pair,
        String nickname,
        @JsonProperty("condition_type") String conditionType,
        @JsonProperty("target_value") Double targetValue,
        @JsonProperty("window_days") int windowDays,
        @JsonProperty("ma_short_period") int maShortPeriod,
        @JsonProperty("ma_long_period") int maLongPeriod,
        @JsonProperty("signal_type") String signalType,
        String frequency,
        @JsonProperty("cooldown_seconds") int cooldownSeconds,
        @JsonProperty("notify_email") boolean notifyEmail,
        String status,
        @JsonProperty("spot_rate") Double spotRate,
        @JsonProperty("distance_pct") Double distancePct,
        @JsonProperty("cooldown_remaining_seconds") long cooldownRemainingSeconds,
        @JsonProperty("last_triggered_at") String lastTriggeredAt,
        @JsonProperty("created_at") String createdAt
    ) {
    }

    public record AlertInboxResponse(
        List<UserAlertEntry> alerts,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("alert_email") String alertEmail,
        List<String> favorites
    ) {
    }

    public record UserAlertProfile(
        @JsonProperty("alert_email") String alertEmail,
        List<String> favorites
    ) {
    }
}
