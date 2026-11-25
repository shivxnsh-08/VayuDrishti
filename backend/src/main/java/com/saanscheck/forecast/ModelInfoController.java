package com.saanscheck.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only view of the forecast service's /model-info (model version, training window,
 * validation scores, stations served by a baseline). The forecast service itself stays internal.
 */
@RestController
public class ModelInfoController {

    private static final Logger log = LoggerFactory.getLogger(ModelInfoController.class);

    private final ForecastClient client;

    public ModelInfoController(ForecastClient client) {
        this.client = client;
    }

    @GetMapping("/api/v1/model-info")
    public Map<String, Object> modelInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode info = client.modelInfo();
            out.put("available", true);
            out.put("info", info);
        } catch (RuntimeException e) {
            log.warn("Model info unavailable: {}", e.toString());
            out.put("available", false);
            out.put("message", "Forecast service unavailable");
        }
        return out;
    }
}
