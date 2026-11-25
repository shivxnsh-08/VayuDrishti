package com.saanscheck.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/** An unexpected bug must surface as 500 (not 401), without leaking internals; security exceptions pass through. */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedExceptionIsA500WithoutInternalDetails() throws Exception {
        ProblemDetail p = handler.unexpected(new NullPointerException("password=hunter2 at SELECT * FROM users"));
        assertThat(p.getStatus()).isEqualTo(500);
        assertThat(p.getDetail()).isEqualTo("Internal server error");
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> handler.unexpected(new AccessDeniedException("no"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> handler.unexpected(new InsufficientAuthenticationException("no")))
                .isInstanceOf(InsufficientAuthenticationException.class);
    }
}
