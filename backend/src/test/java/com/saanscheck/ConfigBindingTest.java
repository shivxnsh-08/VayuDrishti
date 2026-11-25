package com.saanscheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.advice.AdviceProperties;
import com.saanscheck.alerts.AlertProperties;
import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.aqi.AqiCategory;
import com.saanscheck.aqi.AqiProperties;
import com.saanscheck.forecast.ForecastProperties;
import com.saanscheck.ingestion.IngestionProperties;
import com.saanscheck.profile.HealthProfile;
import com.saanscheck.security.SecurityProperties;
import com.saanscheck.telegram.TelegramProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Binds the real application.yml + aqi.yml into the property records without starting the
 * database or web layer, so configuration typos fail fast even on machines without Docker.
 */
@SpringBootTest(classes = ConfigBindingTest.Props.class)
class ConfigBindingTest {

    @Configuration
    @EnableConfigurationProperties({AqiProperties.class, IngestionProperties.class, ForecastProperties.class,
            AdviceProperties.class, TelegramProperties.class, AlertProperties.class, SecurityProperties.class})
    @Import(AqiCalculator.class)
    public static class Props {
    }

    @Autowired
    AqiProperties aqi;
    @Autowired
    IngestionProperties ingestion;
    @Autowired
    AlertProperties alerts;
    @Autowired
    ForecastProperties forecast;
    @Autowired
    TelegramProperties telegram;
    @Autowired
    AqiCalculator calculator;

    @Test
    void aqiConfigBinds() {
        assertThat(aqi.bands()).hasSize(6);
        assertThat(aqi.breakpoints()).containsKeys("PM25", "PM10", "NO2", "O3", "CO", "SO2", "NH3");
        assertThat(aqi.breakpoints().get("CO").get(1).cLow()).isEqualTo(1.1);
        assertThat(calculator.categorize(301)).isEqualTo(AqiCategory.VERY_POOR);
    }

    @Test
    void ingestionConfigBinds() {
        assertThat(ingestion.fields().value()).isEqualTo("avg_value");
        assertThat(ingestion.pollutantAliases()).containsEntry("PM2.5", "PM25").containsEntry("OZONE", "O3");
        assertThat(ingestion.pageDelay()).isEqualTo(Duration.ofMillis(300));
        assertThat(ingestion.stuckHours()).isEqualTo(6);
    }

    @Test
    void alertAndClientConfigBinds() {
        assertThat(alerts.defaultMinCategory()).containsEntry(HealthProfile.ASTHMA, AqiCategory.MODERATE);
        assertThat(alerts.cooldown()).isEqualTo(Duration.ofHours(6));
        assertThat(forecast.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(telegram.linkCodeTtl()).isEqualTo(Duration.ofMinutes(15));
    }
}
