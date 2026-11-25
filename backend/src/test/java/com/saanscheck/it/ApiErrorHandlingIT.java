package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Client errors on public endpoints must be reported as client errors (RFC 7807), not as 401: before the fix,
 * framework exceptions were forwarded to /error, which Spring Security rejects for anonymous callers, so a
 * malformed JSON body on /auth/register looked like "Unauthorized". Protected endpoints still return 401.
 */
class ApiErrorHandlingIT extends AbstractIT {

    private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() { };

    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, String rawBody, MediaType type) {
        HttpHeaders h = new HttpHeaders();
        if (type != null) {
            h.setContentType(type);
        }
        return http.exchange(path, method, new HttpEntity<>(rawBody, h), PROBLEM);
    }

    @Test
    void malformedJsonOnAPublicEndpointIs400() {
        ResponseEntity<Map<String, Object>> r = send(HttpMethod.POST, "/api/v1/auth/register", "{bad json", MediaType.APPLICATION_JSON);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("status", 400).containsKey("detail");
    }

    @Test
    void unsupportedMediaTypeAndMethodAreReportedCorrectly() {
        assertThat(send(HttpMethod.POST, "/api/v1/auth/login", "email=a", MediaType.TEXT_PLAIN).getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(send(HttpMethod.PUT, "/api/v1/auth/login", "{}", MediaType.APPLICATION_JSON).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void typeMismatchDoesNotExposeJavaTypeNames() {
        ResponseEntity<Map<String, Object>> r = send(HttpMethod.GET, "/api/v1/stations/nearest?lat=abc&lon=77", null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(r.getBody().get("detail"))).contains("lat").doesNotContain("java.lang");
    }

    @Test
    void validationAndAuthBehaviourIsUnchanged() {
        ResponseEntity<Map<String, Object>> invalid = send(HttpMethod.POST, "/api/v1/auth/register", "{\"email\":\"bad\",\"password\":\"x\"}",
                MediaType.APPLICATION_JSON);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(invalid.getBody().get("detail"))).contains("email").contains("password");
        assertThat(send(HttpMethod.GET, "/api/v1/me/profile", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.GET, "/api/v1/admin/ingestion-runs", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
