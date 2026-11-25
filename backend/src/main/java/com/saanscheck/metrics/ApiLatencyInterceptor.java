package com.saanscheck.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records {@code saanscheck.api.latency} tagged by endpoint pattern (not raw URL, to keep tag
 * cardinality bounded) and by cache mode, for the with/without-Redis comparison (X7).
 */
public class ApiLatencyInterceptor implements HandlerInterceptor {

    private static final String START = ApiLatencyInterceptor.class.getName() + ".start";

    private final MeterRegistry registry;
    private final String cacheTag;

    public ApiLatencyInterceptor(MeterRegistry registry, boolean cacheEnabled) {
        this.registry = registry;
        this.cacheTag = cacheEnabled ? "on" : "off";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(START);
        if (!(start instanceof Long s)) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Timer.builder("saanscheck.api.latency")
                .tag("endpoint", pattern == null ? "unknown" : pattern.toString())
                .tag("cache", cacheTag)
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry)
                .record(System.nanoTime() - s, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
