package com.saanscheck.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** The JWT subject is the numeric user id. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
