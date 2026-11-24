package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;

public record AlertTriggeredEvent(
    long alertId,
    String pair,
    String alertType,
    Double percentChange,
    Double oldRate,
    Double newRate,
    Double triggerValue,
    Double thresholdValue,
    String timestamp
) {
    public static AlertTriggeredEvent from(AlertEntry entry) {
        return new AlertTriggeredEvent(
            entry.id(),
            entry.pair(),
            entry.alertType(),
            entry.percentChange(),
            entry.oldRate(),
            entry.newRate(),
            entry.triggerValue(),
            entry.thresholdValue(),
            entry.timestamp()
        );
    }
}
