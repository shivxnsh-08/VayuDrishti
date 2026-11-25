package com.saanscheck.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.saanscheck.common.HttpClients;
import com.saanscheck.telegram.TelegramDeliveryException.Outcome;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Minimal Telegram Bot API client over plain HTTP (sendMessage, getUpdates, getMe). */
@Component
public class TelegramClient {

    private static final Pattern TOKEN_IN_URL = Pattern.compile("/bot[^/\\s\"]+");

    private final TelegramProperties props;
    private final RestClient client;

    public TelegramClient(RestClient.Builder builder, TelegramProperties props) {
        this.props = props;
        this.client = HttpClients.withTimeouts(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * Sends a Telegram-HTML message (parse_mode=HTML). Callers build text with {@link TelegramHtml} (escaped).
     * If Telegram still rejects the markup ("can't parse entities") the message is re-sent once as plain text,
     * so a formatting mistake can never swallow an alert. That rejection happens before delivery, so the
     * fallback cannot produce a duplicate.
     */
    public void sendMessage(String chatId, String text) {
        try {
            post(chatId, text, true);
        } catch (TelegramDeliveryException e) {
            if (e.outcome() == Outcome.PERMANENT && e.getMessage() != null && e.getMessage().contains("HTTP 400")
                    && e.getMessage().toLowerCase().contains("parse entities")) {
                post(chatId, TelegramHtml.toPlain(text), false);
                return;
            }
            throw e;
        }
    }

    private void post(String chatId, String text, boolean html) {
        try {
            Map<String, Object> body = new java.util.HashMap<>(Map.of(
                    "chat_id", chatId, "text", TelegramHtml.fit(text), "disable_web_page_preview", true));
            if (html) {
                body.put("parse_mode", "HTML");
            }
            client.post()
                    .uri("/bot{token}/sendMessage", props.botToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            boolean flood = e.getStatusCode().value() == 429;
            throw new TelegramDeliveryException(flood ? Outcome.RETRYABLE : Outcome.PERMANENT,
                    "HTTP " + e.getStatusCode().value() + ": " + redact(e.getResponseBodyAsString()),
                    flood ? retryAfter(e.getResponseBodyAsString()) : null);
        } catch (HttpServerErrorException e) {
            throw new TelegramDeliveryException(Outcome.RETRYABLE, "HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            boolean neverSent = cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException;
            throw new TelegramDeliveryException(neverSent ? Outcome.RETRYABLE : Outcome.UNKNOWN,
                    redact(String.valueOf(cause)));
        }
    }

    public JsonNode getUpdates(long offset) {
        return client.get()
                .uri(b -> b.path("/bot{token}/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", 0)
                        .build(props.botToken()))
                .retrieve()
                .body(JsonNode.class);
    }

    /** getMe: the bot's identity. Throws RestClientException (e.g. HTTP 401 for a wrong token). */
    public JsonNode getMe() {
        return client.get().uri("/bot{token}/getMe", props.botToken()).retrieve().body(JsonNode.class);
    }

    /** Telegram flood control: {"ok":false,"error_code":429,"parameters":{"retry_after":N}}. */
    static Duration retryAfter(String body) {
        java.util.regex.Matcher m = RETRY_AFTER.matcher(body == null ? "" : body);
        return m.find() ? Duration.ofSeconds(Long.parseLong(m.group(1))) : null;
    }

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");

    static String redact(String s) {
        return s == null ? null : TOKEN_IN_URL.matcher(s).replaceAll("/bot***");
    }
}
