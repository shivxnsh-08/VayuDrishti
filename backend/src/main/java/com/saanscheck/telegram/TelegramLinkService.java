package com.saanscheck.telegram;

import com.saanscheck.common.RateLimitedException;
import com.saanscheck.common.SlidingWindowLimiter;
import com.saanscheck.profile.ProfileRepository;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;

/**
 * One-time link codes between a VayuDrishti account and a private Telegram chat.
 *
 * <ul>
 *   <li>Codes: 8 characters from a 32-symbol alphabet via {@link SecureRandom} (~40 bits), random, so they carry
 *       no user information; valid for {@code link-code-ttl} (15 min); one active code per account.</li>
 *   <li>Single use: the code row is deleted atomically ({@code DELETE ... RETURNING}) when it links a chat,
 *       so it can never link a second chat.</li>
 *   <li>One chat links to at most one account (checked here and enforced by a unique index); an account has at
 *       most one chat (linking a new chat replaces the old one).</li>
 *   <li>Throttling: code generation per account, and failed attempts per chat (stops code guessing).</li>
 * </ul>
 */
@Service
public class TelegramLinkService {

    public enum LinkResult { LINKED, ALREADY_LINKED, INVALID_OR_EXPIRED, CHAT_LINKED_TO_OTHER_ACCOUNT, TOO_MANY_ATTEMPTS }

    static final int MAX_CODES_PER_WINDOW = 5;
    static final int MAX_FAILED_ATTEMPTS_PER_WINDOW = 5;
    static final Duration LIMIT_WINDOW = Duration.ofMinutes(15);

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no 0/O/1/I
    private static final int LENGTH = 8;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProfileRepository profiles;
    private final TelegramProperties props;
    private final TelegramBotInfo bot;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final SlidingWindowLimiter codeRequests;
    private final SlidingWindowLimiter failedAttempts;

