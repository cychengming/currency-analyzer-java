package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.marketdata.OhlcBar;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IncrementalOhlcSyncServiceTests {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    @Test
    void computesLastCompletedNyTradingDateAcrossWeekdaysCloseCutoffAndWeekends() {
        // Tuesday 16:59 NY -> Monday is last completed NY 5pm close
        Clock beforeClose = Clock.fixed(
            ZonedDateTime.of(2026, 11, 24, 16, 59, 0, 0, NY_ZONE).toInstant(),
            NY_ZONE
        );
        assertThat(IncrementalOhlcSyncService.lastCompletedNyTradingDate(beforeClose))
            .isEqualTo(LocalDate.of(2026, 11, 23));

        // Tuesday 17:00 NY -> Tuesday is completed
        Clock afterClose = Clock.fixed(
            ZonedDateTime.of(2026, 11, 24, 17, 0, 0, 0, NY_ZONE).toInstant(),
            NY_ZONE
        );
        assertThat(IncrementalOhlcSyncService.lastCompletedNyTradingDate(afterClose))
            .isEqualTo(LocalDate.of(2026, 11, 24));

        // Saturday -> Friday
        Clock saturday = Clock.fixed(
            ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, NY_ZONE).toInstant(),
            NY_ZONE
        );
        assertThat(IncrementalOhlcSyncService.lastCompletedNyTradingDate(saturday))
            .isEqualTo(LocalDate.of(2026, 11, 20));

        // Monday 09:00 NY -> Friday (not Sunday)
        Clock mondayMorning = Clock.fixed(
            ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, NY_ZONE).toInstant(),
            NY_ZONE
        );
        assertThat(IncrementalOhlcSyncService.lastCompletedNyTradingDate(mondayMorning))
            .isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void performsInitialBackfillFiltersIncompleteIntradayBarAndIncrementalDeltaSync() {
        SingleConnectionDataSource dataSource = createMigratedH2();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try {
            AlignedMarketDataRepository repo = new AlignedMarketDataRepository(jdbcTemplate);
            MarketDataService marketData = mock(MarketDataService.class);

            // Day 1: Tuesday 2026-11-24 14:00 NY (before 17:00 close -> targetClosedDate = 2026-11-23)
            Instant day1Instant = ZonedDateTime.of(2026, 11, 24, 14, 0, 0, 0, NY_ZONE).toInstant();
            MutableClock clock = new MutableClock(day1Instant);

            when(marketData.fetchHistoricalOhlc("USD/JPY", 3650)).thenReturn(List.of(
                new OhlcBar("2026-11-20", 153.0, 153.8, 152.9, 153.5, 100.0),
                new OhlcBar("2026-11-23", 153.5, 154.2, 153.2, 154.0, 120.0),
                // Incomplete intra-day bar for 2026-11-24 must NOT be stored before 17:00 NY
                new OhlcBar("2026-11-24", 154.0, 154.4, 153.9, 154.1, 10.0)
            ));

            IncrementalOhlcSyncService syncService = new IncrementalOhlcSyncService(
                repo, marketData, clock, 3650, true
            );

            IncrementalOhlcSyncService.OhlcSyncReport first = syncService.syncPair("usd/jpy");
            assertThat(first.status()).isEqualTo("initial_backfill");
            assertThat(first.barsWritten()).isEqualTo(2);
            assertThat(first.latestStoredDate()).isEqualTo(LocalDate.of(2026, 11, 23));
            assertThat(repo.countOhlcBars("USD/JPY")).isEqualTo(2L);

            // Second call immediately after hits watermark -> 0 upstream calls
            IncrementalOhlcSyncService.OhlcSyncReport upToDate = syncService.syncPair("USD/JPY");
            assertThat(upToDate.status()).isEqualTo("up_to_date");
            assertThat(upToDate.barsWritten()).isZero();
            verify(marketData, times(1)).fetchHistoricalOhlc(eq("USD/JPY"), anyInt());

            // Advance clock past 17:15 NY on 2026-11-24 -> incremental delta fetches only >= 2026-11-24
            clock.setInstant(ZonedDateTime.of(2026, 11, 24, 17, 15, 0, 0, NY_ZONE).toInstant());
            when(marketData.fetchHistoricalOhlc("USD/JPY", 6)).thenReturn(List.of(
                new OhlcBar("2026-11-20", 153.0, 153.8, 152.9, 153.5, 100.0),
                new OhlcBar("2026-11-23", 153.5, 154.2, 153.2, 154.0, 120.0),
                new OhlcBar("2026-11-24", 154.0, 154.9, 153.8, 154.6, 200.0)
            ));

            IncrementalOhlcSyncService.OhlcSyncReport delta = syncService.syncPair("USD/JPY");
            assertThat(delta.status()).isEqualTo("incremental_delta");
            assertThat(delta.barsWritten()).isEqualTo(1);
            assertThat(delta.latestStoredDate()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(repo.countOhlcBars("USD/JPY")).isEqualTo(3L);

            // Read-through helper emits aligned bars with policy rates
            Map<String, List<FxDailyBar>> aligned = syncService.ensureSyncedAndEmitAlignedBars(
                List.of("USD/JPY"), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24)
            );
            assertThat(aligned.get("USD/JPY")).hasSize(3);
            assertThat(aligned.get("USD/JPY").getLast().basePolicyRatePct()).isEqualTo(3.75);
            assertThat(aligned.get("USD/JPY").getLast().quotePolicyRatePct()).isEqualTo(0.75);

            // Scheduled runner respects flag and handles per-pair errors gracefully
            when(marketData.configuredPairs()).thenReturn(List.of("USD/JPY", "GOLD/USD"));
            when(marketData.fetchHistoricalOhlc(eq("GOLD/USD"), anyInt())).thenThrow(new IllegalStateException("upstream down"));
            syncService.runScheduledPostCloseSync();

            // Cooldown prevents hammering on repeated empty responses
            when(marketData.fetchHistoricalOhlc(eq("EUR/USD"), anyInt())).thenReturn(List.of());
            assertThat(syncService.syncPair("EUR/USD").status()).isEqualTo("no_new_bars");
            assertThat(syncService.syncPair("EUR/USD").status()).isEqualTo("cooldown_skipped");
            verify(marketData, times(1)).fetchHistoricalOhlc(eq("EUR/USD"), anyInt());

            // Disabled scheduled runner performs no upstream calls
            MarketDataService unused = mock(MarketDataService.class);
            IncrementalOhlcSyncService disabled = new IncrementalOhlcSyncService(repo, unused, clock, 3650, false);
            disabled.runScheduledPostCloseSync();
            verifyNoInteractions(unused);

            assertThatThrownBy(() -> syncService.syncPair("  "))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            dataSource.destroy();
        }
    }

    private static SingleConnectionDataSource createMigratedH2() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:inc_sync_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        return dataSource;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void setInstant(Instant next) {
            this.current = next;
        }

        @Override
        public ZoneId getZone() {
            return NY_ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
