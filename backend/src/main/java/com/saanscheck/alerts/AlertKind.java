package com.saanscheck.alerts;

/**
 * Notification kinds stored in {@code alerts.kind} (the notification history).
 * <ul>
 *   <li>AQI_THRESHOLD_CROSSED, AQI_DETERIORATION, AQI_RECOVERY, AQI_STABILITY_REMINDER: change-based AQI events
 *       from genuine new observations ({@link AqiEventRules}).</li>
 *   <li>FORECAST: forecast alert (existing forecast rules, {@link AlertDecisionEngine}); shown as "Forecast alert".</li>
 *   <li>STALE_NOTICE: the station stopped reporting (at most one per day); shown as "Stale data".</li>
 *   <li>MORNING_BRIEFING: the daily summary. TEST_MESSAGE: a user-requested test.</li>
 *   <li>CURRENT: legacy current-AQI alert written before the change-based engine (kept for history).</li>
 * </ul>
 * Only threshold crossings, deteriorations, forecast alerts and legacy CURRENT alerts take part in the forecast
 * alert cooldown; recovery, reminders, briefings, tests and stale notices never do.
 */
public enum AlertKind {
    FORECAST, CURRENT, STALE_NOTICE, MORNING_BRIEFING,
    AQI_THRESHOLD_CROSSED, AQI_DETERIORATION, AQI_RECOVERY, AQI_STABILITY_REMINDER, TEST_MESSAGE
}
