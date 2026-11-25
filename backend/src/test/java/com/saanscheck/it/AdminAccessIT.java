package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saanscheck.security.AdminAccountProvisioner;
import com.saanscheck.security.AdminAccountProvisioner.Outcome;
import com.saanscheck.security.AdminProperties;
import com.saanscheck.security.UserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated admin account: provisioning, same login as everyone, backend role enforcement, no secret leakage. */
@TestPropertySource(properties = {"saanscheck.admin.email=Root-Admin@Test.Local",
        "saanscheck.admin.password=Str0ng#Vault-Key9"})
class AdminAccessIT extends AbstractIT {

    static final String ADMIN = "root-admin@test.local";
    static final String PASSWORD = "Str0ng#Vault-Key9";

    @Autowired
    AdminAccountProvisioner provisioner;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    com.saanscheck.telegram.TelegramLinkService telegramLinks;
    @Autowired
    com.saanscheck.alerts.AlertEvaluator evaluator;
    @Autowired
    com.saanscheck.briefing.MorningBriefingService briefings;
    @Autowired
    com.saanscheck.profile.ProfileRepository profiles;

    private AdminAccountProvisioner with(String email, String password, boolean required, boolean reset) {
        return new AdminAccountProvisioner(new AdminProperties(email, password, required, reset), users, encoder, tx,
                telegramLinks);
    }

