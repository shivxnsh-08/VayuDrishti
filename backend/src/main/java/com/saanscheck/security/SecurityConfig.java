package com.saanscheck.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT security using Spring's own resource-server support (HS256 with a shared
 * secret from the JWT_SECRET env var), rather than a hand-written filter.
 *
 * <p>Public: auth, station reads, health status (details only for ADMIN) and the Prometheus scrape.
 * API docs (Swagger) are public unless {@code saanscheck.security.public-api-docs=false}.
 * ADMIN: /api/v1/admin/** and other actuator endpoints. Everything else needs a valid token.
 * Spring Security's default response headers (nosniff, frame DENY, no-cache, HSTS on HTTPS) stay on.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, org.springframework.jdbc.core.JdbcTemplate jdbc,
                                           @Value("${saanscheck.security.public-api-docs:true}") boolean publicApiDocs)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // stateless API with bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .access((auth2, ctx) -> new AuthorizationDecision(
                                publicApiDocs || auth2.get().getAuthorities().stream()
                                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))))
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/stations", "/api/v1/stations/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/model-info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ingestion/health").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Telegram is for normal users only: administrator accounts cannot link, test or use it.
                        .requestMatchers("/api/v1/me/telegram/**").hasRole("USER")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter(jdbc))));
        return http.build();
    }

    @Bean
    SecretKey jwtSecretKey(SecurityProperties props) {
        String secret = props.jwt() == null ? null : props.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set and at least 32 bytes long (e.g. `openssl rand -base64 48`)");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Validates signature (HS256), expiry/not-before against the app clock, and the issuer claim. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey key, Clock clock, SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(props.jwt().issuer())));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Authorities come from the account's CURRENT role in the database, not from the "roles" claim frozen into the
     * token at sign-in. A promotion or demotion therefore applies to the very next request (an old USER token of a
     * promoted admin works for admin pages; a demoted admin's token loses access at once). The signature, expiry
     * and issuer are still checked by the decoder first; a token for a deleted account carries no role.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            long id;
            try {
                id = Long.parseLong(jwt.getSubject());
            } catch (RuntimeException e) {
                return java.util.List.of();
            }
            return jdbc.queryForList("SELECT role FROM users WHERE id = ?", String.class, id).stream()
                    .map(r -> (org.springframework.security.core.GrantedAuthority)
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
        });
        return converter;
    }
}
