package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.stations.CachedStationReads;
import com.saanscheck.stations.CurrentAqiView;
import com.saanscheck.stations.NearestStation;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Cache behaviour against a REAL Redis (the other ITs run with caching disabled). */
@TestPropertySource(properties = "saanscheck.cache.enabled=true")
class RedisCacheIT extends AbstractIT {

    // Singleton container shared by every test in this class: started once below and deliberately not
    // closed here. Testcontainers' Ryuk sidecar removes it when the test JVM exits.
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    RedisConnectionFactory redis;
    @Autowired
    CachedStationReads cache;

    @BeforeEach
    void flush() {
        try (var c = redis.getConnection()) {
            c.serverCommands().flushAll();
        }
    }

    @Test
    void nearestStationResultsAreServedFromRedis() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        NearestStation[] first = http.getForObject("/api/v1/stations/nearest?lat=28.63&lon=77.24&limit=1", NearestStation[].class);
        assertThat(first).extracting(NearestStation::id).containsExactly("ito");

        // Change the database underneath: a cache hit must still return the stored result.
        jdbc.update("UPDATE stations SET active = FALSE WHERE id = 'ito'");
        NearestStation[] second = http.getForObject("/api/v1/stations/nearest?lat=28.63&lon=77.24&limit=1", NearestStation[].class);
        assertThat(second).extracting(NearestStation::id).containsExactly("ito");

        try (var c = redis.getConnection()) {
            assertThat(c.keyCommands().keys("saanscheck:nearest*".getBytes())).isNotEmpty();
        }
    }

    @Test
    void latestAqiCacheIsEvictedAndStaleFlagIsComputedAfterTheCacheRead() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        insertAqi("ito", T0, 180, "MODERATE");
        assertThat(http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class).aqi()).isEqualTo(180);

        insertAqi("ito", T0.plus(Duration.ofHours(1)), 220, "POOR");
        // still cached
        assertThat(http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class).aqi()).isEqualTo(180);

        cache.evictLatest();   // what every ingestion run does
        clock.advance(Duration.ofHours(1));
        CurrentAqiView fresh = http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class);
        assertThat(fresh.aqi()).isEqualTo(220);

        clock.advance(Duration.ofHours(4));   // cached row, but the stale flag must still flip
        assertThat(http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class).stale()).isTrue();
    }
}
