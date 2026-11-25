package com.saanscheck.advice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saanscheck.advice")
public record AdviceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
