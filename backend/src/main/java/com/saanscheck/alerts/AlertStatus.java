package com.saanscheck.alerts;

/**
 * PENDING -> SENDING -> SENT, or back to PENDING (retryable failure, with backoff),
 * or FAILED (permanent / outcome unknown / attempts exhausted). SUPPRESSED rows record
 * alerts withheld by quiet hours so the user can see them in their history.
 */
public enum AlertStatus {
    PENDING, SENDING, SENT, FAILED, SUPPRESSED
}
