package com.saanscheck.ingestion;

/**
 * Why an ingestion attempt failed. {@code retryable} decides whether a bounded retry can help;
 * {@code sourceSide} means the problem is on the data source / network path rather than in VayuDrishti
 * (used to report SOURCE_UNAVAILABLE instead of FAILED).
 */
public enum ErrorCategory {
    NETWORK(true, true),
    TIMEOUT(true, true),
    DNS(true, true),
    TLS(true, true),
    RATE_LIMITED(true, true),
    SOURCE_5XX(true, true),
    AUTH(false, false),          // invalid / missing API key: retrying cannot help
    CLIENT_4XX(false, false),    // bad request, unknown resource: configuration problem
    PARSE(false, true),          // unexpected payload shape from the source
    DATABASE(false, false),
    CONFIG(false, false),
    UNKNOWN(false, false);

    private final boolean retryable;
    private final boolean sourceSide;

    ErrorCategory(boolean retryable, boolean sourceSide) {
        this.retryable = retryable;
        this.sourceSide = sourceSide;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean sourceSide() {
        return sourceSide;
    }
}
