package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.UserAlertRuleRepository.UserAlertEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertTriggeredEvent(
    long alertId,
    String pair,
    String alertType,
    Double percentChange,
    Double oldRate,
    Double newRate,
    Double triggerValue,
    Double thresholdValue,
    String timestamp,
    Long userId,
    UUID ruleId,
    String ruleName,
    Boolean notifyEmail
) {
    public AlertTriggeredEvent(
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
        this(
            alertId,
            pair,
            alertType,
            percentChange,
            oldRate,
            newRate,
            triggerValue,
            thresholdValue,
            timestamp,
            null,
            null,
            null,
            null
        );
    }

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

    public static AlertTriggeredEvent from(UserAlertEntry entry, boolean notifyEmail) {
        return new AlertTriggeredEvent(
            entry.id(),
            entry.pair(),
            entry.alertType(),
            entry.percentChange(),
            entry.oldRate(),
            entry.newRate(),
            entry.triggerValue(),
            entry.thresholdValue(),
            entry.timestamp(),
            entry.userId(),
            entry.ruleId(),
            entry.ruleName(),
            notifyEmail
        );
    }
}
