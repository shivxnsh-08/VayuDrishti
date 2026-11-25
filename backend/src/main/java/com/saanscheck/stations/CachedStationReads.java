package com.saanscheck.stations;

import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * The two hot reads, cached in Redis. Kept in a separate bean so the {@code @Cacheable}
 * proxy is actually used (self-invocation inside one bean would bypass it).
 *
 * <p>Nearest-station keys use coordinates rounded to 2 decimals (~1 km): profile locations
 * are already coarse, and rounding gives a usable hit rate. Results inside one ~1 km cell
 * share a cache entry, which is fine at station spacing of several km.
 */
@Component
public class CachedStationReads {

    public static final String NEAREST = "nearest";
    public static final String LATEST_AQI = "latestAqi";

    private final StationRepository stations;
    private final StationAqiRepository aqi;

    public CachedStationReads(StationRepository stations, StationAqiRepository aqi) {
        this.stations = stations;
        this.aqi = aqi;
    }

    @Cacheable(cacheNames = NEAREST, key = "T(java.lang.String).format('%.2f:%.2f:%d', #lat, #lon, #limit)")
    public List<NearestStation> nearest(double lat, double lon, int limit) {
        return List.copyOf(stations.findNearest(round2(lat), round2(lon), limit));
    }

    /** Cached as Optional-unwrapped value; a missing row is not cached (unless = null result). */
    @Cacheable(cacheNames = LATEST_AQI, key = "#stationId", unless = "#result == null")
    public StationAqi latest(String stationId) {
        return aqi.findLatest(stationId).orElse(null);
    }

    /** Called after each ingestion run so the API never serves an hour-old "current" value from cache. */
    @CacheEvict(cacheNames = LATEST_AQI, allEntries = true)
    public void evictLatest() {
        // annotation does the work
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
