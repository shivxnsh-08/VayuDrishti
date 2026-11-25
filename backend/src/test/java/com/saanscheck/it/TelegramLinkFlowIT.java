package com.saanscheck.it;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.saanscheck.telegram.TelegramClient;
import com.saanscheck.telegram.TelegramLinkService;
import com.saanscheck.telegram.TelegramUpdatePoller;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * The complete Telegram linking path against a MOCKED Bot API (WireMock; no real Telegram call is made):
 * code generation, the bot's /start, /help, /status and /unlink, expiry, single use, one chat per account,
 * private chats only, throttling, website unlink and the test message.
 */
class TelegramLinkFlowIT extends AbstractIT {

    private static final String BOT = "/botTEST-TOKEN";
    private static final AtomicInteger DAY = new AtomicInteger();

    @Autowired
    TelegramClient client;
    @Autowired
    TelegramLinkService links;

    @BeforeEach
    void ownTimeWindow() {
        // Rate limiters are singletons in the shared context: give every test its own day so windows never overlap.
        clock.set(T0.plus(Duration.ofDays(DAY.incrementAndGet())));
        WIREMOCK.stubFor(post(urlPathEqualTo(BOT + "/sendMessage")).willReturn(okJson("{\"ok\":true}")));
    }

    private static String update(long id, long chatId, String type, String text) {
        return "{\"ok\":true,\"result\":[{\"update_id\":" + id + ",\"message\":{\"message_id\":1,\"chat\":{\"id\":"
                + chatId + ",\"type\":\"" + type + "\"},\"text\":\"" + text + "\"}}]}";
    }

    /** Simulates one message arriving at the bot and one poll. */
    private void botReceives(long chatId, String text) {
        botReceives(chatId, "private", text);
    }

    private void botReceives(long chatId, String type, String text) {
        WIREMOCK.stubFor(get(urlPathEqualTo(BOT + "/getUpdates")).willReturn(okJson(update(1, chatId, type, text))));
        new TelegramUpdatePoller(client, links).poll();
    }

