package com.saanscheck.alerts;

import java.time.LocalTime;

/** Quiet-hours check, including windows that wrap midnight (e.g. 22:00-07:00). Start inclusive, end exclusive. */
public final class QuietHours {

    private QuietHours() {
    }

    public static boolean isQuiet(LocalTime now, LocalTime start, LocalTime end) {
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }
}
