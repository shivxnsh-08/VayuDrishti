package com.saanscheck.ingestion;

/** A page fetch that failed after all permitted retries (or immediately, for non-retryable errors). */
public class SourceFetchException extends RuntimeException {

    private final ErrorCategory category;
    private final Integer httpStatus;
    private final int retries;

    public SourceFetchException(ErrorCategory category, Integer httpStatus, int retries, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.httpStatus = httpStatus;
        this.retries = retries;
    }

    public ErrorCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public int retries() {
        return retries;
    }
}
