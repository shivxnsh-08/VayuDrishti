package com.saanscheck.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.saanscheck.aqi.AqiCalculator;
import com.saanscheck.stations.CachedStationReads;
import com.saanscheck.stations.StationAqi;
import com.saanscheck.stations.StationAqiRepository;
import com.saanscheck.stations.StationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One ingestion run: fetch all pages, validate, dedupe, store readings, derive hourly AQI,
 * and record the run with its data-quality counters.
 *
 * <p>Reliability:
 * <ul>
 *   <li>Runs never overlap, within or across backend instances (PostgreSQL advisory lock); a run that
 *       finds the lock taken is skipped, not queued.</li>
 *   <li>Under the lock, RUNNING rows left by a dead process are marked INTERRUPTED first.</li>
 *   <li>Every outcome is recorded (SUCCESS / PARTIAL / FAILED / SKIPPED) with an error category, retry
 *       count, HTTP status and the newest source observation time. Any exception, including database
 *       errors, is caught and recorded; if even recording is impossible it is logged at ERROR.</li>
 *   <li>Idempotent: readings are keyed by (station, pollutant, source timestamp), so retries and
 *       repeated snapshots never duplicate observations, and a run interrupted midway is completed by
 *       the next one (AQI for a station-hour is recomputed from its stored readings' snapshot).</li>
 * </ul>
 * Page 1 failing (after retries) fails the run; a later page failing marks it PARTIAL but stores what
 * was fetched. Per-record problems never abort the run; they are counted with a rejection reason.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionProperties props;
    private final DataGovClient client;
    private final DataGovPayloadMapper mapper;
    private final StationRepository stations;
    private final ReadingRepository readings;
    private final StationAqiRepository stationAqi;
    private final IngestionRunRepository runs;
    private final AqiCalculator calculator;
    private final CachedStationReads cache;
    private final IngestionLock lock;
    private final MeterRegistry metrics;
    private final Clock clock;

    public IngestionService(IngestionProperties props, DataGovClient client, DataGovPayloadMapper mapper,
                            StationRepository stations, ReadingRepository readings, StationAqiRepository stationAqi,
                            IngestionRunRepository runs, AqiCalculator calculator, CachedStationReads cache,
                            IngestionLock lock, MeterRegistry metrics, Clock clock) {
        this.props = props;
        this.client = client;
        this.mapper = mapper;
        this.stations = stations;
        this.readings = readings;
        this.stationAqi = stationAqi;
        this.runs = runs;
        this.calculator = calculator;
        this.cache = cache;
        this.lock = lock;
        this.metrics = metrics;
        this.clock = clock;
        if (metrics != null) {
            // Register run counters at 0 so dashboards show "0 failures" instead of "no data" after a restart.
            for (String st : List.of("SUCCESS", "PARTIAL", "FAILED", "SKIPPED")) {
                metrics.counter("saanscheck.ingestion.runs", "status", st);
            }
            metrics.counter("saanscheck.ingestion.new_observations");
            metrics.counter("saanscheck.ingestion.retries");
        }
    }

    /** Manual run (admin endpoint, tests). Throws if another run is in progress. */
    public IngestionRun runOnce() {
        return runOnce(IngestionTrigger.MANUAL)
                .orElseThrow(() -> new IllegalStateException("another ingestion run is in progress"));
    }

    /** Empty if another run (in this or another backend instance) holds the ingestion lock. */
    public synchronized Optional<IngestionRun> runOnce(IngestionTrigger trigger) {
        Optional<IngestionRun> result = lock.withLock(() -> runLocked(trigger));
        if (result.isEmpty()) {
            log.info("Ingestion ({}) skipped: another run is in progress", trigger);
            metrics.counter("saanscheck.ingestion.skipped_locked").increment();
        }
        return result;
    }

    private IngestionRun runLocked(IngestionTrigger trigger) {
        Instant started = clock.instant();
        int interrupted = runs.markInterrupted(started);
        if (interrupted > 0) {
            log.warn("Marked {} orphaned ingestion run(s) INTERRUPTED (process stopped mid-run)", interrupted);
            metrics.counter("saanscheck.ingestion.interrupted").increment(interrupted);
        }
        long runId = runs.start(started, trigger.name());
        IngestionStats stats = new IngestionStats();
        String status;
        String error = null;

        if (!props.hasApiKey()) {
            status = "FAILED";
            stats.errorCategory = ErrorCategory.CONFIG;
            error = "DATA_GOV_API_KEY is not configured";
            log.error("Ingestion run {} cannot start: {}", runId, error);
        } else {
            try {
                FetchResult fetch = fetchAll(stats);
                stats.fetched = fetch.records().size();
                stats.expected = fetch.total();
                List<JsonNode> unique = dedupeWithinRun(fetch.records(), stats);
                process(unique, stats);
                error = fetch.partialError();
                if (error == null && fetch.total() >= 0 && unique.size() < fetch.total()) {
                    error = "incomplete snapshot: received " + unique.size() + " unique records of "
                            + fetch.total() + " reported by the API";
                }
                status = error == null ? "SUCCESS" : "PARTIAL";
            } catch (Exception e) {   // network, source, parse, database: all recorded, never swallowed
                status = "FAILED";
                stats.errorCategory = ErrorClassifier.classify(e);
                if (stats.httpStatus == null) {
                    stats.httpStatus = ErrorClassifier.httpStatus(e);
                }
                if (e instanceof SourceFetchException s) {
                    stats.retries += s.retries();
                }
                error = DataGovClient.redact(e.getMessage());
                log.error("Ingestion run {} ({}) FAILED [{}]: {}", runId, trigger, stats.errorCategory, error);
            }
        }

        Instant finished = clock.instant();
        runs.finish(runId, stats, status, error, started, finished);
        recordMetrics(status, stats, started);
        cache.evictLatest();
        log.info("Ingestion run {} ({}) {} in {} ms: expected={} fetched={} newObservations={} alreadyStored={} "
                        + "payloadDuplicates={} rejected={} missingValues={} stuck={} stationHours={} retries={} "
                        + "latestSourceObservation={}{}",
                runId, trigger, status, Duration.between(started, finished).toMillis(), stats.expected, stats.fetched,
                stats.inserted, stats.duplicates, stats.payloadDuplicates, stats.rejected, stats.missing, stats.stuck,
                stats.stationHoursComputed, stats.retries, stats.latestSourceTs,
                stats.errorCategory != null ? " errorCategory=" + stats.errorCategory : "");
        return new IngestionRun(runId, started, finished, stats.fetched, stats.inserted, stats.rejected,
                stats.missing, stats.stuck, stats.duplicates, stats.payloadDuplicates,
                stats.expected < 0 ? null : stats.expected, status, error, trigger.name(),
                Duration.between(started, finished).toMillis(), stats.retries,
                stats.errorCategory == null ? null : stats.errorCategory.name(), stats.httpStatus,
                stats.latestSourceTs, Map.copyOf(stats.rejectionReasons));
    }

    private FetchResult fetchAll(IngestionStats stats) {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        int total = -1;
        for (int page = 0; page < props.maxPages(); page++) {
            DataGovPayloadMapper.Page p;
            try {
                DataGovClient.Fetched fetched = client.fetchPage(offset);
                stats.retries += fetched.retries();
                stats.httpStatus = fetched.httpStatus();
                p = mapper.parsePage(fetched.body());
            } catch (RuntimeException e) {
                if (page == 0) {
                    throw e;
                }
                if (e instanceof SourceFetchException s) {
                    stats.retries += s.retries();
                    stats.httpStatus = s.httpStatus();
                }
                stats.errorCategory = ErrorClassifier.classify(e);
                String msg = "page " + page + " failed: " + DataGovClient.redact(e.getMessage());
                log.warn("Ingestion partial, {}", msg);
                return new FetchResult(all, total, msg);
            }
            all.addAll(p.records());
            if (p.total() >= 0) {
                total = p.total();
            }
            offset += p.records().size();
            boolean lastPage = p.records().size() < props.pageSize() || (p.total() >= 0 && offset >= p.total());
            if (lastPage) {
                break;
            }
            pause();
        }
        return new FetchResult(all, total, null);
    }

    /**
     * Drops records received more than once in the same run (identical station, pollutant and
     * source timestamp). With sorted pagination this should be zero; a non-zero count means the
     * pages overlapped, which also implies other records were skipped - see the completeness check.
     */
    List<JsonNode> dedupeWithinRun(List<JsonNode> records, IngestionStats stats) {
        Map<String, JsonNode> unique = new java.util.LinkedHashMap<>();
        for (JsonNode rec : records) {
            RawRecord r = mapper.toRaw(rec);
            String key = r.stationName() + "|" + r.pollutant() + "|" + r.lastUpdate();
            if (unique.putIfAbsent(key, rec) != null) {
                stats.payloadDuplicates++;
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** Source timestamps further ahead than this (clock skew, wrong time zone, bad data) are rejected. */
    static final Duration FUTURE_TOLERANCE = Duration.ofHours(1);

    void process(List<JsonNode> records, IngestionStats stats) {
        Instant now = clock.instant();
        ZoneId zone = mapper.zone();
        Set<String> upserted = new HashSet<>();
        Map<String, Boolean> knownStations = new HashMap<>();
        // station -> hour -> pollutant -> value (OK readings only)
        Map<String, Map<Instant, Map<String, Double>>> okValues = new HashMap<>();

        for (JsonNode rec : records) {
            RawRecord raw = mapper.toRaw(rec);
            if (raw.stationName() == null || raw.stationName().isBlank()) {
                stats.reject("MISSING_STATION_NAME");
                continue;
            }
            String stationId = StationIds.slug(raw.stationName());
            if (!ensureStation(raw, stationId, now, upserted, knownStations)) {
                stats.reject("UNKNOWN_STATION_NO_COORDINATES");   // cannot be located on the map
                continue;
            }
            Optional<ParsedReading> parsed = mapper.toReading(raw, stationId);
            if (parsed.isEmpty()) {
                stats.reject("UNPARSEABLE_TIMESTAMP_OR_POLLUTANT");
                continue;
            }
            ParsedReading r = parsed.get();
            if (r.ts().isAfter(now.plus(FUTURE_TOLERANCE))) {
                // A future-dated record would make the data look fresh until that time: never store it.
                stats.reject("FUTURE_TIMESTAMP");
                continue;
            }
            stats.observe(r.ts());
            if (r.quality() == ReadingQuality.OK && isStuck(r)) {
                r = r.withQuality(ReadingQuality.STUCK);
            }
            if (readings.insertIfAbsent(r)) {
                stats.inserted++;
                switch (r.quality()) {
                    case MISSING -> stats.missing++;
                    case INVALID -> stats.reject("INVALID_VALUE");
                    case STUCK -> stats.stuck++;
                    case OK -> { }
                }
            } else {
                stats.duplicates++;
            }
            if (r.quality() == ReadingQuality.OK) {
                Instant hour = r.ts().atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant();
                okValues.computeIfAbsent(stationId, k -> new TreeMap<>())
                        .computeIfAbsent(hour, k -> new HashMap<>())
                        .put(r.pollutant(), r.value());
            }
        }

        // Derived AQI per station-hour. Recomputing for a duplicate snapshot is idempotent (upsert).
        okValues.forEach((stationId, byHour) -> byHour.forEach((hour, values) ->
                calculator.overall(values).ifPresent(res -> {
                    stationAqi.upsert(new StationAqi(stationId, hour, res.aqi(), res.category().name(),
                            res.dominantPollutant(), res.pollutantCount()));
                    stats.stationHoursComputed++;
                })));
    }

    /**
     * Stuck sensor = the same value for {@code stuck-hours} CONSECUTIVE hourly readings. Only readings
     * inside the contiguous window before this one count, so an outage gap can never make a sensor look
     * frozen. Values at or below {@code stuck-min-value} are exempt: the feed reports sub-indices of 24-hour
     * averages, and small values (e.g. NH3 = 5) legitimately stay constant for many hours.
     */
    private boolean isStuck(ParsedReading r) {
        if (r.value() == null || r.value() <= props.stuckMinValue()) {
            return false;
        }
        int hours = props.stuckHours();
        Instant since = r.ts().minus(Duration.ofHours(hours - 1)).minus(Duration.ofMinutes(5));
        List<Double> prev = readings.recentValuesSince(r.stationId(), r.pollutant(), since, r.ts(), hours);
        return ReadingValidator.isStuck(prev, r.value(), hours);
    }

    private boolean ensureStation(RawRecord raw, String stationId, Instant now,
                                  Set<String> upserted, Map<String, Boolean> known) {
        if (upserted.contains(stationId)) {
            return true;
        }
        if (raw.hasCoordinates()) {
            stations.upsert(stationId, raw.stationName(), raw.city(), raw.state(),
                    raw.latitude(), raw.longitude(), now);
            upserted.add(stationId);
            return true;
        }
        return known.computeIfAbsent(stationId, stations::exists);
    }

    private void recordMetrics(String status, IngestionStats stats, Instant started) {
        metrics.counter("saanscheck.ingestion.runs", "status", status).increment();
        io.micrometer.core.instrument.Timer.builder("saanscheck.ingestion.duration")
                .description("Wall-clock duration of an ingestion run (fetch + validate + store)")
                .tag("status", status).register(metrics).record(Duration.between(started, clock.instant()));
        metrics.counter("saanscheck.ingestion.rejected").increment(stats.rejected);
        metrics.counter("saanscheck.ingestion.stuck").increment(stats.stuck);
        metrics.counter("saanscheck.ingestion.missing").increment(stats.missing);
        metrics.counter("saanscheck.ingestion.payload_duplicates").increment(stats.payloadDuplicates);
        metrics.counter("saanscheck.ingestion.new_observations").increment(stats.inserted);
        metrics.counter("saanscheck.ingestion.retries").increment(stats.retries);
        if (stats.errorCategory != null) {
            metrics.counter("saanscheck.ingestion.errors", "category", stats.errorCategory.name()).increment();
        }
        if ("SUCCESS".equals(status) || "PARTIAL".equals(status)) {
            metrics.counter("saanscheck.ingestion.gaps").increment(stations.countActiveNotSeenSince(started));
        }
    }

    private void pause() {
        try {
            Thread.sleep(props.pageDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record FetchResult(List<JsonNode> records, int total, String partialError) {
    }
}
