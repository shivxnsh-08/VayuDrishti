package com.saanscheck.common;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds RestClients with explicit timeouts. No outbound call in this app is allowed to wait forever. */
public final class HttpClients {

    private HttpClients() {
    }

    public static RestClient withTimeouts(RestClient.Builder builder, String baseUrl,
                                          Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
