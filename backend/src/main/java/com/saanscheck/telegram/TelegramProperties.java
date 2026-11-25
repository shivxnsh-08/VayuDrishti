package com.saanscheck.telegram;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saanscheck.telegram")
public record TelegramProperties(String botToken, String botUsername, String baseUrl, boolean pollUpdates,
                                 Duration linkCodeTtl, Duration connectTimeout, Duration readTimeout) {

    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }
}
