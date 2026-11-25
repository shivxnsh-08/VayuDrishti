package com.saanscheck.profile;

import com.saanscheck.aqi.AqiCategory;
import java.time.LocalTime;

/** Profile as returned to its owner. The chat id itself is never returned, only whether one is linked. */
public record ProfileView(HealthProfile healthProfile, Double latitude, Double longitude, String homeCity,
                          String stationId, String language, AqiCategory alertMinCategory,
                          AqiCategory effectiveMinCategory, LocalTime quietStart, LocalTime quietEnd,
                          boolean severeOverridesQuiet, boolean telegramLinked,
                          boolean morningBriefingEnabled, LocalTime morningBriefingTime,
                          boolean aqiAlertsEnabled) {
}
