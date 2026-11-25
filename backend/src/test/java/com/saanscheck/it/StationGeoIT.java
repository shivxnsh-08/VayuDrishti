package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.stations.NearestStation;
import com.saanscheck.stations.StationRepository;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;

/** Nearest-station ordering with known coordinates, on real PostGIS. */
class StationGeoIT extends AbstractIT {

    // Connaught Place, New Delhi
    private static final double CP_LAT = 28.6315;
    private static final double CP_LON = 77.2167;

    @Autowired
    StationRepository stations;

    @BeforeEach
    void seed() {
        insertStation("india-gate", "India Gate", 28.6129, 77.2295, true);          // ~2.4 km
        insertStation("anand-vihar", "Anand Vihar", 28.6468, 77.3160, true);        // ~9.8 km
        insertStation("noida-62", "Noida Sector 62", 28.6270, 77.3730, true);       // ~15 km
        insertStation("gurugram", "Gurugram", 28.4595, 77.0266, true);              // ~26 km
        insertStation("closed-cp", "Closed station at CP", 28.6316, 77.2168, false); // nearest but inactive
    }

    @Test
    void returnsActiveStationsOrderedByDistance() {
        List<NearestStation> result = stations.findNearest(CP_LAT, CP_LON, 3);

        assertThat(result).extracting(NearestStation::id).containsExactly("india-gate", "anand-vihar", "noida-62");
        assertThat(result.get(0).distanceMeters()).isBetween(1_500.0, 3_500.0);
        assertThat(result).extracting(NearestStation::distanceMeters).isSorted();
    }

    @Test
    void publicEndpointValidatesAndReturnsNearest() {
        ResponseEntity<NearestStation[]> ok = http.getForEntity(
                "/api/v1/stations/nearest?lat=" + CP_LAT + "&lon=" + CP_LON + "&limit=2", NearestStation[].class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).extracting(NearestStation::id).containsExactly("india-gate", "anand-vihar");

        ResponseEntity<String> bad = http.getForEntity("/api/v1/stations/nearest?lat=123&lon=77", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Interview question: "what index does the query use?" - this proves it is the GiST index. */
    @Test
    void knnQueryCanUseGistIndex() {
        List<String> plan = jdbc.execute((ConnectionCallback<List<String>>) con -> {
            List<String> lines = new ArrayList<>();
            try (Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");   // tiny table: force the planner to show the index path
                try (ResultSet rs = st.executeQuery("""
                        EXPLAIN SELECT id FROM stations
                        ORDER BY geom <-> ST_SetSRID(ST_MakePoint(77.2167, 28.6315), 4326)::geography LIMIT 3
                        """)) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                st.execute("RESET enable_seqscan");
            }
            return lines;
        });
        assertThat(String.join("\n", plan)).contains("ix_stations_geom");
    }
}