    public TelegramLinkService(NamedParameterJdbcTemplate jdbc, ProfileRepository profiles,
                               TelegramProperties props, TelegramBotInfo bot, Clock clock) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.props = props;
        this.bot = bot;
        this.clock = clock;
        this.codeRequests = new SlidingWindowLimiter(MAX_CODES_PER_WINDOW, LIMIT_WINDOW, clock);
        this.failedAttempts = new SlidingWindowLimiter(MAX_FAILED_ATTEMPTS_PER_WINDOW, LIMIT_WINDOW, clock);
    }

    @Transactional
    public LinkCode createCode(long userId) {
        if (!bot.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Telegram alerts are not configured on this server (no bot token)");
        }
        if (isAdmin(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator accounts cannot use Telegram");
        }
        if (!codeRequests.tryAcquire("user:" + userId)) {
            throw new RateLimitedException("Too many link codes requested; try again in a few minutes");
        }
        Instant expires = clock.instant().plus(props.linkCodeTtl());
        String code = randomCode();
        jdbc.update("DELETE FROM telegram_link_codes WHERE user_id = :uid", new MapSqlParameterSource("uid", userId));
        jdbc.update("INSERT INTO telegram_link_codes (code, user_id, expires_at) VALUES (:c, :uid, :exp)",
                new MapSqlParameterSource().addValue("c", code).addValue("uid", userId)
                        .addValue("exp", Timestamp.from(expires)));
        String username = bot.botUsername();
        return new LinkCode(code, expires, username, deepLink(username, code));
    }

    /** Links {@code chatId} (a private chat) using a code typed in the bot. */
    @Transactional
    public LinkResult link(String code, String chatId) {
        String chatKey = "chat:" + chatId;
        if (failedAttempts.isLimited(chatKey)) {
            return LinkResult.TOO_MANY_ATTEMPTS;
        }
        String normalized = code == null ? "" : code.trim().toUpperCase();
        Optional<Long> owner = jdbc.queryForList(
                "SELECT user_id FROM telegram_link_codes WHERE code = :c AND expires_at > :now",
                new MapSqlParameterSource().addValue("c", normalized).addValue("now", Timestamp.from(clock.instant())),
                Long.class).stream().findFirst();
        if (owner.isEmpty()) {
            failedAttempts.record(chatKey);
            return LinkResult.INVALID_OR_EXPIRED;
        }
        long userId = owner.get();
        if (isAdmin(userId)) {                         // defence in depth: an admin's code never links a chat
            jdbc.update("DELETE FROM telegram_link_codes WHERE user_id = :uid", new MapSqlParameterSource("uid", userId));
            failedAttempts.record(chatKey);
            return LinkResult.INVALID_OR_EXPIRED;
        }
        Optional<Long> linkedTo = profiles.findUserIdByChatId(chatId);
        if (linkedTo.isPresent() && linkedTo.get() != userId) {
            return LinkResult.CHAT_LINKED_TO_OTHER_ACCOUNT;      // code is kept: /unlink, then retry
        }
        // Single use: only the caller that deletes the row may link (concurrent attempts get nothing back).
        Optional<Long> consumed = jdbc.queryForList("""
                DELETE FROM telegram_link_codes WHERE code = :c AND expires_at > :now RETURNING user_id
                """, new MapSqlParameterSource().addValue("c", normalized)
                .addValue("now", Timestamp.from(clock.instant())), Long.class).stream().findFirst();
        if (consumed.isEmpty()) {
            failedAttempts.record(chatKey);
            return LinkResult.INVALID_OR_EXPIRED;
        }
        if (linkedTo.isPresent()) {
            failedAttempts.reset(chatKey);
            return LinkResult.ALREADY_LINKED;
        }
        try {
            profiles.setTelegramChatId(userId, chatId);
        } catch (DuplicateKeyException race) {
            // Another account linked this chat concurrently; undo the code deletion too.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return LinkResult.CHAT_LINKED_TO_OTHER_ACCOUNT;
        }
        failedAttempts.reset(chatKey);
        return LinkResult.LINKED;
    }

    /** Kept for existing callers: the linked user id if the code linked the chat. */
    @Transactional
    public Optional<Long> consume(String code, String chatId) {
        Optional<Long> before = jdbc.queryForList("SELECT user_id FROM telegram_link_codes WHERE code = :c",
                new MapSqlParameterSource("c", code == null ? "" : code.trim().toUpperCase()), Long.class).stream().findFirst();
        LinkResult r = link(code, chatId);
        return r == LinkResult.LINKED || r == LinkResult.ALREADY_LINKED ? before : Optional.empty();
    }

    /** Unlinks the account's chat and drops any pending code. Returns true if a chat was linked. */
    @Transactional
    public boolean unlinkUser(long userId) {
        jdbc.update("DELETE FROM telegram_link_codes WHERE user_id = :uid", new MapSqlParameterSource("uid", userId));
        return profiles.clearTelegramChat(userId);
    }

    /** The bot's /unlink. Returns true if this chat was linked. */
    @Transactional
    public boolean unlinkChat(String chatId) {
        return profiles.clearTelegramChatByChatId(chatId);
    }

    public boolean isChatLinked(String chatId) {
        return profiles.findUserIdByChatId(chatId).isPresent();
    }

    public boolean isUserLinked(long userId) {
        return profiles.findChatId(userId).isPresent();
    }

    /** The caller's own chat id (server-side use only, never returned to clients). */
    public Optional<String> chatOf(long userId) {
        return profiles.findChatId(userId);
    }

    private boolean isAdmin(long userId) {
        return jdbc.queryForList("SELECT role FROM users WHERE id = :uid", new MapSqlParameterSource("uid", userId),
                String.class).stream().anyMatch("ADMIN"::equals);
    }

    static String deepLink(String botUsername, String code) {
        return botUsername == null || botUsername.isBlank() ? null : "https://t.me/" + botUsername + "?start=" + code;
    }

    private String randomCode() {
        char[] c = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            c[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(c);
    }

    /** What the website shows: the code, when it expires and which bot to send it to. */
    public record LinkCode(String code, Instant expiresAt, String botUsername, String deepLink) {
    }
}
