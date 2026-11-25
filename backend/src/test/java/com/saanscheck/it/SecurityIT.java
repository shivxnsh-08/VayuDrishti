package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Security review regressions (Priority 6). */
class SecurityIT extends AbstractIT {

    @Autowired
    JwtEncoder encoder;

    /** Tokens below are for user 1; its role comes from the database (the token's "roles" claim is not trusted). */
    @BeforeEach
    void adminAccount() {
        jdbc.update("INSERT INTO users (id, email, password_hash, role) VALUES (1, 'root@test.local', 'x', 'ADMIN')");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('users', 'id'), 1000)", Long.class);
    }

    private String token(String issuer, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("1")
                .issuedAt(clock.instant()).expiresAt(clock.instant().plus(ttl))
                .claim("roles", List.of("ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void tokenFromAnotherIssuerIsRejectedEvenWithValidSignature() {
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", token("saanscheck", Duration.ofHours(1)), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", token("evil", Duration.ofHours(1)), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRolesClaimIsNotTrustedTheDatabaseRoleDecides() {
        jdbc.update("UPDATE users SET role = 'USER' WHERE id = 1");      // the token still claims ADMIN
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", token("saanscheck", Duration.ofHours(1)), null,
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredTokenIsRejected() {
        String t = token("saanscheck", Duration.ofMinutes(30));
        clock.advance(Duration.ofHours(2));
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", t, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tamperedTokenIsRejected() {
        String t = token("saanscheck", Duration.ofHours(1));
        String tampered = t.substring(0, t.length() - 2) + (t.endsWith("A") ? "BB" : "AA");
        assertThat(exchange(HttpMethod.GET, "/api/v1/me/alerts", tampered, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthDetailsOnlyForAdmin() {
        Map<String, Object> anon = http.getForObject("/actuator/health", Map.class);
        assertThat(anon).containsEntry("status", "UP").doesNotContainKey("components");

        Map<String, Object> admin = exchange(HttpMethod.GET, "/actuator/health", token("saanscheck", Duration.ofHours(1)),
                null, Map.class).getBody();
        assertThat(admin).containsKey("components");
    }

    @Test
    void securityHeadersArePresent() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/stations/nearest?lat=28.6&lon=77.2", String.class);
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeaders().getFirst("Cache-Control")).contains("no-store");
    }

    @Test
    void repeatedFailedLoginsAreThrottledPerAccount() {
        register("victim@test.local");
        Map<String, String> wrong = Map.of("email", "victim@test.local", "password", "wrong-password-x");
        for (int i = 0; i < 5; i++) {
            assertThat(http.postForEntity("/api/v1/auth/login", wrong, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        Map<String, String> right = Map.of("email", "victim@test.local", "password", "correct-horse-battery");
        assertThat(http.postForEntity("/api/v1/auth/login", right, String.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // other accounts are unaffected
        register("other@test.local");
        assertThat(http.postForEntity("/api/v1/auth/login",
                Map.of("email", "other@test.local", "password", "correct-horse-battery"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        clock.advance(Duration.ofMinutes(16));
        assertThat(http.postForEntity("/api/v1/auth/login", right, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void apiDocsArePublicByDefault() {
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
