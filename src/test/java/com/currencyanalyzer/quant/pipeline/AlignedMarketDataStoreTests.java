package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.OhlcBar;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlignedMarketDataStoreTests {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void policyRateResolverLoadsG10AndMetalsWithStrictPointInTimeFloorLookup() {
        PolicyRateResolver resolver = PolicyRateResolver.fromClasspathSeed();

        assertThat(resolver.hasCurrency("USD")).isTrue();
        assertThat(resolver.hasCurrency("JPY")).isTrue();
        assertThat(resolver.hasCurrency("XAU")).isTrue();

        // Point-in-time before BoJ March 2024 hike -> negative rate still applies
        assertThat(resolver.rateAsOf("JPY", LocalDate.of(2024, 3, 18))).isEqualTo(-0.10);
        // On effective date -> new rate applies
        assertThat(resolver.rateAsOf("JPY", LocalDate.of(2024, 3, 19))).isEqualTo(0.10);
        // Between effective dates -> floor rate applies without leaking July 2024 hike
        assertThat(resolver.rateAsOf("JPY", LocalDate.of(2024, 7, 30))).isEqualTo(0.10);
        assertThat(resolver.rateAsOf("JPY", LocalDate.of(2024, 7, 31))).isEqualTo(0.25);

        // Precious metals carry 0% base rate
        assertThat(resolver.rateAsOf("GOLD", LocalDate.of(2024, 8, 1))).isEqualTo(0.0);
        assertThat(resolver.rateAsOf("SILVER", LocalDate.of(2024, 8, 1))).isEqualTo(0.0);
        assertThat(resolver.rateAsOf("USD", LocalDate.of(1990, 1, 1))).isZero();
        assertThat(resolver.rateAsOf("", LocalDate.of(2024, 8, 1))).isZero();
        assertThat(resolver.hasCurrency("")).isFalse();

        PolicyRateResolver custom = PolicyRateResolver.fromEntries(List.of(
            new PolicyRateEntry("EUR", LocalDate.of(2025, 1, 1), 2.5, "")
        ));
        assertThat(custom.rateAsOf("EUR", LocalDate.of(2025, 2, 1))).isEqualTo(2.5);
        assertThat(PolicyRateResolver.fromEntries(null).hasCurrency("USD")).isFalse();
    }

    @Test
    void alignedRepositorySeedsRatesUpsertsOhlcEmitsAlignedBarsAndSavesRuns() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:pipeline_store_" + UUID.randomUUID().toString().replace("-", "")
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
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try {
            AlignedMarketDataRepository repo = new AlignedMarketDataRepository(jdbcTemplate);
            int seeded = repo.seedClasspathPolicyRatesIfEmpty(FIXED_CLOCK);
            assertThat(seeded).isGreaterThan(50);
            assertThat(repo.seedClasspathPolicyRatesIfEmpty(FIXED_CLOCK)).isZero();

            int inserted = repo.upsertOhlcBars(
                "USD/JPY",
                "YAHOO",
                List.of(
                    new OhlcBar("2024-07-30", 154.0, 154.5, 153.5, 154.2, 1000.0),
                    new OhlcBar("2024-07-31", 154.2, 154.3, 150.0, 150.5, 1200.0)
                ),
                FIXED_CLOCK.instant()
            );
            assertThat(inserted).isEqualTo(2);

            // Upsert updates existing bar idempotently
            int updated = repo.upsertOhlcBars(
                "USD/JPY",
                "YAHOO",
                List.of(new OhlcBar("2024-07-31", 154.2, 154.3, 149.8, 150.1, 1500.0)),
                FIXED_CLOCK.instant()
            );
            assertThat(updated).isEqualTo(1);

            Map<String, List<FxDailyBar>> aligned = repo.emitAlignedBars(
                List.of("USD/JPY", "GOLD/USD"),
                LocalDate.of(2024, 7, 30),
                LocalDate.of(2024, 7, 31)
            );

            List<FxDailyBar> usdjpy = aligned.get("USD/JPY");
            assertThat(usdjpy).hasSize(2);
            assertThat(usdjpy.get(0).basePolicyRatePct()).isEqualTo(5.50);
            assertThat(usdjpy.get(0).quotePolicyRatePct()).isEqualTo(0.10);
            assertThat(usdjpy.get(1).quotePolicyRatePct()).isEqualTo(0.25);
            assertThat(usdjpy.get(1).close()).isEqualTo(150.1);

            UUID runId = UUID.randomUUID();
            repo.saveBacktestRun(
                runId,
                "trader1",
                CarverAqrSignalEngine.ENGINE_ID,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                "{}",
                "{\"netSharpeRatio\":1.12}",
                FIXED_CLOCK.instant()
            );
            List<AlignedMarketDataRepository.PipelineRunRecord> runs = repo.findRunsByUsername("trader1", 10);
            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().id()).isEqualTo(runId);
            assertThat(runs.getFirst().profileId()).isEqualTo(CarverAqrSignalEngine.ENGINE_ID);

            // Empty / invalid guard branches
            assertThat(repo.upsertOhlcBars("", "YAHOO", List.of(), FIXED_CLOCK.instant())).isZero();
            assertThat(repo.upsertOhlcBars("EUR/USD", "", List.of(new OhlcBar("2024-07-31", -1.0, 1.0, 0.5, 1.0, 0.0)), FIXED_CLOCK.instant())).isZero();
            assertThat(repo.upsertPolicyRates(List.of(), FIXED_CLOCK.instant())).isZero();
            assertThat(repo.upsertPolicyRates(List.of(new PolicyRateEntry("USD", LocalDate.of(2026, 6, 1), 3.80, "")), FIXED_CLOCK.instant())).isEqualTo(1);
            assertThat(repo.findOhlcBars("", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))).isEmpty();
            assertThat(repo.emitAlignedBars(List.of(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))).isEmpty();
        } finally {
            dataSource.destroy();
        }
    }
}
