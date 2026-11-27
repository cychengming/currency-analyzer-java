package com.currencyanalyzer.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationIntegrityTests {

    @Test
    void productionFlywayLocationsHaveGloballyUniqueOrderedVersions() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:flyway_integrity_info;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");

        MigrationInfo[] migrations = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:db/postgresql-migration")
            .load()
            .info()
            .all();

        assertTrue(migrations.length >= 8, "Expected combined base and PostgreSQL migrations");

        Set<MigrationVersion> seenVersions = new HashSet<>();
        MigrationVersion previous = null;
        for (MigrationInfo info : migrations) {
            MigrationVersion version = info.getVersion();
            assertNotNull(version, () -> "Migration is missing a version: " + info.getScript());
            assertTrue(
                seenVersions.add(version),
                () -> "Duplicate Flyway migration version " + version + " in " + info.getScript()
            );
            if (previous != null) {
                MigrationVersion prev = previous;
                assertTrue(
                    version.compareTo(prev) > 0,
                    () -> "Migrations out of ascending order: " + prev + " followed by " + version
                );
            }
            previous = version;
        }

        List<String> scripts = Arrays.stream(migrations)
            .map(MigrationInfo::getScript)
            .toList();
        assertTrue(scripts.contains("V6__rag_pgvector.sql"));
        assertTrue(scripts.contains("V7__rag_pg_trgm.sql"));
        assertTrue(scripts.contains("V8__user_alert_rules.sql"));
    }

    @Test
    void h2BaseMigrationsApplyCleanlyAcrossPostgreSqlVersionGap() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:flyway_integrity_migrate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer ruleTables = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_name IN ('alert_rules', 'user_alert_settings', 'user_favorite_pairs')
            """,
            Integer.class
        );
        assertEquals(3, ruleTables);

        Integer pipelineTables = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_name IN ('ohlc_history', 'policy_rate_history', 'pipeline_backtest_runs', 'user_trade_settings')
            """,
            Integer.class
        );
        assertEquals(4, pipelineTables);
    }
}
