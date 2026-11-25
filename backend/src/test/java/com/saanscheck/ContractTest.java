package com.saanscheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Contract test between the Java client and the FastAPI schemas. The same example files in
 * /contracts are validated by the Python test suite. FAIL_ON_UNKNOWN_PROPERTIES is on here so a
 * field added on the Python side without a Java counterpart is caught.
 */
class ContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @ParameterizedTest
    @CsvSource({
            "forecast_request.example.json, com.saanscheck.forecast.ForecastDtos$ForecastRequest",
            "forecast_response.example.json, com.saanscheck.forecast.ForecastDtos$ForecastResponse",
            "advice_request.example.json, com.saanscheck.advice.AdviceDtos$AdviceRequest",
            "advice_response.example.json, com.saanscheck.advice.AdviceDtos$AdviceResponse"})
    void javaDtosRoundTripTheSharedExamples(String file, String className) throws Exception {
        JsonNode example = MAPPER.readTree(Files.readString(Path.of("..", "contracts", file)));
        Object dto = MAPPER.treeToValue(example, Class.forName(className));
        JsonNode roundTrip = MAPPER.valueToTree(dto);
        assertThat(fieldPaths(roundTrip, "")).isEqualTo(fieldPaths(example, ""));
    }

    private static Set<String> fieldPaths(JsonNode node, String prefix) {
        Set<String> out = new TreeSet<>();
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                out.add(prefix + n);
                out.addAll(fieldPaths(node.get(n), prefix + n + "."));
            }
        } else if (node.isArray() && !node.isEmpty()) {
            out.addAll(fieldPaths(node.get(0), prefix + "[]."));
        }
        return out;
    }
}
