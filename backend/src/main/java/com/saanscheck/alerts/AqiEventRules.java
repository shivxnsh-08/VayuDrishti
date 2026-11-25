package com.saanscheck.alerts;

import com.saanscheck.aqi.AqiCategory;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure rules for change-based AQI notifications (no I/O; every rule is unit-tested).
 *
 * <p><b>Reference.</b> Changes are measured against the <i>reference</i>: the AQI the user was last told about (the
 * last event), or the silent baseline taken when tracking started. Measuring against what the user knows (not just
 * the previous hourly value) catches both sudden jumps and slow drifts, and never reports a change the user would
 * not perceive. The previous observation is still recorded and shown.
 *
 * <p><b>Rules for a new, fresh observation</b> (each observation is processed once):
 * <ol>
 *   <li>below → at/above the threshold: {@code AQI_THRESHOLD_CROSSED};</li>
 *   <li>at/above → below the threshold: {@code AQI_RECOVERY};</li>
 *   <li>at/above, and a worse category than the reference or ≥ {@code significantChange} points higher:
 *       {@code AQI_DETERIORATION};</li>
 *   <li>still at/above, and a better category or ≥ {@code significantChange} points lower: {@code AQI_RECOVERY};</li>
 *   <li>otherwise, while at/above: a {@code AQI_STABILITY_REMINDER} once {@code reminderInterval} has passed since
 *       the last event or reminder (whichever is later).</li>
 * </ol>
 * Changes entirely below the user's threshold are not notified (the user chose not to be alerted there).
 * Stale observations (older than the freshness limit) never produce events or reminders.
 *
 * <p><b>Quiet hours.</b> Reminders wait until quiet hours end. Events are suppressed (recorded, not sent) unless
 * they are critical (category ≥ {@code criticalCategory}, a threshold crossing or deterioration), the global policy
 * allows it, and the user opted in. A suppressed event does not move the reference, so the change is announced
 * with the next observation after quiet hours if it still holds.
 */
public final class AqiEventRules {

    private AqiEventRules() {
    }

    /** Persisted per-user memory (see V9 {@code alert_state}). */
    public record State(String stationId, AqiCategory threshold, Instant lastObsTs, Integer lastObsAqi,
                        int refAqi, AqiCategory refCategory, Instant refTs, boolean above,
                        Instant lastEventAt, Instant lastReminderAt, Instant trackingSince) {
    }

    public record Observation(Instant ts, int aqi, AqiCategory category) {
    }

    public enum Action {
        /** Nothing to do (no data, stale data, nothing new and no reminder due, or a reminder held for quiet hours). */
        NONE,
        /** Start/restart tracking silently (first observation below the threshold, station or threshold changed). */
        BASELINE,
        /** A new observation without a notification: just remember it as processed. */
        OBSERVED,
        /** Notify an AQI event ({@link #kind}). */
        EVENT,
        /** Event happened but quiet hours hold it: record as suppressed, keep the reference. */
        SUPPRESSED,
        /** Notify a stability reminder. */
        REMINDER
    }

    /**
     * @param kind        event kind for EVENT/SUPPRESSED/REMINDER
     * @param previousAqi the reference AQI the change is measured against (null for a first observation)
     * @param change      current minus reference (null when there is no reference)
     * @param reason      short machine-readable explanation (logged and kept with suppressed records)
     */
    public record Decision(Action action, AlertKind kind, Integer previousAqi, Integer change, boolean critical,
                           boolean newObservation, boolean aboveNow, String reason) {
        static Decision none(String reason) {
            return new Decision(Action.NONE, null, null, null, false, false, false, reason);
        }
    }

