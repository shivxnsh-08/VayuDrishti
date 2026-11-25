package com.saanscheck.forecast;

import com.saanscheck.common.HttpClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Python forecast service. Three layers of protection:
 * <ol>
 *   <li>Timeouts on connect and read (a slow service is treated as a failed one).</li>
 *   <li>Retry with backoff for transient errors (network, 5xx) - at most 2 attempts.</li>
 *   <li>Circuit breaker: after repeated failures calls are short-circuited for a while,
 *       so a dead service costs microseconds, not a timeout per request.</li>
 * </ol>
 * Exceptions propagate; {@link ForecastService} turns them into status UNAVAILABLE.
 */
@Component
public class ForecastClient {

    private final RestClient client;

    public ForecastClient(RestClient.Builder builder, ForecastProperties props) {
        this.client = HttpClients.withTimeouts(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
    }

    @CircuitBreaker(name = "forecast")
    public com.fasterxml.jackson.databind.JsonNode modelInfo() {
        return client.get().uri("/model-info").retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
    }

    @Retry(name = "forecast")
    @CircuitBreaker(name = "forecast")
    public ForecastDtos.ForecastResponse forecast(ForecastDtos.ForecastRequest request) {
        return client.post()
                .uri("/forecast")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ForecastDtos.ForecastResponse.class);
    }
}
