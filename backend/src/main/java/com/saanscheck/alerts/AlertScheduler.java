package com.saanscheck.alerts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"saanscheck.scheduling.enabled", "saanscheck.alerts.enabled"},
        havingValue = "true", matchIfMissing = true)
public class AlertScheduler {

    private final AlertEvaluator evaluator;
    private final AlertDispatcher dispatcher;

    public AlertScheduler(AlertEvaluator evaluator, AlertDispatcher dispatcher) {
        this.evaluator = evaluator;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${saanscheck.alerts.evaluation-interval}", initialDelayString = "PT1M")
    public void evaluate() {
        evaluator.evaluateAll();
    }

    @Scheduled(fixedDelayString = "${saanscheck.alerts.dispatch-interval}", initialDelayString = "PT30S")
    public void dispatch() {
        dispatcher.dispatchDue();
    }
}
