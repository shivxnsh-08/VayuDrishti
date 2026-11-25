package com.saanscheck.forecast;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saanscheck.forecast")
public record ForecastProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                 int historyHours, int minHistoryPoints, Duration freshFor, String refreshCron,
                                 Duration maxDataAge) {
}
