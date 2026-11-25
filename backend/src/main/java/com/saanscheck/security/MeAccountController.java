package com.saanscheck.security;

import com.saanscheck.common.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the signed-in caller is, from the database (not from anything the browser stored). The frontend uses this
 * to choose between the normal app and the admin interface; the backend still authorises every request itself.
 */
@RestController
public class MeAccountController {

    private final UserRepository users;

    public MeAccountController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/v1/me/account")
    public Account account(@AuthenticationPrincipal Jwt jwt) {
        UserEntity u = users.findById(CurrentUser.id(jwt)).orElseThrow(() -> new NotFoundException("Account not found"));
        return new Account(u.getId(), u.getEmail(), u.getRole());
    }

    public record Account(long id, String email, String role) {
    }
}
