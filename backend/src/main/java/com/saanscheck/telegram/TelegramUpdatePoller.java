package com.saanscheck.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Polls the bot (getUpdates) and answers commands in private chats:
 * <ul>
 *   <li>{@code /start CODE} (or the bare code): link this chat to the account that generated the code;</li>
 *   <li>{@code /start} without a code, {@code /help}: how to connect;</li>
 *   <li>{@code /status}: whether this chat is linked (no account details are revealed);</li>
 *   <li>{@code /unlink}: stop alerts to this chat.</li>
 * </ul>
 * Polling avoids needing a public HTTPS webhook. Telegram allows one getUpdates consumer per bot: a second
 * instance, or a webhook on the bot, makes getUpdates fail with HTTP 409, which is logged as a WARN.
 */
@Component
@ConditionalOnProperty(name = {"saanscheck.scheduling.enabled", "saanscheck.telegram.poll-updates"},
        havingValue = "true", matchIfMissing = true)
public class TelegramUpdatePoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatePoller.class);

    static final String HELP = """
            VayuDrishti sends air-quality alerts for your saved station here.

            To connect: sign in on the VayuDrishti website, open Profile → Telegram, press "Generate link code" and \
            send /start CODE (or just the code) to this bot. Codes are valid for 15 minutes and work once.

            /status - is this chat connected?
            /unlink - stop alerts to this chat
            /help - this message""";

    private final TelegramClient client;
    private final TelegramLinkService links;
    private final AtomicLong nextOffset = new AtomicLong(0);
    private final AtomicReference<String> lastProblem = new AtomicReference<>(null);

    public TelegramUpdatePoller(TelegramClient client, TelegramLinkService links) {
        this.client = client;
        this.links = links;
    }

    @Scheduled(fixedDelayString = "PT5S")
    public void poll() {
        if (!client.enabled()) {
            return;
        }
        try {
            JsonNode body = client.getUpdates(nextOffset.get());
            problemCleared();
            for (JsonNode update : body.path("result")) {
                nextOffset.set(update.path("update_id").asLong() + 1);
                try {
                    handle(update.path("message"));
                } catch (RuntimeException e) {   // one bad update must not block the others
                    log.warn("Telegram update {} could not be handled: {}", update.path("update_id").asLong(),
                            TelegramClient.redact(e.toString()));
                }
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            problem(status == 409
                    ? "Telegram getUpdates conflict (HTTP 409): another process is polling this bot or a webhook is set; "
                      + "linking messages will not be received by this instance"
                    : status == 401 || status == 404
                    ? "Telegram rejected the bot token (HTTP " + status + "); linking and alerts cannot work"
                    : "Telegram getUpdates failed with HTTP " + status);
        } catch (RuntimeException e) {
            log.debug("Telegram getUpdates failed: {}", TelegramClient.redact(String.valueOf(e.getMessage())));
        }
    }

    void handle(JsonNode message) {
        if (message.isMissingNode()) {
            return;
        }
        String text = message.path("text").asText("").trim();
        String chatId = message.path("chat").path("id").asText(null);
        if (chatId == null || text.isEmpty()) {
            return;
        }
        if (!"private".equals(message.path("chat").path("type").asText("private"))) {
            reply(chatId, "Please message me in a private chat: alerts are personal and are only sent to private chats.");
            return;
        }
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');                    // "/start@VayuDrishtiBot CODE"
        if (command.startsWith("/") && at > 0) {
            command = command.substring(0, at);
        }
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (command) {
            case "/start" -> {
                if (arg.isEmpty()) {
                    reply(chatId, "Welcome to VayuDrishti.\n\n" + HELP);
                } else {
                    reply(chatId, linkReply(links.link(arg, chatId)));
                }
            }
            case "/help" -> reply(chatId, HELP);
            case "/status" -> reply(chatId, links.isChatLinked(chatId)
                    ? "This chat is connected to a VayuDrishti account. Alerts for the saved station arrive here."
                    : "This chat is not connected. Generate a link code in the VayuDrishti website (Profile → Telegram).");
            case "/unlink" -> reply(chatId, links.unlinkChat(chatId)
                    ? "Disconnected. You will not receive VayuDrishti alerts here any more. You can reconnect from the website."
                    : "This chat was not connected to any VayuDrishti account.");
            default -> {
                if (command.startsWith("/")) {
                    reply(chatId, "Unknown command.\n\n" + HELP);
                } else {
                    reply(chatId, linkReply(links.link(text, chatId)));   // a bare code
                }
            }
        }
    }

    static String linkReply(TelegramLinkService.LinkResult r) {
        return switch (r) {
            case LINKED -> "Linked. You will receive VayuDrishti air-quality alerts here. Send /status to check, /unlink to stop.";
            case ALREADY_LINKED -> "This chat was already linked to your account. Nothing changed.";
            case INVALID_OR_EXPIRED -> "That code is invalid or expired. Generate a new one in the website (Profile → Telegram).";
            case CHAT_LINKED_TO_OTHER_ACCOUNT -> "This chat is already connected to another VayuDrishti account. "
                    + "Send /unlink first if you want to connect it to a different account.";
            case TOO_MANY_ATTEMPTS -> "Too many invalid codes. Please wait 15 minutes, then try again with a new code.";
        };
    }

    /** Bot replies are plain sentences: escaped and sent as Telegram HTML under the VayuDrishti header. */
    private void reply(String chatId, String text) {
        try {
            client.sendMessage(chatId, "🌬️ <b>VayuDrishti</b>\n" + TelegramHtml.DIVIDER + "\n\n" + TelegramHtml.esc(text));
        } catch (TelegramDeliveryException e) {
            log.debug("Reply to chat failed: {}", e.getMessage());
        }
    }

    private void problem(String message) {
        if (!message.equals(lastProblem.getAndSet(message))) {
            log.warn(message);                            // logged once per change, not every 5 seconds
        }
    }

    private void problemCleared() {
        if (lastProblem.getAndSet(null) != null) {
            log.info("Telegram getUpdates is working again");
        }
    }
}
