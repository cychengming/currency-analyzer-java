package com.currencyanalyzer.trade;

import com.currencyanalyzer.auth.RoleGuard;
import com.currencyanalyzer.trade.TradeJournalService.CloseTradeRequest;
import com.currencyanalyzer.trade.TradeJournalService.RiskSummary;
import com.currencyanalyzer.trade.TradeJournalService.TradeEntryRequest;
import com.currencyanalyzer.trade.TradeJournalService.TradeMutationResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/trade")
public class TradeJournalController {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_ERROR = "error";
    private static final String KEY_SUMMARY = "summary";
    private static final String KEY_ENTRY = "entry";
    private static final String VIEWER_DENIED =
        "Viewer accounts have read-only access and cannot modify paper trades or risk limits";

    private final TradeJournalService service;
    private final RoleGuard roleGuard;

    public TradeJournalController(TradeJournalService service, RoleGuard roleGuard) {
        this.service = service;
        this.roleGuard = roleGuard;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
        Authentication authentication,
        @RequestParam(name = "equity", required = false) Double equity,
        @RequestParam(name = "planned_risk_usd", required = false) Double plannedRiskUsd
    ) {
        String username = requireUsername(authentication);
        RiskSummary riskSummary = service.computeSummary(username, equity, plannedRiskUsd);
        return riskSummary.toMap();
    }

    @GetMapping("/settings")
    public Map<String, Object> tradeSettings(Authentication authentication) {
        String username = requireUsername(authentication);
        return Map.of(
            KEY_SUCCESS, true,
            "daily_risk_limit_pct", service.resolveDailyRiskLimitPct(username)
        );
    }

    @PutMapping("/settings")
    public Map<String, Object> saveTradeSettings(
        Authentication authentication,
        @RequestBody(required = false) RiskSettingsPayload payload
    ) {
        roleGuard.requireMemberOrAdmin(authentication, VIEWER_DENIED);
        if (payload == null) {
            throw new IllegalArgumentException("Trade settings payload is required");
        }
        String username = requireUsername(authentication);
        double saved = service.saveUserDailyRiskLimitPct(username, payload.dailyRiskLimitPct());
        return Map.of(
            KEY_SUCCESS, true,
            "daily_risk_limit_pct", saved
        );
    }

    @GetMapping("/diary")
    public Map<String, Object> listDiary(
        Authentication authentication,
        @RequestParam(name = "limit", defaultValue = "40") int limit
    ) {
        String username = requireUsername(authentication);
        List<Map<String, Object>> entries = service.listDiaryEntries(username, limit);
        return Map.of(
            KEY_SUCCESS, true,
            "entries", entries
        );
    }

    @PostMapping("/diary")
    public ResponseEntity<Map<String, Object>> createDiaryEntry(
        Authentication authentication,
        @RequestBody(required = false) DiaryPayload payload
    ) {
        roleGuard.requireMemberOrAdmin(authentication, VIEWER_DENIED);
        String username = requireUsername(authentication);
        TradeMutationResult result = service.createEntry(username, toEntryRequest(payload));
        if (!result.accepted()) {
            return ResponseEntity.badRequest().body(Map.of(
                KEY_SUCCESS, false,
                KEY_ERROR, result.errorMessage(),
                KEY_SUMMARY, result.summary().toMap()
            ));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            KEY_SUCCESS, true,
            KEY_ENTRY, result.entry(),
            KEY_SUMMARY, result.summary().toMap()
        ));
    }

    @PutMapping("/diary/{id}")
    public ResponseEntity<Map<String, Object>> updateDiaryEntry(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody(required = false) DiaryPayload payload
    ) {
        roleGuard.requireMemberOrAdmin(authentication, VIEWER_DENIED);
        String username = requireUsername(authentication);
        Optional<TradeMutationResult> resultOpt = service.updateEntry(username, id, toEntryRequest(payload));
        if (resultOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                KEY_SUCCESS, false,
                KEY_ERROR, "Trade diary entry not found"
            ));
        }
        TradeMutationResult result = resultOpt.get();
        if (!result.accepted()) {
            return ResponseEntity.badRequest().body(Map.of(
                KEY_SUCCESS, false,
                KEY_ERROR, result.errorMessage(),
                KEY_SUMMARY, result.summary().toMap()
            ));
        }
        return ResponseEntity.ok(Map.of(
            KEY_SUCCESS, true,
            KEY_ENTRY, result.entry(),
            KEY_SUMMARY, result.summary().toMap()
        ));
    }

    @PostMapping("/diary/{id}/close")
    public ResponseEntity<Map<String, Object>> closeDiaryEntry(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody(required = false) ClosePayload payload
    ) {
        roleGuard.requireMemberOrAdmin(authentication, VIEWER_DENIED);
        String username = requireUsername(authentication);
        CloseTradeRequest request = payload == null
            ? null
            : new CloseTradeRequest(payload.closeReason(), payload.closePrice(), payload.equity());
        Optional<TradeMutationResult> resultOpt = service.closeEntry(username, id, request);
        if (resultOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                KEY_SUCCESS, false,
                KEY_ERROR, "Trade diary entry not found"
            ));
        }
        TradeMutationResult result = resultOpt.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(KEY_SUCCESS, true);
        response.put(KEY_ENTRY, result.entry());
        response.put(KEY_SUMMARY, result.summary().toMap());
        return ResponseEntity.ok(response);
    }

    private static TradeEntryRequest toEntryRequest(DiaryPayload payload) {
        if (payload == null) {
            return null;
        }
        return new TradeEntryRequest(
            payload.pair(),
            payload.side(),
            payload.entryPrice(),
            payload.stopPrice(),
            payload.quantity(),
            payload.equity(),
            payload.entryReason(),
            payload.notes(),
            payload.closePrice(),
            payload.closeReason(),
            payload.atr(),
            payload.sigma()
        );
    }

    private static String requireUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return authentication.getName();
    }

    public record RiskSettingsPayload(
        @JsonProperty("daily_risk_limit_pct") Double dailyRiskLimitPct
    ) {
    }

    public record DiaryPayload(
        String pair,
        String side,
        @JsonProperty("entry_price") Double entryPrice,
        @JsonProperty("stop_price") Double stopPrice,
        Double quantity,
        Double equity,
        @JsonProperty("entry_reason") String entryReason,
        String notes,
        @JsonProperty("close_price") Double closePrice,
        @JsonProperty("close_reason") String closeReason,
        Double atr,
        Double sigma
    ) {
    }

    public record ClosePayload(
        @JsonProperty("close_reason") String closeReason,
        @JsonProperty("close_price") Double closePrice,
        Double equity
    ) {
    }
}
