package com.saanscheck.aqi;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AQI bands, breakpoints and plausibility limits, bound from {@code aqi.yml}. */
@ConfigurationProperties(prefix = "saanscheck.aqi")
public record AqiProperties(
        List<Band> bands,
        boolean valuesAreSubIndices,
        int maxPlausibleSubIndex,
        int minPollutants,
        List<String> requiredAnyOf,
        Map<String, Double> maxPlausible,
        Map<String, List<Breakpoint>> breakpoints) {

    public record Band(AqiCategory category, int min, int max) {
    }

    public record Breakpoint(double cLow, double cHigh, int iLow, int iHigh) {
    }
}
