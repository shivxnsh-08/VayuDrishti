package com.saanscheck.ingestion;

import com.saanscheck.aqi.AqiCalculator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Pure validation rules for a single value, plus stuck-sensor detection over recent history. */
@Component
public class ReadingValidator {

    private static final Set<String> MISSING_TOKENS = Set.of("", "NA", "N/A", "NULL", "NONE", "-", "--");
    private static final double EPS = 1e-9;

    private final AqiCalculator calculator;

    public ReadingValidator(AqiCalculator calculator) {
        this.calculator = calculator;
    }

    public Result validate(String pollutant, String rawValue) {
        if (rawValue == null || MISSING_TOKENS.contains(rawValue.trim().toUpperCase(Locale.ROOT))) {
            return new Result(null, ReadingQuality.MISSING);
        }
        double v;
        try {
            v = Double.parseDouble(rawValue.trim());
        } catch (NumberFormatException e) {
            return new Result(null, ReadingQuality.INVALID);
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > calculator.maxPlausible(pollutant)) {
            return new Result(null, ReadingQuality.INVALID);
        }
        return new Result(v, ReadingQuality.OK);
    }

    /**
     * A sensor is "stuck" when the current value equals each of the previous {@code stuckHours - 1}
     * readings. {@code previous} is most-recent-first. With too little history we cannot tell, so false.
     */
    public static boolean isStuck(List<Double> previous, double current, int stuckHours) {
        int needed = stuckHours - 1;
        if (needed <= 0 || previous.size() < needed) {
            return false;
        }
        for (int i = 0; i < needed; i++) {
            Double p = previous.get(i);
            if (p == null || Math.abs(p - current) > EPS) {
                return false;
            }
        }
        return true;
    }

    public record Result(Double value, ReadingQuality quality) {
    }
}
