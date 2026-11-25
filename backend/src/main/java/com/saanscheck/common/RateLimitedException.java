package com.saanscheck.common;

/** The caller did this too often; answered with HTTP 429. */
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
