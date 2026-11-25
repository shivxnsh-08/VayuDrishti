package com.saanscheck.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/** API_DOCS_PUBLIC=false: Swagger/OpenAPI only for ADMIN. */
@TestPropertySource(properties = "saanscheck.security.public-api-docs=false")
class PrivateApiDocsIT extends AbstractIT {

    @Test
    void apiDocsRequireAdmin() {
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String user = register("user@test.local");
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", user, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String admin = register("admin@test.local");
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", admin, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
