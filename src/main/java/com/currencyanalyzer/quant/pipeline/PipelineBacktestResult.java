package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.List;

/**
 * Full 5-stage simulation output including summary attribution, equity curve, and traces.
 */
public record PipelineBacktestResult(
    String profileId,
    LocalDate startDate,
    LocalDate endDate,
    PipelineSummary summary,
    List<LedgerSnapshot> equityCurve,
    List<StageTrace> traces
) {
    public PipelineBacktestResult {
        equityCurve = equityCurve == null ? List.of() : List.copyOf(equityCurve);
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}
