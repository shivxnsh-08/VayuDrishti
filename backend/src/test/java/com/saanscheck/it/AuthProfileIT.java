package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.profile.ProfileView;
import com.saanscheck.telegram.TelegramLinkService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthProfileIT extends AbstractIT {

    @Autowired
    TelegramLinkService links;

    @Test
    void registerLoginAndDuplicateHandling() {
        assertThat(register("a@test.local")).isNotBlank();

        ResponseEntity<String> dup = http.postForEntity("/api/v1/auth/register",
                Map.of("email", "A@test.local", "password", "correct-horse-battery"), String.class);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> wrong = http.postForEntity("/api/v1/auth/login",
                Map.of("email", "a@test.local", "password", "wrong-password"), String.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> weak = http.postForEntity("/api/v1/auth/register",
                Map.of("email", "b@test.local", "password", "short"), String.class);
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void profileRequiresAuthStoresCoarseLocationAndPicksNearestStation() {
        insertStation("india-gate", "India Gate", 28.6129, 77.2295, true);
        insertStation("gurugram", "Gurugram", 28.4595, 77.0266, true);
        String token = register("p@test.local");

        assertThat(exchange(HttpMethod.GET, "/api/v1/me/profile", null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> body = Map.of("healthProfile", "ASTHMA", "latitude", 28.613912, "longitude", 77.229512,
                "language", "hi", "quietStart", "22:00", "quietEnd", "07:00");
        ResponseEntity<ProfileView> put = exchange(HttpMethod.PUT, "/api/v1/me/profile", token, body, ProfileView.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProfileView view = put.getBody();
        assertThat(view.latitude()).isEqualTo(28.61);
        assertThat(view.longitude()).isEqualTo(77.23);
        assertThat(view.stationId()).isEqualTo("india-gate");
        assertThat(view.effectiveMinCategory().name()).isEqualTo("MODERATE");
        assertThat(view.telegramLinked()).isFalse();
    }

    @Test
    void telegramCodeLinksChatOnce() {
        String token = register("t@test.local");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getBody();
        String code = (String) link.get("code");

        assertThat(links.consume(code, "999")).isPresent();
        assertThat(links.consume(code, "999")).isEmpty();   // one-time
        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM profiles", String.class)).isEqualTo("999");
    }

    @Test
    void adminEndpointsNeedAdminRole() {
        String user = register("user@test.local");
        String admin = register("admin@test.local");

        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", user, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, "/api/v1/admin/ingestion-runs", admin, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void deletingAccountRemovesPersonalData() {
        String token = register("d@test.local");
        exchange(HttpMethod.PUT, "/api/v1/me/profile", token, Map.of("healthProfile", "GENERAL"), String.class);

        assertThat(exchange(HttpMethod.DELETE, "/api/v1/me", token, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles", Integer.class)).isZero();
    }
}
