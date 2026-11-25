package com.saanscheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * VayuDrishti backend (class name kept for compatibility): ingestion, geo lookup, profiles, alert engine and public API.
 *
 * <p>Forecasting and advice phrasing live in the separate Python service; this
 * application talks to it over two HTTP endpoints only (spec risk table:
 * "keep the boundary at two internal endpoints").
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SaansCheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaansCheckApplication.class, args);
    }
}
