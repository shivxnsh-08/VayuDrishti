package com.saanscheck.telegram;

/**
 * Delivery failure with an explicit outcome, because the retry decision depends on whether
 * the message might already have reached the user.
 */
public class TelegramDeliveryException extends RuntimeException {

    public enum Outcome {
        /** Definitely not delivered (connection refused, 5xx, 429): safe to retry. */
        RETRYABLE,
        /** Will never succeed (bot blocked, bad chat id): stop. */
        PERMANENT,
        /** Request may have reached Telegram (e.g. read timeout): do not retry, to avoid a duplicate. */
        UNKNOWN
    }

    private final Outcome outcome;
    private final java.time.Duration retryAfter;

    public TelegramDeliveryException(Outcome outcome, String message) {
        this(outcome, message, null);
    }

    public TelegramDeliveryException(Outcome outcome, String message, java.time.Duration retryAfter) {
        super(message);
        this.outcome = outcome;
        this.retryAfter = retryAfter;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Telegram's own "retry_after" for HTTP 429 (flood control), or null. */
    public java.time.Duration retryAfter() {
        return retryAfter;
    }
}
