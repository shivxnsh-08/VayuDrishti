package com.saanscheck.ingestion;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** Maps an exception (and its cause chain) to an {@link ErrorCategory}. Pure; unit-tested. */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    public static ErrorCategory classify(Throwable e) {
        if (e instanceof SourceFetchException s) {
            return s.category();
        }
        if (e instanceof HttpClientErrorException h) {
            int code = h.getStatusCode().value();
            if (code == 429) {
                return ErrorCategory.RATE_LIMITED;
            }
            return code == 401 || code == 403 ? ErrorCategory.AUTH : ErrorCategory.CLIENT_4XX;
        }
        if (e instanceof HttpServerErrorException) {
            return ErrorCategory.SOURCE_5XX;
        }
        if (e instanceof DataAccessException) {
            return ErrorCategory.DATABASE;
        }
        // Network-level problems: look through the cause chain.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException || c instanceof CertPathBuilderException || c instanceof CertificateException) {
                return ErrorCategory.TLS;
            }
            if (c instanceof UnknownHostException) {
                return ErrorCategory.DNS;
            }
            if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                return ErrorCategory.TIMEOUT;
            }
            if (c instanceof ConnectException || c instanceof NoRouteToHostException || c instanceof SocketException) {
                return ErrorCategory.NETWORK;
            }
            if (c == c.getCause()) {
                break;
            }
        }
        if (e instanceof ResourceAccessException) {
            return ErrorCategory.NETWORK;
        }
        if (e instanceof RestClientException || e instanceof IllegalStateException) {
            return ErrorCategory.PARSE;   // response could not be read / unexpected payload
        }
        return ErrorCategory.UNKNOWN;
    }

    public static Integer httpStatus(Throwable e) {
        if (e instanceof SourceFetchException s) {
            return s.httpStatus();
        }
        if (e instanceof HttpClientErrorException h) {
            return h.getStatusCode().value();
        }
        if (e instanceof HttpServerErrorException h) {
            return h.getStatusCode().value();
        }
        return null;
    }
}