    public static Decision decide(State s, Observation obs, String stationId, AqiCategory threshold, Instant now,
                                  Duration staleAfter, AlertEventProperties p, boolean quietNow,
                                  boolean severeOverridesQuiet) {
        if (obs == null) {
            return Decision.none("no_observation");
        }
        if (Duration.between(obs.ts(), now).compareTo(staleAfter) > 0) {
            return Decision.none("stale_observation");                 // never an event, never a reminder
        }
        boolean aboveNow = obs.category().isAtLeast(threshold);

        // No usable memory: first observation for this user, or the station changed.
        if (s == null || !stationId.equals(s.stationId())) {
            return aboveNow ? event(AlertKind.AQI_THRESHOLD_CROSSED, obs, null, true, aboveNow, threshold, p,
                    quietNow, severeOverridesQuiet, "first_observation_above_threshold")
                    : new Decision(Action.BASELINE, null, null, null, false, true, false, "baseline");
        }

        boolean newObs = s.lastObsTs() == null || obs.ts().isAfter(s.lastObsTs());
        if (newObs) {
            if (s.threshold() != threshold) {
                // The user changed their alert level: only announce if the air now reaches the new level and did not
                // under the old one; otherwise silently re-baseline.
                return aboveNow && !s.above()
                        ? event(AlertKind.AQI_THRESHOLD_CROSSED, obs, s, true, aboveNow, threshold, p, quietNow,
                                severeOverridesQuiet, "threshold_changed_now_above")
                        : new Decision(Action.BASELINE, null, null, null, false, true, aboveNow, "threshold_changed");
            }
            int diff = obs.aqi() - s.refAqi();
            if (!s.above() && aboveNow) {
                return event(AlertKind.AQI_THRESHOLD_CROSSED, obs, s, true, aboveNow, threshold, p, quietNow,
                        severeOverridesQuiet, "crossed_threshold");
            }
            if (s.above() && !aboveNow) {
                return event(AlertKind.AQI_RECOVERY, obs, s, true, aboveNow, threshold, p, quietNow,
                        severeOverridesQuiet, "fell_below_threshold");
            }
            if (aboveNow && (obs.category().isWorseThan(s.refCategory()) || diff >= p.significantChange())) {
                return event(AlertKind.AQI_DETERIORATION, obs, s, true, aboveNow, threshold, p, quietNow,
                        severeOverridesQuiet, obs.category().isWorseThan(s.refCategory()) ? "worse_category"
                                : "significant_increase");
            }
            if (aboveNow && (s.refCategory().isWorseThan(obs.category()) || -diff >= p.significantChange())) {
                return event(AlertKind.AQI_RECOVERY, obs, s, true, aboveNow, threshold, p, quietNow,
                        severeOverridesQuiet, s.refCategory().isWorseThan(obs.category()) ? "better_category"
                                : "significant_decrease");
            }
        } else if (s.threshold() != threshold) {
            return Decision.none("threshold_changed_waiting_for_new_observation");
        }

        // No event: is a stability reminder due?
        if (aboveNow && s.above()) {
            Instant anchor = latest(latest(s.lastEventAt(), s.lastReminderAt()), s.trackingSince());
            if (anchor != null && Duration.between(anchor, now).compareTo(p.reminderInterval()) >= 0) {
                if (quietNow) {
                    return new Decision(Action.NONE, AlertKind.AQI_STABILITY_REMINDER, s.refAqi(), obs.aqi() - s.refAqi(),
                            false, newObs, aboveNow, "reminder_waits_for_quiet_hours");
                }
                return new Decision(Action.REMINDER, AlertKind.AQI_STABILITY_REMINDER, s.refAqi(),
                        obs.aqi() - s.refAqi(), false, newObs, aboveNow, "stable_above_threshold");
            }
        }
        return newObs ? new Decision(Action.OBSERVED, null, s.refAqi(), obs.aqi() - s.refAqi(), false, true, aboveNow,
                "no_significant_change")
                : Decision.none("no_new_observation");
    }

    private static Decision event(AlertKind kind, Observation obs, State s, boolean newObs, boolean aboveNow,
                                  AqiCategory threshold, AlertEventProperties p, boolean quietNow,
                                  boolean severeOverridesQuiet, String reason) {
        Integer previous = s == null ? null : s.refAqi();
        Integer change = s == null ? null : obs.aqi() - s.refAqi();
        boolean critical = kind != AlertKind.AQI_RECOVERY && obs.category().isAtLeast(p.criticalCategory());
        if (quietNow && !(critical && p.criticalBypassesQuietHours() && severeOverridesQuiet)) {
            return new Decision(Action.SUPPRESSED, kind, previous, change, critical, newObs, aboveNow,
                    reason + ";quiet_hours");
        }
        return new Decision(Action.EVENT, kind, previous, change, critical, newObs, aboveNow, reason);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
