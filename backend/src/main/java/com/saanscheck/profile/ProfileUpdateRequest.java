package com.saanscheck.profile;

import com.saanscheck.aqi.AqiCategory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** PUT body. Phone numbers, exact addresses etc. are deliberately not accepted. */
public record ProfileUpdateRequest(
        @NotNull HealthProfile healthProfile,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @Size(max = 100) String homeCity,
        @Size(max = 64) String stationId,
        @Pattern(regexp = "en|hi") String language,
        AqiCategory alertMinCategory,
        LocalTime quietStart,
        LocalTime quietEnd,
        Boolean severeOverridesQuiet,
        Boolean morningBriefingEnabled,          // null = keep the current setting
        LocalTime morningBriefingTime,           // local time in the alert zone (Asia/Kolkata); null = keep
        Boolean aqiAlertsEnabled) {              // AQI event alerts incl. 8-hour reminders; null = keep
}
