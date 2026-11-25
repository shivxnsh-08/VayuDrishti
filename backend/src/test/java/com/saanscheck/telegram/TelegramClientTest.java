package com.saanscheck.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bot token is part of every Bot API URL; it must never reach logs or stored error text. */
class TelegramClientTest {

    @Test
    void tokenIsRedactedFromErrorText() {
        String err = "I/O error on POST request for \"https://api.telegram.org/bot123456:ABC-secret_x/sendMessage\": timeout";
        assertThat(TelegramClient.redact(err)).doesNotContain("123456:ABC-secret_x").contains("/bot***/sendMessage");
        assertThat(TelegramClient.redact(null)).isNull();
    }

    @Test
    void disabledWithoutAToken() {
        assertThat(new TelegramProperties("", "", null, true, null, null, null).enabled()).isFalse();
        assertThat(new TelegramProperties("  ", "", null, true, null, null, null).enabled()).isFalse();
        assertThat(new TelegramProperties("123:abc", "", null, true, null, null, null).enabled()).isTrue();
    }

    @Test
    void floodControlRetryAfterIsParsed() {
        assertThat(TelegramClient.retryAfter("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\": 37}}"))
                .isEqualTo(java.time.Duration.ofSeconds(37));
        assertThat(TelegramClient.retryAfter("{\"ok\":false}")).isNull();
        assertThat(TelegramClient.retryAfter(null)).isNull();
    }

    @Test
    void linkRepliesNeverContainAccountDetails() {
        for (TelegramLinkService.LinkResult r : TelegramLinkService.LinkResult.values()) {
            String reply = TelegramUpdatePoller.linkReply(r);
            assertThat(reply).isNotBlank().doesNotContain("@").doesNotContainIgnoringCase("email");
        }
    }

    @Test
    void deepLinkUsesTheConfiguredBotOnlyWhenKnown() {
        assertThat(TelegramLinkService.deepLink("VayuTestBot", "ABCD2345")).isEqualTo("https://t.me/VayuTestBot?start=ABCD2345");
        assertThat(TelegramLinkService.deepLink(null, "ABCD2345")).isNull();
        assertThat(TelegramLinkService.deepLink(" ", "ABCD2345")).isNull();
    }
}
