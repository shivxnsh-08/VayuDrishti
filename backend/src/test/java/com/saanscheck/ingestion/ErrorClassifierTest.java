package com.saanscheck.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathBuilderException;
import java.time.Duration;
import java.time.Instant;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Failure classification, retry decisions, backoff and Retry-After (Phase 3). */
class ErrorClassifierTest {

    private static ResourceAccessException io(Throwable cause) {
        return new ResourceAccessException("I/O error on GET request for \"https://api.data.gov.in/...\"", cause instanceof java.io.IOException ioe ? ioe : new java.io.IOException(cause));
    }

    @Test
    void classifiesHttpErrors() {
        assertThat(ErrorClassifier.classify(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", null, null, StandardCharsets.UTF_8)))
                .isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(ErrorClassifier.classify(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "", null, null, StandardCharsets.UTF_8)))
                .isEqualTo(ErrorCategory.AUTH);
        assertThat(ErrorClassifier.classify(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "", null, null, StandardCharsets.UTF_8)))
                .isEqualTo(ErrorCategory.CLIENT_4XX);
        assertThat(ErrorClassifier.classify(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, StandardCharsets.UTF_8)))
                .isEqualTo(ErrorCategory.SOURCE_5XX);
    }

    @Test
    void classifiesNetworkTlsDnsAndTimeouts() {
        // The real failure seen on 2026-11-22: PKIX path building failed (certificate chain).
        assertThat(ErrorClassifier.classify(io(new SSLHandshakeException("PKIX path building failed"))))
                .isEqualTo(ErrorCategory.TLS);
        assertThat(ErrorClassifier.classify(io(new java.io.IOException(new CertPathBuilderException("no path")))))
                .isEqualTo(ErrorCategory.TLS);
        assertThat(ErrorClassifier.classify(io(new UnknownHostException("api.data.gov.in")))).isEqualTo(ErrorCategory.DNS);
        assertThat(ErrorClassifier.classify(io(new SocketTimeoutException("Read timed out")))).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ErrorClassifier.classify(io(new ConnectException("Connection refused")))).isEqualTo(ErrorCategory.NETWORK);
    }

    @Test
    void classifiesDatabaseAndParseErrors() {
        assertThat(ErrorClassifier.classify(new DataAccessResourceFailureException("db down"))).isEqualTo(ErrorCategory.DATABASE);
        assertThat(ErrorClassifier.classify(new IllegalStateException("Unexpected payload: no 'records' array")))
                .isEqualTo(ErrorCategory.PARSE);
    }

    @Test
    void onlyTransientCategoriesAreRetried() {
        assertThat(ErrorCategory.TLS.retryable()).isTrue();
        assertThat(ErrorCategory.RATE_LIMITED.retryable()).isTrue();
        assertThat(ErrorCategory.SOURCE_5XX.retryable()).isTrue();
        assertThat(ErrorCategory.AUTH.retryable()).isFalse();
        assertThat(ErrorCategory.CLIENT_4XX.retryable()).isFalse();
        assertThat(ErrorCategory.PARSE.retryable()).isFalse();
        assertThat(ErrorCategory.DATABASE.sourceSide()).isFalse();
    }

    @Test
    void backoffIsExponentialCappedAndHonoursRetryAfter() {
        IngestionProperties.Retry r = new IngestionProperties.Retry(4, Duration.ofSeconds(2), Duration.ofSeconds(30),
                Duration.ofSeconds(60));
        assertThat(DataGovClient.backoff(r, 1, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(DataGovClient.backoff(r, 2, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(DataGovClient.backoff(r, 3, null)).isEqualTo(Duration.ofSeconds(8));
        assertThat(DataGovClient.backoff(r, 10, null)).isEqualTo(Duration.ofSeconds(30));          // capped
        assertThat(DataGovClient.backoff(r, 1, Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(20)); // Retry-After wins
        assertThat(DataGovClient.backoff(r, 1, Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(60));   // ... up to the cap
    }

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-11-24T09:00:00Z");
        assertThat(DataGovClient.parseRetryAfter("7", now)).isEqualTo(Duration.ofSeconds(7));
        assertThat(DataGovClient.parseRetryAfter("Tue, 24 Nov 2026 09:00:30 GMT", now)).isEqualTo(Duration.ofSeconds(30));
        assertThat(DataGovClient.parseRetryAfter("garbage", now)).isNull();
        assertThat(DataGovClient.parseRetryAfter(null, now)).isNull();
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.RETRY_AFTER, "3");
        var e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", h, null, StandardCharsets.UTF_8);
        assertThat(DataGovClient.retryAfter(e, IngestionProperties.Retry.DEFAULT)).isEqualTo(Duration.ofSeconds(3));
    }
}
