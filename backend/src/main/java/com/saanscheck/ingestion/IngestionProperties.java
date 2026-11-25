package com.saanscheck.ingestion;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the data source is configuration, including field names, so an API
 * change means editing {@code application.yml}, not the code (spec risk table).
 */
@ConfigurationProperties(prefix = "saanscheck.ingestion")
public record IngestionProperties(
        boolean enabled,
        String baseUrl,
        String resourceId,
        String apiKey,
        String cron,
        int pageSize,
        int maxPages,
        Duration pageDelay,
        Duration connectTimeout,
        Duration readTimeout,
        Map<String, String> filters,
        String sourceZone,
        String timestampPattern,
        int stuckHours,
        Fields fields,
        Map<String, String> pollutantAliases,
        Retry retry,
        double stuckMinValue) {

    /**
     * Bounded retry for one page request: attempts include the first try; backoff doubles per attempt up to
     * {@code maxBackoff}; an HTTP Retry-After header is honoured up to {@code maxRetryAfter}.
     */
    public record Retry(int maxAttempts, Duration backoff, Duration maxBackoff, Duration maxRetryAfter) {
        public static final Retry DEFAULT = new Retry(4, Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    public Retry retryOrDefault() {
        return retry == null ? Retry.DEFAULT : retry;
    }

    /** JSON field names in one source record. VERIFY against a recorded real payload. */
    public record Fields(String station, String city, String state, String latitude, String longitude,
                         String pollutant, String value, String lastUpdate) {
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
