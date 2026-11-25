package com.saanscheck.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saanscheck.security")
public record SecurityProperties(List<String> adminEmails, Jwt jwt) {

    public record Jwt(String secret, Duration ttl, String issuer) {
    }

    public boolean isAdminEmail(String email) {
        return adminEmails != null && adminEmails.stream()
                .anyMatch(a -> a != null && !a.isBlank() && a.trim().equalsIgnoreCase(email));
    }
}
