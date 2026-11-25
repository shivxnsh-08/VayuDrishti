package com.saanscheck.aqi;

/**
 * National AQI categories, ordered from best to worst. The numeric band limits are
 * NOT here; they come from {@code aqi.yml} via {@link AqiProperties}. Only the order
 * (severity) is a property of the enum.
 */
public enum AqiCategory {
    GOOD, SATISFACTORY, MODERATE, POOR, VERY_POOR, SEVERE;

    public boolean isAtLeast(AqiCategory other) {
        return this.ordinal() >= other.ordinal();
    }

    public boolean isWorseThan(AqiCategory other) {
        return this.ordinal() > other.ordinal();
    }

    public static AqiCategory max(AqiCategory a, AqiCategory b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
