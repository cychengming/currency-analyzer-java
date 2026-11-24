package com.currencyanalyzer.pubsub;

@FunctionalInterface
public interface AlertEventPublisher {
    void publish(AlertTriggeredEvent event);
}
