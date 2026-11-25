package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: real PostgreSQL + PostGIS in a container (migrations, geo queries
 * and constraints run for real), WireMock standing in for data.gov.in, the forecast/advice
 * service and Telegram. Skipped automatically when Docker is not available.
 *
 * <p>Containers are singletons shared by all IT classes so the Spring context can be cached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(AbstractIT.ClockConfig.class)
// Caching and scheduling off by default. Declared here (not in @DynamicPropertySource, which outranks
// everything) so subclasses such as RedisCacheIT / IngestionSchedulerIT can switch them on with their own
// @TestPropertySource.
@TestPropertySource(properties = {"saanscheck.cache.enabled=false", "saanscheck.scheduling.enabled=false"})
public abstract class AbstractIT {

    public static final Instant T0 = Instant.parse("2026-11-20T04:30:00Z");   // 10:00 IST

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    protected static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            WIREMOCK.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("saanscheck.ingestion.base-url", WIREMOCK::baseUrl);
        r.add("saanscheck.ingestion.api-key", () -> "test-key");
        r.add("saanscheck.ingestion.resource-id", () -> "test-resource");
        r.add("saanscheck.ingestion.page-delay", () -> "0s");
        r.add("saanscheck.forecast.base-url", WIREMOCK::baseUrl);
        r.add("saanscheck.advice.base-url", WIREMOCK::baseUrl);
        r.add("saanscheck.telegram.base-url", WIREMOCK::baseUrl);
        r.add("saanscheck.telegram.bot-token", () -> "TEST-TOKEN");
        r.add("saanscheck.security.jwt.secret", () -> "integration-test-secret-integration-test-secret");
        r.add("saanscheck.security.admin-emails", () -> "admin@test.local");
        r.add("saanscheck.ingestion.retry.backoff", () -> "10ms");
        r.add("saanscheck.ingestion.retry.max-backoff", () -> "50ms");
        r.add("resilience4j.retry.instances.forecast.wait-duration", () -> "10ms");
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE alerts, telegram_link_codes, profiles, users, forecasts, station_aqi, readings,
                         stations, ingestion_runs RESTART IDENTITY CASCADE
                """);
        WIREMOCK.resetAll();
        clock.set(T0);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // ---- data helpers ----------------------------------------------------------------------

    protected void insertStation(String id, String name, double lat, double lon, boolean active) {
        jdbc.update("""
                INSERT INTO stations (id, name, city, state, geom, active)
                VALUES (?, ?, 'Delhi', 'Delhi', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """, id, name, lon, lat, active);
    }

    protected void insertAqi(String stationId, Instant ts, int aqi, String category) {
        jdbc.update("""
                INSERT INTO station_aqi (station_id, ts, aqi, category, dominant_pollutant, pollutant_count)
                VALUES (?, ?, ?, ?, 'PM25', 3)
                """, stationId, Timestamp.from(ts), aqi, category);
    }

    // ---- HTTP helpers ----------------------------------------------------------------------

    protected String register(String email) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = http.postForObject("/api/v1/auth/register",
                Map.of("email", email, "password", "correct-horse-battery"), Map.class);
        return (String) body.get("accessToken");
    }

    protected <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<>(body, h), type);
    }
}
