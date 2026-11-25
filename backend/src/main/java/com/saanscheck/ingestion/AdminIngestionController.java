package com.saanscheck.ingestion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (enforced in SecurityConfig): ingestion health and data-quality summary. */
@RestController
@RequestMapping("/api/v1/admin/ingestion-runs")
@Validated
public class AdminIngestionController {

    private final IngestionRunRepository runs;
    private final DataQualityService quality;
    private final IngestionService ingestion;

    public AdminIngestionController(IngestionRunRepository runs, DataQualityService quality,
                                    IngestionService ingestion) {
        this.runs = runs;
        this.quality = quality;
        this.ingestion = ingestion;
    }

    @GetMapping
    public Response list(@RequestParam(defaultValue = "24") @Min(1) @Max(500) int limit,
                         @RequestParam(defaultValue = "24") @Min(1) @Max(24 * 30) int windowHours) {
        return new Response(runs.recent(limit), quality.summary(Duration.ofHours(windowHours)));
    }

    /** Manual trigger, handy for demos and for checking a new API key. */
    @PostMapping
    public IngestionRun trigger() {
        return ingestion.runOnce(IngestionTrigger.MANUAL)
                .orElseThrow(() -> new com.saanscheck.common.ConflictException("an ingestion run is already in progress"));
    }

    public record Response(List<IngestionRun> runs, DataQualityService.Summary dataQuality) {
    }
}