    private void assertReplied(long chatId, String fragment) {
        WIREMOCK.verify(postRequestedFor(urlPathEqualTo(BOT + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", com.github.tomakehurst.wiremock.client.WireMock.equalTo(String.valueOf(chatId))))
                .withRequestBody(containing(fragment)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> newCode(String token) {
        return exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String token) {
        return exchange(HttpMethod.GET, "/api/v1/me/telegram", token, null, Map.class).getBody();
    }

    @Test
    void startCommandLinksTheChatOnceAndTheWebsiteSeesIt() {
        String token = register("link@test.local");
        assertThat(status(token)).containsEntry("configured", true).containsEntry("linked", false);

        Map<String, Object> link = newCode(token);
        String code = (String) link.get("code");
        assertThat(code).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(link).containsKeys("expiresAt", "botUsername", "deepLink");
        assertThat(link.toString()).doesNotContain("TEST-TOKEN");

        botReceives(5551234, "/start " + code);

        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM profiles", String.class)).isEqualTo("5551234");
        assertReplied(5551234, "Linked");
        Map<String, Object> s = status(token);
        assertThat(s).containsEntry("linked", true);
        assertThat(s.toString()).doesNotContain("5551234");                 // chat id never leaves the server
    }

    @Test
    void startWithBotSuffixAndBareCodeBothWork() {
        String token = register("suffix@test.local");
        botReceives(77, "/start@VayuDrishtiBot " + newCode(token).get("code"));
        assertThat(status(token)).containsEntry("linked", true);

        String token2 = register("bare@test.local");
        botReceives(78, (String) newCode(token2).get("code"));
        assertThat(status(token2)).containsEntry("linked", true);
    }

    @Test
    void codeExpiresAfterFifteenMinutes() {
        String token = register("expiry@test.local");
        String code = (String) newCode(token).get("code");
        clock.advance(Duration.ofMinutes(16));
        botReceives(42, "/start " + code);
        assertReplied(42, "invalid or expired");
        assertThat(status(token)).containsEntry("linked", false);
    }

    @Test
    void codeIsSingleUseAndCannotLinkASecondChat() {
        String token = register("once@test.local");
        String code = (String) newCode(token).get("code");
        botReceives(100, "/start " + code);
        botReceives(200, "/start " + code);
        assertReplied(200, "invalid or expired");
        assertThat(jdbc.queryForObject("SELECT telegram_chat_id FROM profiles", String.class)).isEqualTo("100");
    }

    @Test
    void oneChatCannotBeLinkedToTwoAccountsUntilItIsUnlinked() {
        String alice = register("alice@test.local");
        botReceives(300, "/start " + newCode(alice).get("code"));
        String bob = register("bob@test.local");
        String bobCode = (String) newCode(bob).get("code");

        botReceives(300, "/start " + bobCode);
        assertReplied(300, "already connected to another VayuDrishti account");
        assertThat(status(bob)).containsEntry("linked", false);
        assertThat(status(alice)).containsEntry("linked", true);

        botReceives(300, "/unlink");
        assertReplied(300, "Disconnected");
        assertThat(status(alice)).containsEntry("linked", false);
        botReceives(300, "/start " + bobCode);                          // the code was kept and still works
        assertThat(status(bob)).containsEntry("linked", true);
    }

    @Test
    void groupChatsAreRefused() {
        String token = register("group@test.local");
        botReceives(-1001, "group", "/start " + newCode(token).get("code"));
        assertReplied(-1001, "private chat");
        assertThat(status(token)).containsEntry("linked", false);
    }

    @Test
    void helpAndStatusCommandsRevealNoAccountDetails() {
        botReceives(400, "/help");
        assertReplied(400, "/unlink");
        botReceives(400, "/status");
        assertReplied(400, "not connected");

        String token = register("status-check@test.local");
        botReceives(400, "/start " + newCode(token).get("code"));
        botReceives(400, "/status");
        assertReplied(400, "is connected");
        WIREMOCK.findAll(postRequestedFor(urlPathEqualTo(BOT + "/sendMessage")))
                .forEach(r -> assertThat(r.getBodyAsString()).doesNotContain("status-check@test.local"));
    }

    @Test
    void websiteUnlinkStopsAlerts() {
        String token = register("unlink@test.local");
        botReceives(500, "/start " + newCode(token).get("code"));
        assertThat(exchange(HttpMethod.DELETE, "/api/v1/me/telegram", token, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(token)).containsEntry("linked", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles WHERE telegram_chat_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void codeGenerationIsThrottledPerAccount() {
        String token = register("throttle@test.local");
        for (int i = 0; i < 5; i++) {
            assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        clock.advance(Duration.ofMinutes(16));
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", token, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void repeatedWrongCodesFromOneChatAreThrottled() {
        String token = register("guess@test.local");
        String good = (String) newCode(token).get("code");
        for (int i = 0; i < 5; i++) {
            botReceives(600, "/start WRONG" + i + "XY");
        }
        botReceives(600, "/start " + good);                             // even a valid code is refused for now
        assertReplied(600, "Too many invalid codes");
        assertThat(status(token)).containsEntry("linked", false);
    }

    @Test
    void testMessageGoesOnlyToTheCallersOwnChatAndIsThrottled() {
        String token = register("tester@test.local");
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/test", token, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);                        // not linked yet
        botReceives(700, "/start " + newCode(token).get("code"));
        for (int i = 0; i < 3; i++) {
            assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/test", token, null, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertReplied(700, "VayuDrishti Telegram Connection Successful");
        WIREMOCK.verify(postRequestedFor(urlPathEqualTo(BOT + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.parse_mode", com.github.tomakehurst.wiremock.client.WireMock.equalTo("HTML"))));
        assertThat(jdbc.queryForList("SELECT kind || ':' || status FROM alerts WHERE kind = 'TEST_MESSAGE'", String.class))
                .hasSize(3).containsOnly("TEST_MESSAGE:SENT");                 // each test message is in the history
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/test", token, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void statusResponseHasExactlyTheFieldsTheWebsiteUsesAndNoChatId() {
        String token = register("fields@test.local");
        botReceives(800, "/start " + newCode(token).get("code"));
        assertThat(status(token).keySet()).containsExactlyInAnyOrder("configured", "botState", "botUsername", "botLink", "linked");
        assertThat(status(token)).containsEntry("linked", true).containsEntry("configured", true);
        assertThat(status(token).values().toString()).doesNotContain("800").doesNotContain("TEST-TOKEN");
    }

    @Test
    void testMessageIgnoresAChatIdSuppliedByTheClient() {
        String owner = register("owner@test.local");
        botReceives(900, "/start " + newCode(owner).get("code"));
        String other = register("other@test.local");
        botReceives(901, "/start " + newCode(other).get("code"));
        WIREMOCK.resetRequests();

        exchange(HttpMethod.POST, "/api/v1/me/telegram/test", owner, Map.of("chat_id", "901", "chatId", "901"), Map.class);

        WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo(BOT + "/sendMessage")));
        assertReplied(900, "VayuDrishti Telegram Connection Successful");
        WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo(BOT + "/sendMessage"))
                .withRequestBody(matchingJsonPath("$.chat_id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("901"))));
    }

    @Test
    void telegramEndpointsRequireSignIn() {
        assertThat(exchange(HttpMethod.GET, "/api/v1/me/telegram", null, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.POST, "/api/v1/me/telegram/link", null, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