    private ResponseEntity<Map<String, Object>> login(String email, String password) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api/v1/auth/login", HttpMethod.POST, new HttpEntity<>(Map.of("email", email, "password", password), h),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    private String adminToken() {
        provisioner.provision();
        return (String) login(ADMIN, PASSWORD).getBody().get("accessToken");
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null, String.class);
    }

    @Test
    void provisioningCreatesOneBcryptAdminAndRestartsAreIdempotent() {
        assertThat(provisioner.provision()).isEqualTo(Outcome.CREATED);
        assertThat(provisioner.provision()).isEqualTo(Outcome.ALREADY_ADMIN);      // restart
        assertThat(provisioner.provision()).isEqualTo(Outcome.ALREADY_ADMIN);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE email = ?", ADMIN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, ADMIN)).isEqualTo(1);
        assertThat(row).containsEntry("role", "ADMIN");
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void adminSignsInThroughTheNormalLoginAndIsAdminOnTheServer() {
        String token = adminToken();
        ResponseEntity<Map<String, Object>> res = login(ADMIN, PASSWORD);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("role", "ADMIN");
        assertThat(res.getBody().toString()).doesNotContain(PASSWORD).doesNotContain("$2a$");
        assertThat(get("/api/v1/me/account", token).getBody()).contains("\"role\":\"ADMIN\"").contains(ADMIN)
                .doesNotContain("password");
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE email = ?", Boolean.class, ADMIN))
                .isTrue();
    }

    @Test
    void restartWithAChangedPasswordDoesNotOverwriteItUnlessRotationIsRequested() {
        provisioner.provision();
        assertThat(with(ADMIN, "Diff3rent#Secret77", false, false).provision()).isEqualTo(Outcome.ALREADY_ADMIN);
        assertThat(login(ADMIN, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(with(ADMIN, "Diff3rent#Secret77", false, true).provision()).isEqualTo(Outcome.PASSWORD_ROTATED);
        assertThat(login(ADMIN, "Diff3rent#Secret77").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(ADMIN, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anotherUsersAccountIsNeverTakenOverButAnOwnerWithTheSamePasswordIsPromoted() {
        register("taken@test.local");                                         // password: correct-horse-battery
        assertThat(with("taken@test.local", "Str0ng#Vault-Key9", false, false).provision())
                .isEqualTo(Outcome.REFUSED_EXISTING_USER);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = 'taken@test.local'", String.class)).isEqualTo("USER");

        jdbc.update("UPDATE users SET password_hash = ? WHERE email = 'taken@test.local'", encoder.encode("Own3r#Proves-It"));
        assertThat(with("taken@test.local", "Own3r#Proves-It", false, false).provision()).isEqualTo(Outcome.PROMOTED_OWNER);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = 'taken@test.local'", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void missingOrWeakConfigurationIsRejectedAndFailsStartupWhenRequired() {
        assertThat(with("weak@test.local", "short", false, false).provision()).isEqualTo(Outcome.REJECTED_WEAK_PASSWORD);
        assertThat(with("weak@test.local", "alllowercaseletters", false, false).provision()).isEqualTo(Outcome.REJECTED_WEAK_PASSWORD);
        assertThat(with("weak@test.local", "MyAdmin#Password1", false, false).provision()).isEqualTo(Outcome.REJECTED_WEAK_PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'weak@test.local'", Integer.class)).isZero();
        assertThat(with("", "", false, false).provision()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThatThrownBy(() -> with("", "", true, false).provision()).hasMessageContaining("ADMIN_EMAIL");
        assertThatThrownBy(() -> with("weak@test.local", "short", true, false).provision()).hasMessageContaining("12 characters");
    }

    @Test
    void adminApisRequireTheAdminRoleOnTheBackend() {
        String admin = adminToken();
        String user = register("plain@test.local");
        for (String path : List.of("/api/v1/admin/users", "/api/v1/admin/users/stats", "/api/v1/admin/users/1")) {
            assertThat(get(path, null).getStatusCode()).as("anonymous " + path).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(path, user).getStatusCode()).as("user " + path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(get("/api/v1/admin/users", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/admin/users/stats", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        // a forged token (role claim edited, signature invalid) is rejected
        String[] parts = user.split("\\.");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("USER", "ADMIN").getBytes())
                + "." + parts[2];
        assertThat(get("/api/v1/admin/users", forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usersCannotPromoteThemselves() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> reg = http.postForObject("/api/v1/auth/register",
                new HttpEntity<>(Map.of("email", "sneaky@test.local", "password", "correct-horse-battery", "role", "ADMIN"), h), Map.class);
        assertThat(reg.get("role")).isEqualTo("USER");
        String token = (String) reg.get("accessToken");
        exchange(HttpMethod.PUT, "/api/v1/me/profile", token, Map.of("healthProfile", "GENERAL", "role", "ADMIN"), String.class);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = 'sneaky@test.local'", String.class)).isEqualTo("USER");
        assertThat(exchange(HttpMethod.PUT, "/api/v1/admin/users/1", token, Map.of("role", "ADMIN"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/me/account", token).getBody()).contains("\"role\":\"USER\"");
    }

    @Test
    void userManagementListsSafeFieldsWithSearchFiltersSortAndPagingAndNoSecrets() {
        String admin = adminToken();
        for (int i = 1; i <= 7; i++) {
            register("person" + i + "@test.local");
        }
        login("person1@test.local", "correct-horse-battery");                  // records a sign-in
        long p2 = jdbc.queryForObject("SELECT id FROM users WHERE email = 'person2@test.local'", Long.class);
        insertStation("ito", "ITO, Delhi - CPCB", 28.62, 77.24, true);
        jdbc.update("INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id) "
                + "VALUES (?, 'ASTHMA', 'ito', 'en', '999888777')", p2);
        jdbc.update("UPDATE users SET last_login_at = ? WHERE email = 'person3@test.local'",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(45))));

        String all = get("/api/v1/admin/users?size=100", admin).getBody();
        assertThat(all).contains("person2@test.local").contains("ITO, Delhi - CPCB").contains("\"telegramLinked\":true")
                .doesNotContain("password").doesNotContain("$2a$").doesNotContain("999888777")
                .doesNotContain("accessToken").doesNotContain("TEST-TOKEN").doesNotContain(PASSWORD);

        Map<String, Object> page = http.exchange("/api/v1/admin/users?size=3&page=1&sort=email&dir=asc", HttpMethod.GET,
                new HttpEntity<>(bearer(admin)), new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
        assertThat(page).containsEntry("total", 8).containsEntry("page", 1).containsEntry("size", 3);
        // sorted by email: person1..person7, root-admin; page 1 of size 3 = entries 4-6
        assertThat(((List<?>) page.get("items")).toString()).contains("person4@test.local").contains("person5@test.local")
                .contains("person6@test.local").doesNotContain("person3@").doesNotContain("person7@");

        assertThat(get("/api/v1/admin/users?search=PERSON6", admin).getBody()).contains("person6@test.local").doesNotContain("person7");
        assertThat(get("/api/v1/admin/users?role=ADMIN", admin).getBody()).contains(ADMIN).doesNotContain("person1@");
        assertThat(get("/api/v1/admin/users?status=ACTIVE", admin).getBody()).contains("person1@test.local").doesNotContain("person2@");
        assertThat(get("/api/v1/admin/users?status=INACTIVE", admin).getBody()).contains("person3@test.local").doesNotContain("person1@");
        assertThat(get("/api/v1/admin/users?search=%25", admin).getBody()).contains("\"total\":0");   // LIKE wildcard escaped
        assertThat(get("/api/v1/admin/users?sort=password_hash;DROP", admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> stats = http.exchange("/api/v1/admin/users/stats", HttpMethod.GET, new HttpEntity<>(bearer(admin)),
                new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
        assertThat(stats).containsEntry("totalUsers", 8).containsEntry("adminAccounts", 1).containsEntry("normalAccounts", 7)
                .containsEntry("activeLast30Days", 2);                     // admin + person1 signed in
        String detail = get("/api/v1/admin/users/" + p2, admin).getBody();
        assertThat(detail).contains("\"healthProfile\":\"ASTHMA\"").contains("\"telegramLinked\":true")
                .doesNotContain("999888777").doesNotContain("$2a$").doesNotContain("latitude");
        assertThat(get("/api/v1/admin/users/999999", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void existingNormalUsersStillSignInAsUsers() {
        register("existing@test.local");
        ResponseEntity<Map<String, Object>> res = login("existing@test.local", "correct-horse-battery");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("role", "USER");
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    // ---- admin accounts have no Telegram ------------------------------------------------------------------

    private long idOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @Test
    void promotionUnlinksTelegramKeepsTheAccountAndHistoryAndSendsNothing() {
        String oldToken = register("owner@test.local");
        long id = idOf("owner@test.local");
        insertStation("ito", "ITO", 28.6, 77.2, true);
        jdbc.update("INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id) "
                + "VALUES (?, 'GENERAL', 'ito', 'en', '424242')", id);
        jdbc.update("INSERT INTO alerts (user_id, station_id, dedupe_key, kind, message, status) "
                + "VALUES (?, 'ito', 'hist-1', 'AQI_THRESHOLD_CROSSED', 'old alert', 'SENT')", id);
        register("normal@test.local");
        long other = idOf("normal@test.local");
        jdbc.update("INSERT INTO profiles (user_id, health_profile, language, telegram_chat_id) VALUES (?, 'GENERAL', 'en', '515151')", other);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode("Own3r#Proves-It"), id);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);

        assertThat(with("owner@test.local", "Own3r#Proves-It", false, false).provision()).isEqualTo(Outcome.PROMOTED_OWNER);

        Map<String, Object> u = jdbc.queryForMap("SELECT id, email, role, password_hash FROM users WHERE id = ?", id);
        assertThat(u).containsEntry("role", "ADMIN").containsEntry("email", "owner@test.local").containsEntry("password_hash", hash);
        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM profiles WHERE user_id = ?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT station_id FROM profiles WHERE user_id = ?", String.class, id)).isEqualTo("ito");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE user_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM profiles WHERE user_id = ?", String.class, other))
                .isEqualTo("515151");                                          // normal users untouched
        assertThat(WIREMOCK.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("/bot.*/sendMessage")))).isEmpty();
        // the session from before the promotion now has admin rights (role is read from the database)
        assertThat(get("/api/v1/admin/users", oldToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/me/account", oldToken).getBody()).contains("\"role\":\"ADMIN\"");
    }

    @Test
    void adminAccountsCannotLinkTestOrUseTelegramEvenWithAStaleUserToken() {
        String stale = register("staff@test.local");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = 'staff@test.local'");
        String fresh = (String) login("staff@test.local", "correct-horse-battery").getBody().get("accessToken");
        for (String token : List.of(stale, fresh)) {
            assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/test", token, null, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/api/v1/me/telegram", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThatThrownBy(() -> telegramLinks.createCode(idOf("staff@test.local"))).hasMessageContaining("Administrator");
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", register("member@test.local"), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);                    // normal users keep Telegram
    }

    @Test
    void aLinkCodeCreatedBeforePromotionCannotLinkTheAdmin() {
        String token = register("early@test.local");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getBody();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = 'early@test.local'");
        assertThat(telegramLinks.link((String) link.get("code"), "777000"))
                .isEqualTo(com.saanscheck.telegram.TelegramLinkService.LinkResult.INVALID_OR_EXPIRED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE telegram_chat_id = '777000'", Integer.class)).isZero();
    }

    @Test
    void noNotificationIsScheduledOrDeliverableForAnAdminEvenIfAChatIdWereForcedIn() {
        insertStation("ito", "ITO", 28.6, 77.2, true);
        insertAqi("ito", T0, 300, "VERY_POOR");
        register("forced@test.local");
        long id = idOf("forced@test.local");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", id);
        jdbc.update("INSERT INTO profiles (user_id, health_profile, station_id, language, telegram_chat_id, alert_min_category) "
                + "VALUES (?, 'GENERAL', 'ito', 'en', '999', 'GOOD')", id);

        assertThat(profiles.findAlertable()).noneMatch(p -> p.userId() == id);
        assertThat(profiles.briefingCandidates()).noneMatch(c -> c.userId() == id);
        assertThat(profiles.findChatId(id)).isEmpty();
        evaluator.evaluateAll();
        clock.set(java.time.Instant.parse("2026-11-20T01:35:00Z"));
        briefings.queueDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE user_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void roleChangesApplyToExistingTokensImmediately() {
        String admin = adminToken();
        assertThat(get("/api/v1/admin/users", admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE users SET role = 'USER' WHERE email = ?", ADMIN);
        assertThat(get("/api/v1/admin/users", admin).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM users WHERE email = ?", ADMIN);
        assertThat(get("/api/v1/admin/users", admin).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
