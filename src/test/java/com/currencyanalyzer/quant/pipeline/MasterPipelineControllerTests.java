package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.MarketDataController;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.marketdata.OhlcBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:master-pipeline-api-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.market.incremental-sync-enabled=false"
    }
)
@AutoConfigureMockMvc
class MasterPipelineControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlignedMarketDataRepository alignedRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private boolean seeded = false;

    @BeforeEach
    void seedDeterministicMarketHistory() {
        if (seeded) {
            return;
        }
        alignedRepository.seedClasspathPolicyRatesIfEmpty(clock);
        LocalDate end = IncrementalOhlcSyncService.lastCompletedNyTradingDate(clock);
        Instant now = clock.instant();
        alignedRepository.upsertOhlcBars("EUR/USD", "SEED", generateBars(end, 340, 1.0500, 0.0004), now);
        alignedRepository.upsertOhlcBars("USD/JPY", "SEED", generateBars(end, 340, 140.00, 0.06), now);
        alignedRepository.upsertOhlcBars("GOLD/USD", "SEED", generateBars(end, 340, 2000.0, 1.50), now);
        seeded = true;
    }

    @Test
    void servesProfilesArenaBacktestLiveTargetsAndViewerPersistenceGuard() throws Exception {
        // Unauthenticated rejected
        mockMvc.perform(get("/api/pipeline/profiles")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/pipeline/targets/live")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/pipeline/backtest").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/historical-ohlc").param("pair", "EUR/USD").param("days", "60"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/historical-ohlc/EUR/USD/60"))
            .andExpect(status().isUnauthorized());

        register("quant_member", "password123");
        register("quant_viewer", "password123");
        jdbcTemplate.update("UPDATE users SET role = 'VIEWER' WHERE username = 'quant_viewer'");

        MockHttpSession member = login("quant_member", "password123");
        MockHttpSession viewer = login("quant_viewer", "password123");

        // 1. Catalog & conditions endpoints
        mockMvc.perform(get("/api/pipeline/profiles").session(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.profiles", hasSize(4)));

        mockMvc.perform(get("/api/backtest/conditions").session(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.long_term_uptrend.name").value("Long-Term Uptrend"));

        // 2. Member runs 5-Stage Master Arena -> persists run
        mockMvc.perform(post("/api/pipeline/backtest").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "profile_id": "carver-aqr-spine",
                      "pairs": ["EUR/USD", "USD/JPY", "GOLD/USD"],
                      "days": 500,
                      "initial_equity_usd": 100000,
                      "target_volatility_pct": 10.0,
                      "max_currency_exposure_multiple": 1.5,
                      "rebalance_inertia_pct": 10.0,
                      "enable_carry": true,
                      "include_arena": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.arena_comparison", hasSize(4)))
            .andExpect(jsonPath("$.equity_curve", hasSize(greaterThanOrEqualTo(10))))
            .andExpect(jsonPath("$.summary.final_equity_usd").isNumber());

        mockMvc.perform(get("/api/pipeline/runs").session(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs", hasSize(1)));

        // 3. Viewer can simulate in read-only mode without persisting a DB run row
        mockMvc.perform(post("/api/pipeline/backtest").session(viewer).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "profile_id": "turtle-donchian",
                      "pairs": ["EUR/USD", "GOLD/USD"],
                      "days": 500
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/pipeline/runs").session(viewer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs", hasSize(0)));

        // 4. Open a discretionary trade for quant_member and verify Live Master Target Sheet alignment
        mockMvc.perform(post("/api/trade/diary").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.1500,
                      "stop_price": 1.1300,
                      "quantity": 5000,
                      "equity": 50000,
                      "entry_reason": "Trend alignment test"
                    }
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/pipeline/targets/live?equity=50000&pairs=EUR/USD,USD/JPY,GOLD/USD").session(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.targets", hasSize(3)))
            .andExpect(jsonPath("$.targets[0].discretionary_alignment").value("ALIGNED"));

        // Divergent short position on USD/JPY (positive trend + positive carry -> LONG signal vs short discretionary)
        mockMvc.perform(post("/api/trade/diary").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "USD/JPY",
                      "side": "short",
                      "entry_price": 158.00,
                      "stop_price": 160.00,
                      "quantity": 200,
                      "equity": 50000,
                      "entry_reason": "Contrarian short against spine"
                    }
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/pipeline/targets/live?equity=50000&target_vol_pct=12&pairs=EUR/USD,USD/JPY,GOLD/USD").session(member))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targets[1].discretionary_alignment").value("DIVERGENT"));

        // Empty-body pipeline backtest exercises PipelineBacktestRequest.defaults() on seeded pairs
        alignedRepository.upsertOhlcBars("GBP/USD", "SEED", generateBars(
            IncrementalOhlcSyncService.lastCompletedNyTradingDate(clock), 340, 1.2400, 0.0003), clock.instant());
        alignedRepository.upsertOhlcBars("AUD/USD", "SEED", generateBars(
            IncrementalOhlcSyncService.lastCompletedNyTradingDate(clock), 340, 0.6500, -0.0002), clock.instant());

        mockMvc.perform(post("/api/pipeline/backtest").session(member).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // 5. Validation edge cases -> 400 Bad Request
        mockMvc.perform(post("/api/pipeline/backtest").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile_id\":\"non-existent\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/pipeline/backtest").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"days\":50}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/historical-ohlc/badpath").session(member))
            .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/historical-ohlc/EUR/USD/notanumber").session(member))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/backtest").session(member).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void marketDataControllerServesQueryAndLegacyOhlcAndHistoricalPaths() {
        MarketDataService mockMarket = Mockito.mock(MarketDataService.class);
        List<OhlcBar> sampleOhlc = List.of(new OhlcBar("2025-01-02", 1.05, 1.06, 1.04, 1.055, 100.0));
        List<HistoricalPoint> sampleHist = List.of(new HistoricalPoint("2025-01-02", 1.055));
        Mockito.when(mockMarket.fetchHistoricalOhlc("EUR/USD", 30)).thenReturn(sampleOhlc);
        Mockito.when(mockMarket.fetchHistorical("EUR/USD", 30)).thenReturn(sampleHist);

        MarketDataController controller = new MarketDataController(mockMarket);
        assertThat(controller.historicalOhlc("EUR/USD", 30)).hasSize(1);
        assertThat(controller.historicalOhlcLegacy("/EUR/USD/30")).hasSize(1);
        assertThat(controller.historicalLegacy("EUR/USD/30")).hasSize(1);
    }

    private static List<OhlcBar> generateBars(LocalDate endInclusive, int tradingDays, double startPrice, double drift) {
        List<LocalDate> dates = new ArrayList<>(tradingDays);
        LocalDate cursor = endInclusive;
        while (dates.size() < tradingDays) {
            if (isWeekday(cursor)) {
                dates.add(cursor);
            }
            cursor = cursor.minusDays(1);
        }
        List<OhlcBar> bars = new ArrayList<>(tradingDays);
        double price = startPrice;
        for (int i = dates.size() - 1; i >= 0; i--) {
            double open = price;
            double close = Math.max(0.01, price + drift);
            double high = Math.max(open, close) * 1.003;
            double low = Math.min(open, close) * 0.997;
            bars.add(new OhlcBar(dates.get(i).toString(), open, high, low, close, 100.0));
            price = close;
        }
        return bars;
    }

    private static boolean isWeekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }
}
