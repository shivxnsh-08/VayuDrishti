package com.saanscheck.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Validates the Telegram configuration at start-up without ever blocking the application:
 * <ul>
 *   <li>no token: one WARN, linking and alert delivery are disabled (alerts stay PENDING);</li>
 *   <li>token present: {@code getMe} checks it; a rejected token is logged as an ERROR;</li>
 *   <li>the bot username shown on the website is TELEGRAM_BOT_USERNAME, or the one reported by
 *       {@code getMe} when that variable is empty; a mismatch between the two is logged.</li>
 * </ul>
 * The token itself is never logged.
 */
@Component
public class TelegramBotInfo {

    public enum State { NOT_CONFIGURED, UNVERIFIED, OK, TOKEN_REJECTED }

    private static final Logger log = LoggerFactory.getLogger(TelegramBotInfo.class);

    private final TelegramClient client;
    private final TelegramProperties props;
    private volatile State state;
    private volatile String discoveredUsername;

    public TelegramBotInfo(TelegramClient client, TelegramProperties props) {
        this.client = client;
        this.props = props;
        this.state = client.enabled() ? State.UNVERIFIED : State.NOT_CONFIGURED;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!client.enabled()) {
            state = State.NOT_CONFIGURED;
            log.warn("Telegram is not configured (TELEGRAM_BOT_TOKEN is empty): account linking and alert delivery are "
                    + "disabled; alerts stay PENDING until a token is set. See docs/operations.md#telegram.");
            return;
        }
        try {
            JsonNode me = client.getMe();
            String username = me == null ? null : me.path("result").path("username").asText(null);
            discoveredUsername = username;
            state = State.OK;
            String configured = configuredUsername();
            if (configured == null) {
                log.info("Telegram bot verified as @{} (TELEGRAM_BOT_USERNAME is empty; using the verified name)", username);
            } else if (username != null && !username.equalsIgnoreCase(configured)) {
                log.warn("TELEGRAM_BOT_USERNAME is '{}' but the token belongs to @{}: the website will link to the wrong bot",
                        configured, username);
            } else {
                log.info("Telegram bot verified as @{}", username);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 404) {
                state = State.TOKEN_REJECTED;
                log.error("Telegram rejected TELEGRAM_BOT_TOKEN (HTTP {}): check the token from @BotFather",
                        e.getStatusCode().value());
            } else {
                state = State.UNVERIFIED;
                log.warn("Telegram bot check failed (HTTP {}); will keep trying to deliver", e.getStatusCode().value());
            }
        } catch (RestClientException e) {
            state = State.UNVERIFIED;
            log.warn("Telegram bot check failed (cannot reach api.telegram.org: {}); will keep trying to deliver",
                    TelegramClient.redact(e.getClass().getSimpleName()));
        }
    }

    public State state() {
        return state;
    }

    public boolean configured() {
        return client.enabled();
    }

    /** Username for the website and deep links: configured value first, else the verified one; never with "@". */
    public String botUsername() {
        String c = configuredUsername();
        return c != null ? c : discoveredUsername;
    }

    private String configuredUsername() {
        String u = props.botUsername();
        if (u == null || u.isBlank()) {
            return null;
        }
        u = u.trim();
        return u.startsWith("@") ? u.substring(1) : u;
    }
}
