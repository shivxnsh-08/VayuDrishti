package com.saanscheck.advice;

import com.saanscheck.common.HttpClients;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls {@code POST /advice}. Failures propagate; {@link AlertMessageComposer} falls back to a template. */
@Component
public class AdviceClient {

    private final RestClient client;

    public AdviceClient(RestClient.Builder builder, AdviceProperties props) {
        this.client = HttpClients.withTimeouts(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
    }

    @CircuitBreaker(name = "advice")
    public AdviceDtos.AdviceResponse advice(AdviceDtos.AdviceRequest request) {
        return client.post()
                .uri("/advice")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AdviceDtos.AdviceResponse.class);
    }
}
