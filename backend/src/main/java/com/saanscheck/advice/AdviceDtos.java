package com.saanscheck.advice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;

/** Wire contract with {@code POST /advice}; must match forecast-service/app/schemas.py. */
public final class AdviceDtos {

    private AdviceDtos() {
    }

    /**
     * Only system-computed values cross this boundary: category, trend, profile, language and
     * the numbers the message may mention. The LLM never sees raw data it could misread.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AdviceRequest(String category, String trend, String healthProfile, String language,
                                Map<String, String> numbers) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AdviceResponse(String message, List<String> actionCodes, String source) {
    }
}
