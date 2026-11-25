package com.saanscheck.aqi;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * Pure AQI logic driven entirely by configuration.
 *
 * <ul>
 *   <li>{@link #categorize(int)}: maps an AQI value to its band.</li>
 *   <li>{@link #subIndex(String, double)}: concentration -> sub-index by linear interpolation
 *       inside the matching breakpoint row (only used when the feed gives concentrations).</li>
 *   <li>{@link #overall(Map)}: overall AQI = max sub-index, subject to the minimum-pollutant rule.</li>
 * </ul>
 */
@Component
public class AqiCalculator {

    private final AqiProperties props;
    private final List<AqiProperties.Band> bands;

    public AqiCalculator(AqiProperties props) {
        this.props = props;
        this.bands = props.bands().stream().sorted(Comparator.comparingInt(AqiProperties.Band::min)).toList();
        if (bands.isEmpty()) {
            throw new IllegalStateException("saanscheck.aqi.bands must not be empty");
        }
    }

    public AqiCategory categorize(int aqi) {
        if (aqi < bands.getFirst().min()) {
            return bands.getFirst().category();
        }
        for (AqiProperties.Band band : bands) {
            if (aqi >= band.min() && aqi <= band.max()) {
                return band.category();
            }
        }
        // Above the top band's max (or in a gap): treat as the worst band reached.
        AqiCategory result = bands.getFirst().category();
        for (AqiProperties.Band band : bands) {
            if (aqi >= band.min()) {
                result = band.category();
            }
        }
        return result;
    }

    /** Sub-index for a raw concentration, or empty if the pollutant has no breakpoints configured. */
    public OptionalInt subIndex(String pollutant, double concentration) {
        List<AqiProperties.Breakpoint> rows = props.breakpoints() == null ? null : props.breakpoints().get(pollutant);
        if (rows == null || rows.isEmpty() || concentration < 0) {
            return OptionalInt.empty();
        }
        AqiProperties.Breakpoint last = rows.getLast();
        if (concentration > last.cHigh()) {
            return OptionalInt.of(last.iHigh());   // capped at the top of the scale
        }
        for (int i = 0; i < rows.size(); i++) {
            AqiProperties.Breakpoint bp = rows.get(i);
            // Breakpoint tables have small gaps (e.g. 30 -> 31); a value in a gap belongs to the next row.
            double upper = i + 1 < rows.size() ? rows.get(i + 1).cLow() : bp.cHigh();
            if (concentration >= bp.cLow() && (concentration < upper || i + 1 == rows.size())) {
                double c = Math.min(concentration, bp.cHigh());
                double value = (bp.iHigh() - bp.iLow()) / (bp.cHigh() - bp.cLow()) * (c - bp.cLow()) + bp.iLow();
                return OptionalInt.of((int) Math.round(value));
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Overall AQI from the valid pollutant values of one station-hour. Values are interpreted as
     * sub-indices or concentrations depending on {@code values-are-sub-indices}.
     */
    public Optional<AqiResult> overall(Map<String, Double> pollutantValues) {
        String dominant = null;
        int max = -1;
        int count = 0;
        boolean hasRequired = props.requiredAnyOf() == null || props.requiredAnyOf().isEmpty();
        for (Map.Entry<String, Double> e : pollutantValues.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            OptionalInt idx = props.valuesAreSubIndices()
                    ? OptionalInt.of((int) Math.round(e.getValue()))
                    : subIndex(e.getKey(), e.getValue());
            if (idx.isEmpty()) {
                continue;
            }
            count++;
            if (props.requiredAnyOf() != null && props.requiredAnyOf().contains(e.getKey())) {
                hasRequired = true;
            }
            if (idx.getAsInt() > max) {
                max = idx.getAsInt();
                dominant = e.getKey();
            }
        }
        if (count < props.minPollutants() || !hasRequired) {
            return Optional.empty();
        }
        return Optional.of(new AqiResult(max, categorize(max), dominant, count));
    }

    /** Upper plausibility limit for a pollutant value as it arrives from the feed. */
    public double maxPlausible(String pollutant) {
        if (props.valuesAreSubIndices()) {
            return props.maxPlausibleSubIndex();
        }
        Double limit = props.maxPlausible() == null ? null : props.maxPlausible().get(pollutant);
        return limit == null ? Double.MAX_VALUE : limit;
    }
}
