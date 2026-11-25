package com.saanscheck.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The only class that knows the shape of the data.gov.in payload. Field names, timestamp
 * pattern, time zone and pollutant ids all come from {@link IngestionProperties}.
 *
 * <p>Expected envelope (VERIFY): {@code {"total": N, "count": n, "records": [ {...}, ... ]}}.
 */
@Component
public class DataGovPayloadMapper {

    private final IngestionProperties.Fields f;
    private final Map<String, String> aliases;
    private final DateTimeFormatter tsFormat;
    private final ZoneId zone;
    private final ReadingValidator validator;

    public DataGovPayloadMapper(IngestionProperties props, ReadingValidator validator) {
        this.f = props.fields();
        this.aliases = props.pollutantAliases() == null ? Map.of() : props.pollutantAliases();
        // STRICT: impossible dates such as 31-02-2026 are rejected instead of being "fixed" to 28-02.
        // STRICT needs 'u' (proleptic year) rather than 'y' (year-of-era), so accept either in config.
        this.tsFormat = DateTimeFormatter.ofPattern(props.timestampPattern().replace('y', 'u'), Locale.ROOT)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
        this.zone = ZoneId.of(props.sourceZone());
        this.validator = validator;
    }

    public Page parsePage(JsonNode body) {
        if (body == null || !body.has("records") || !body.get("records").isArray()) {
            throw new IllegalStateException("Unexpected payload: no 'records' array");
        }
        List<JsonNode> records = new ArrayList<>();
        body.get("records").forEach(records::add);
        int total = body.path("total").asInt(-1);
        return new Page(records, total);
    }

    public RawRecord toRaw(JsonNode rec) {
        return new RawRecord(text(rec, f.station()), text(rec, f.city()), text(rec, f.state()),
                number(rec, f.latitude()), number(rec, f.longitude()),
                text(rec, f.pollutant()), text(rec, f.value()), text(rec, f.lastUpdate()), rec);
    }

    /** Empty when the timestamp or pollutant cannot be read: such records cannot be placed in a series. */
    public Optional<ParsedReading> toReading(RawRecord raw, String stationId) {
        if (raw.pollutant() == null || raw.pollutant().isBlank() || raw.lastUpdate() == null) {
            return Optional.empty();
        }
        Instant ts;
        try {
            ts = LocalDateTime.parse(raw.lastUpdate().trim(), tsFormat).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        String pollutant = normalizePollutant(raw.pollutant());
        ReadingValidator.Result v = validator.validate(pollutant, raw.value());
        return Optional.of(new ParsedReading(stationId, pollutant, ts, v.value(), v.quality(), raw.raw().toString()));
    }

    public String normalizePollutant(String sourceId) {
        String key = sourceId.trim();
        String mapped = aliases.get(key);
        if (mapped == null) {
            mapped = aliases.get(key.toUpperCase(Locale.ROOT));
        }
        return mapped != null ? mapped : key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public ZoneId zone() {
        return zone;
    }

    private static String text(JsonNode rec, String field) {
        JsonNode n = rec.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s == null ? null : s.trim();
    }

    private static Double number(JsonNode rec, String field) {
        String s = text(rec, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Page(List<JsonNode> records, int total) {
    }
}
