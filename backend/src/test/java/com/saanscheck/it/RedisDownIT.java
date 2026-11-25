package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.stations.CurrentAqiView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** Cache enabled but Redis unreachable: endpoints must still answer (spec section 10). */
@TestPropertySource(properties = {"saanscheck.cache.enabled=true", "spring.data.redis.port=1"})
class RedisDownIT extends AbstractIT {

    @Test
    void endpointsWorkWithoutRedis() {
        insertStation("ito", "ITO", 28.6286, 77.2410, true);
        insertAqi("ito", T0, 180, "MODERATE");

        assertThat(http.getForEntity("/api/v1/stations/nearest?lat=28.63&lon=77.24", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        CurrentAqiView current = http.getForObject("/api/v1/stations/ito/aqi/current", CurrentAqiView.class);
        assertThat(current.aqi()).isEqualTo(180);
    }
}
