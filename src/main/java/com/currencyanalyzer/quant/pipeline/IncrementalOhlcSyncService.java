package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.marketdata.OhlcBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-market-close incremental OHLC loader for {@code ohlc_history}:
 * performs a 10-year backfill when a pair has no stored bars, then appends only
 * completed daily bars strictly after {@code MAX(bar_date)} following the 5:00 PM NY close.
 */
@Service
public class IncrementalOhlcSyncService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalOhlcSyncService.class);
    static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    static final LocalTime NY_CLOSE_TIME = LocalTime.of(17, 0);
    private static final Duration EMPTY_RETRY_COOLDOWN = Duration.ofMinutes(5);

    private final AlignedMarketDataRepository repository;
    private final MarketDataService marketDataService;
    private final Clock clock;
    private final int initialBackfillDays;
    private final boolean scheduledSyncEnabled;
    private final ConcurrentHashMap<String, Instant> lastEmptyAttemptAt = new ConcurrentHashMap<>();

    @Autowired
    public IncrementalOhlcSyncService(
        AlignedMarketDataRepository repository,
        MarketDataService marketDataService,
        Clock clock,
        @Value("${app.market.ohlc-backfill-days:3650}") int initialBackfillDays,
        @Value("${app.market.incremental-sync-enabled:false}") boolean scheduledSyncEnabled
    ) {
        this.repository = repository;
        this.marketDataService = marketDataService;
        this.clock = clock;
        this.initialBackfillDays = Math.max(30, Math.min(3650, initialBackfillDays));
        this.scheduledSyncEnabled = scheduledSyncEnabled;
    }

    /**
     * Returns the most recent completed NY 5:00 PM weekday trading date.
     * Prevents incomplete intra-day bars from being stored as closed daily history.
     */
    public static LocalDate lastCompletedNyTradingDate(Clock clock) {
        ZonedDateTime nyNow = clock.instant().atZone(NY_ZONE);
        LocalDate candidate = nyNow.toLocalTime().isBefore(NY_CLOSE_TIME)
            ? nyNow.toLocalDate().minusDays(1)
            : nyNow.toLocalDate();
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    public OhlcSyncReport syncPair(String rawPair) {
        if (rawPair == null || rawPair.isBlank()) {
            throw new IllegalArgumentException("pair is required");
        }
        String pair = rawPair.strip().toUpperCase(Locale.ROOT);
        LocalDate targetClosedDate = lastCompletedNyTradingDate(clock);
        Optional<LocalDate> latestStored = repository.findLatestOhlcDate(pair);

        if (latestStored.isPresent() && !latestStored.get().isBefore(targetClosedDate)) {
            return new OhlcSyncReport(pair, latestStored.get(), targetClosedDate, 0, "up_to_date");
        }

        Instant now = clock.instant();
        Instant lastEmpty = lastEmptyAttemptAt.get(pair);
        if (lastEmpty != null && Duration.between(lastEmpty, now).compareTo(EMPTY_RETRY_COOLDOWN) < 0) {
            return new OhlcSyncReport(pair, latestStored.orElse(null), targetClosedDate, 0, "cooldown_skipped");
        }

        int fetchDays = latestStored.isEmpty()
            ? initialBackfillDays
            : (int) Math.max(5L, Math.min(3650L, ChronoUnit.DAYS.between(latestStored.get(), targetClosedDate) + 5L));

        List<OhlcBar> fetched = marketDataService.fetchHistoricalOhlc(pair, fetchDays);
        List<OhlcBar> deltaBars = new ArrayList<>();
        for (OhlcBar bar : fetched) {
            LocalDate barDate = LocalDate.parse(bar.date());
            if (barDate.isAfter(targetClosedDate)) {
                continue;
            }
            if (latestStored.isEmpty() || barDate.isAfter(latestStored.get())) {
                deltaBars.add(bar);
            }
        }

        if (deltaBars.isEmpty()) {
            lastEmptyAttemptAt.put(pair, now);
            return new OhlcSyncReport(pair, latestStored.orElse(null), targetClosedDate, 0, "no_new_bars");
        }

        int written = repository.upsertOhlcBars(pair, "INCREMENTAL_SYNC", deltaBars, now);
        lastEmptyAttemptAt.remove(pair);
        LocalDate updatedLatest = repository.findLatestOhlcDate(pair).orElse(targetClosedDate);
        String status = latestStored.isEmpty() ? "initial_backfill" : "incremental_delta";
        return new OhlcSyncReport(pair, updatedLatest, targetClosedDate, written, status);
    }

    public List<OhlcSyncReport> syncConfiguredPairs() {
        repository.seedClasspathPolicyRatesIfEmpty(clock);
        List<OhlcSyncReport> reports = new ArrayList<>();
        for (String pair : marketDataService.configuredPairs()) {
            try {
                reports.add(syncPair(pair));
            } catch (RuntimeException exception) {
                log.warn("Incremental OHLC sync failed for {}: {}", pair, exception.getMessage());
                reports.add(new OhlcSyncReport(pair, null, lastCompletedNyTradingDate(clock), 0, "error"));
            }
        }
        return List.copyOf(reports);
    }

    @Scheduled(cron = "${app.market.incremental-sync-cron:0 15 17 * * MON-FRI}", zone = "America/New_York")
    public void runScheduledPostCloseSync() {
        if (!scheduledSyncEnabled) {
            return;
        }
        List<OhlcSyncReport> reports = syncConfiguredPairs();
        int totalWritten = reports.stream().mapToInt(OhlcSyncReport::barsWritten).sum();
        log.info("Completed scheduled post-NY-close OHLC sync across {} pairs ({} bars written)",
            reports.size(), totalWritten);
    }

    /**
     * Lazy read-through entry point ensuring stored history is caught up to the requested
     * window (capped at the last completed NY 5:00 PM bar) before emitting Stage 1 aligned bars.
     */
    public Map<String, List<FxDailyBar>> ensureSyncedAndEmitAlignedBars(
        List<String> pairs,
        LocalDate startDate,
        LocalDate endDate
    ) {
        repository.seedClasspathPolicyRatesIfEmpty(clock);
        LocalDate effectiveEnd = endDate != null ? endDate : lastCompletedNyTradingDate(clock);
        LocalDate targetClosed = lastCompletedNyTradingDate(clock);

        if (pairs != null) {
            for (String rawPair : pairs) {
                if (rawPair == null || rawPair.isBlank()) {
                    continue;
                }
                String pair = rawPair.strip().toUpperCase(Locale.ROOT);
                Optional<LocalDate> latest = repository.findLatestOhlcDate(pair);
                if (latest.isEmpty() || (latest.get().isBefore(effectiveEnd) && latest.get().isBefore(targetClosed))) {
                    try {
                        syncPair(pair);
                    } catch (RuntimeException exception) {
                        log.warn("Read-through OHLC sync failed for {}: {}", pair, exception.getMessage());
                    }
                }
            }
        }
        return repository.emitAlignedBars(pairs, startDate, effectiveEnd);
    }

    public record OhlcSyncReport(
        String pair,
        LocalDate latestStoredDate,
        LocalDate targetClosedDate,
        int barsWritten,
        String status
    ) {
    }
}
