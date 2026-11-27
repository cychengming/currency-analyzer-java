package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.auth.RoleGuard;
import com.currencyanalyzer.quant.pipeline.MasterPipelineService.PipelineBacktestRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MasterPipelineController {

    private final MasterPipelineService pipelineService;
    private final RoleGuard roleGuard;

    public MasterPipelineController(MasterPipelineService pipelineService, RoleGuard roleGuard) {
        this.pipelineService = pipelineService;
        this.roleGuard = roleGuard;
    }

    @GetMapping("/backtest/conditions")
    public Map<String, Object> backtestConditions(Authentication authentication) {
        requireUsername(authentication);
        return pipelineService.backtestConditionsSpec();
    }

    @PostMapping("/backtest")
    public Map<String, Object> runRuleBacktest(
        Authentication authentication,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        requireUsername(authentication);
        return pipelineService.runSinglePairRuleBacktest(payload);
    }

    @GetMapping("/pipeline/profiles")
    public Map<String, Object> listProfiles(Authentication authentication) {
        requireUsername(authentication);
        return pipelineService.describeCatalog();
    }

    @PostMapping("/pipeline/backtest")
    public Map<String, Object> runPipelineBacktest(
        Authentication authentication,
        @RequestBody(required = false) PipelineBacktestPayload payload
    ) {
        String username = requireUsername(authentication);
        boolean persistRun = !roleGuard.isViewer(authentication);
        PipelineBacktestRequest request = payload == null
            ? PipelineBacktestRequest.defaults()
            : new PipelineBacktestRequest(
                payload.profileId(),
                payload.pairs(),
                payload.days(),
                payload.initialEquityUsd(),
                payload.targetVolatilityPct(),
                payload.maxCurrencyExposureMultiple(),
                payload.rebalanceInertiaPct(),
                payload.brokerCarryMarkupPct(),
                payload.enableCarry(),
                payload.includeArena()
            );
        return pipelineService.runPipelineArena(username, persistRun, request);
    }

    @GetMapping("/pipeline/targets/live")
    public Map<String, Object> liveTargets(
        Authentication authentication,
        @RequestParam(name = "equity", required = false) Double equity,
        @RequestParam(name = "target_vol_pct", required = false) Double targetVolPct,
        @RequestParam(name = "profile_id", required = false) String profileId,
        @RequestParam(name = "pairs", required = false) String pairsCsv
    ) {
        String username = requireUsername(authentication);
        List<String> pairs = (pairsCsv == null || pairsCsv.isBlank())
            ? List.of()
            : Arrays.stream(pairsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return pipelineService.computeLiveTargets(username, equity, targetVolPct, profileId, pairs);
    }

    @GetMapping("/pipeline/runs")
    public Map<String, Object> listPipelineRuns(
        Authentication authentication,
        @RequestParam(name = "limit", defaultValue = "15") int limit
    ) {
        String username = requireUsername(authentication);
        return Map.of(
            "success", true,
            "runs", pipelineService.listRecentRuns(username, limit)
        );
    }

    private static String requireUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return authentication.getName();
    }

    public record PipelineBacktestPayload(
        @JsonProperty("profile_id") String profileId,
        List<String> pairs,
        Integer days,
        @JsonProperty("initial_equity_usd") Double initialEquityUsd,
        @JsonProperty("target_volatility_pct") Double targetVolatilityPct,
        @JsonProperty("max_currency_exposure_multiple") Double maxCurrencyExposureMultiple,
        @JsonProperty("rebalance_inertia_pct") Double rebalanceInertiaPct,
        @JsonProperty("broker_carry_markup_pct") Double brokerCarryMarkupPct,
        @JsonProperty("enable_carry") Boolean enableCarry,
        @JsonProperty("include_arena") Boolean includeArena
    ) {
    }
}
