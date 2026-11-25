package com.saanscheck.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MetricsWebConfig implements WebMvcConfigurer {

    private final MeterRegistry registry;
    private final boolean cacheEnabled;

    public MetricsWebConfig(MeterRegistry registry, @Value("${saanscheck.cache.enabled:true}") boolean cacheEnabled) {
        this.registry = registry;
        this.cacheEnabled = cacheEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiLatencyInterceptor(this.registry, cacheEnabled)).addPathPatterns("/api/**");
    }
}
