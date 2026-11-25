package com.saanscheck.ingestion.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only ingestion health (no secrets: status, timestamps, counts, error category). Used by the
 * dashboard; error messages and run details stay on the ADMIN endpoint /api/v1/admin/ingestion-runs.
 */
@RestController
public class IngestionHealthController {

    private final IngestionHealthService health;

    public IngestionHealthController(IngestionHealthService health) {
        this.health = health;
    }

    @GetMapping("/api/v1/ingestion/health")
    public IngestionHealth health() {
        return health.current();
    }
}
