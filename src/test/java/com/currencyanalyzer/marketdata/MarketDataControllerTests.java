package com.currencyanalyzer.marketdata;

import com.currencyanalyzer.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MarketDataControllerTests {

    private final MarketDataService service = mock(MarketDataService.class);
    private final MockMvc mockMvc = standaloneSetup(new MarketDataController(service))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void returnsLiveRatesInFrontendContract() throws Exception {
        when(service.fetchLiveRates()).thenReturn(Map.of(
            "EUR/USD",
            new LiveRate(1.0876, 0.0021, 0.19)
        ));

        mockMvc.perform(get("/api/live-rates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['EUR/USD'].rate").value(1.0876))
            .andExpect(jsonPath("$['EUR/USD'].change").value(0.0021))
            .andExpect(jsonPath("$['EUR/USD'].changePercent").value(0.19));
    }

    @Test
    void returnsHistoricalSeriesInFrontendContract() throws Exception {
        when(service.fetchHistorical("EUR/USD", 30)).thenReturn(List.of(
            new HistoricalPoint("2026-11-10", 1.0876)
        ));

        mockMvc.perform(get("/api/historical")
                .param("pair", "EUR/USD")
                .param("days", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].date").value("2026-11-10"))
            .andExpect(jsonPath("$[0].rate").value(1.0876));
    }

    @Test
    void returnsValidationErrorForMalformedPair() throws Exception {
        when(service.fetchHistorical("success", 30))
            .thenThrow(new IllegalArgumentException("Invalid currency pair: success"));

        mockMvc.perform(get("/api/historical")
                .param("pair", "success")
                .param("days", "30"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Invalid currency pair: success"));
    }
}
