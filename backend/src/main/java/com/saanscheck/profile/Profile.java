package com.saanscheck.profile;

import com.saanscheck.aqi.AqiCategory;
import java.time.LocalTime;

/** Stored profile. Coordinates are already rounded (coarse) when they reach this record. */
public record Profile(long userId, HealthProfile healthProfile, Double latitude, Double longitude, String homeCity,
                      String stationId, String language, AqiCategory alertMinCategory,
                      LocalTime quietStart, LocalTime quietEnd, boolean severeOverridesQuiet,
                      String telegramChatId) {
}
