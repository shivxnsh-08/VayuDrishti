package com.saanscheck.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.saanscheck.common.HttpClients;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client for the data.gov.in resource API, with explicit, bounded retries.
 *
 * <ul>
 *   <li>Timeouts: connect / read from configuration (no call can hang).</li>
 *   <li>Retried (exponential backoff, capped): network, timeout, DNS, TLS, HTTP 5xx, HTTP 429.
 *       A {@code Retry-After} header (seconds or HTTP date) is honoured up to {@code max-retry-after}.</li>
 *   <li>Not retried: 401/403 (key), other 4xx (configuration), unparseable payloads. These fail at once.</li>
 *   <li>Every retry is counted and reported to the run record; the API key never reaches logs or the DB.</li>
 * </ul>
 */
@Component
public class DataGovClient {

    private static final Logger log = LoggerFactory.getLogger(DataGovClient.class);
    private static final Pattern KEY_IN_URL = Pattern.compile("(api-key=)[^&\\s\"]+");

    /** Result of one page request. */
    public record Fetched(JsonNode body, int retries, int httpStatus) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final RestClient client;
    private final IngestionProperties props;
    Sleeper sleeper = d -> Thread.sleep(d);

    public DataGovClient(RestClient.Builder builder, IngestionProperties props) {
        this.props = props;
        this.client = HttpClients.withTimeouts(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
    }

    public Fetched fetchPage(int offset) {
        IngestionProperties.Retry retry = props.retryOrDefault();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                JsonNode body = request(offset);
                return new Fetched(body, attempt - 1, 200);
            } catch (RuntimeException e) {
                ErrorCategory category = ErrorClassifier.classify(e);
                Integer status = ErrorClassifier.httpStatus(e);
                String message = redact(e.getMessage());
                if (!category.retryable() || attempt >= retry.maxAttempts()) {
                    throw new SourceFetchException(category, status, attempt - 1,
                            category + (status != null ? " (HTTP " + status + ")" : "") + " after " + attempt
                                    + " attempt(s): " + message, e);
                }
                Duration wait = backoff(retry, attempt, retryAfter(e, retry));
                log.warn("data.gov.in page at offset {} failed ({}{}), retry {}/{} in {} ms", offset, category,
                        status != null ? ", HTTP " + status : "", attempt, retry.maxAttempts() - 1, wait.toMillis());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SourceFetchException(category, status, attempt - 1, "interrupted while waiting to retry", e);
                }
            }
        }
    }

    private JsonNode request(int offset) {
        return client.get()
                .uri(b -> {
                    b.path("/resource/{id}")
                            .queryParam("api-key", props.apiKey())
                            .queryParam("format", "json")
                            .queryParam("offset", offset)
                            .queryParam("limit", props.pageSize())
                            // Without a total order, offset pages overlap and some records are never
                            // returned (measured: 58 of 3,906 lost per run). Station + pollutant is
                            // unique within one snapshot, so this ordering makes pages disjoint.
                            .queryParam("sort[" + props.fields().station() + "]", "asc")
                            .queryParam("sort[" + props.fields().pollutant() + "]", "asc");
                    if (props.filters() != null) {
                        for (Map.Entry<String, String> e : props.filters().entrySet()) {
                            if (e.getValue() != null && !e.getValue().isBlank()) {
                                b.queryParam("filters[" + e.getKey() + "]", e.getValue());
                            }
                        }
                    }
                    return b.build(props.resourceId());
                })
                .retrieve()
                .body(JsonNode.class);
    }

    /** Exponential backoff (backoff x 2^(attempt-1), capped), or the server's Retry-After if longer. */
    static Duration backoff(IngestionProperties.Retry retry, int attempt, Duration retryAfter) {
        long exp = retry.backoff().toMillis() * (1L << Math.min(attempt - 1, 20));
        Duration d = Duration.ofMillis(Math.min(exp, retry.maxBackoff().toMillis()));
        if (retryAfter != null && retryAfter.compareTo(d) > 0) {
            d = retryAfter.compareTo(retry.maxRetryAfter()) > 0 ? retry.maxRetryAfter() : retryAfter;
        }
        return d;
    }

    /** Parses Retry-After as delta-seconds or an HTTP date. Null if absent or unparseable. */
    static Duration retryAfter(Throwable e, IngestionProperties.Retry retry) {
        if (!(e instanceof RestClientResponseException r)) {
            return null;
        }
        HttpHeaders headers = r.getResponseHeaders();
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        return parseRetryAfter(value, Instant.now());
    }

    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration d = Duration.between(now, at);
            return d.isNegative() ? Duration.ZERO : d;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Exception messages can contain the request URL; never let the API key reach logs or the DB. */
    public static String redact(String message) {
        return message == null ? null : KEY_IN_URL.matcher(message).replaceAll("$1***");
    }
}
