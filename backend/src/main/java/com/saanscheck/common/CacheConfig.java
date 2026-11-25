package com.saanscheck.common;

import com.saanscheck.stations.CachedStationReads;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis cache with per-cache TTLs and an error handler that turns every Redis failure
 * into a cache miss. Result: if Redis is down, endpoints still work, just slower
 * (spec section 10). {@code saanscheck.cache.enabled=false} swaps in a no-op cache for the
 * with/without-cache latency experiment (X7).
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     @Value("${saanscheck.cache.enabled:true}") boolean enabled,
                                     @Value("${saanscheck.cache.nearest-ttl:PT1H}") Duration nearestTtl,
                                     @Value("${saanscheck.cache.latest-aqi-ttl:PT5M}") Duration latestTtl) {
        if (!enabled) {
            log.info("Caching disabled (saanscheck.cache.enabled=false)");
            return new NoOpCacheManager();
        }
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("saanscheck:")
                .disableCachingNullValues();
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(Map.of(
                        CachedStationReads.NEAREST, base.entryTtl(nearestTtl),
                        CachedStationReads.LATEST_AQI, base.entryTtl(latestTtl)))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                warn("get", cache, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                warn("put", cache, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                warn("evict", cache, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                warn("clear", cache, e);
            }
        };
    }

    private static void warn(String op, Cache cache, RuntimeException e) {
        log.warn("Cache {} failed on '{}', bypassing cache: {}", op, cache.getName(), e.getMessage());
    }
}
